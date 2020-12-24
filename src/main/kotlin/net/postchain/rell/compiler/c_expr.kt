/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.C_BinOp
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_NameExprPair
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_EnumValue

sealed class C_ExprContextAttr(val type: R_Type) {
    abstract fun compile(pos: S_Pos): V_Expr
}

class C_ExprContextAttr_DbAtEntity(
        private val entity: C_AtEntity,
        private val attrRef: C_EntityAttrRef
): C_ExprContextAttr(attrRef.type()) {
    override fun compile(pos: S_Pos) = V_AtAttrExpr(pos, entity.compile(), attrRef)
    override fun toString() = "${entity.alias}.${attrRef.name}"
}

class C_ExprContextAttr_ColAtMember(
        private val ctx: C_ExprContext,
        private val memberValue: C_MemberValue,
        private val memberRef: C_MemberRef
): C_ExprContextAttr(memberValue.type()) {
    override fun compile(pos: S_Pos) = memberValue.compile(ctx, memberRef)
    override fun toString() = ".${memberRef.name}"
}

class C_ExprContext(
        val blkCtx: C_BlockContext,
        val nameCtx: C_NameContext,
        val factsCtx: C_VarFactsContext,
        val insideGuardBlock: Boolean = false
) {
    val defCtx = blkCtx.defCtx
    val nsCtx = defCtx.nsCtx
    val globalCtx = defCtx.globalCtx
    val appCtx = defCtx.appCtx
    val msgCtx = nsCtx.msgCtx
    val executor = defCtx.executor
    val nsValueCtx = C_NamespaceValueContext(defCtx)

    fun update(
            blkCtx: C_BlockContext? = null,
            nameCtx: C_NameContext? = null,
            factsCtx: C_VarFactsContext? = null,
            insideGuardBlock: Boolean? = null
    ): C_ExprContext {
        val blkCtx2 = blkCtx ?: this.blkCtx
        val nameCtx2 = nameCtx ?: this.nameCtx
        val factsCtx2 = factsCtx ?: this.factsCtx
        val insideGuardBlock2 = this.insideGuardBlock || (insideGuardBlock ?: false)
        return if (
                blkCtx2 == this.blkCtx
                && nameCtx2 == this.nameCtx
                && factsCtx2 == this.factsCtx
                && insideGuardBlock2 == this.insideGuardBlock
        ) this else C_ExprContext(blkCtx2, nameCtx2, factsCtx2, insideGuardBlock2)
    }

    fun updateFacts(facts: C_VarFactsAccess): C_ExprContext {
        if (facts.isEmpty()) return this
        return update(factsCtx = this.factsCtx.sub(facts))
    }

    fun checkDbUpdateAllowed(pos: S_Pos) {
        defCtx.checkDbUpdateAllowed(pos)
        if (insideGuardBlock) {
            msgCtx.error(pos, "no_db_update:guard", "Database modifications are not allowed inside or before a guard block")
        }
    }
}

class C_StmtContext private constructor(
        val blkCtx: C_BlockContext,
        val exprCtx: C_ExprContext,
        val loop: C_LoopUid?,
        val afterGuardBlock: Boolean = false,
        val topLevel: Boolean = false
) {
    val fnCtx = blkCtx.fnCtx
    val defCtx = fnCtx.defCtx
    val nsCtx = defCtx.nsCtx
    val msgCtx = nsCtx.msgCtx
    val executor = defCtx.executor

    fun update(
            blkCtx: C_BlockContext? = null,
            exprCtx: C_ExprContext? = null,
            loop: C_LoopUid? = null,
            afterGuardBlock: Boolean? = null,
            topLevel: Boolean? = null
    ): C_StmtContext {
        val blkCtx2 = blkCtx ?: this.blkCtx
        val exprCtx2 = exprCtx ?: this.exprCtx
        val loop2 = loop ?: this.loop
        val afterGuardBlock2 = afterGuardBlock ?: this.afterGuardBlock
        val topLevel2 = topLevel ?: this.topLevel
        return if (blkCtx2 == this.blkCtx
                && exprCtx2 == this.exprCtx
                && loop2 == this.loop
                && afterGuardBlock2 == this.afterGuardBlock
                && topLevel2 == this.topLevel
        ) this else C_StmtContext(
                blkCtx = blkCtx2,
                exprCtx = exprCtx2,
                loop = loop2,
                afterGuardBlock = afterGuardBlock2,
                topLevel = topLevel2
        )
    }

    fun updateFacts(facts: C_VarFactsAccess): C_StmtContext {
        return update(exprCtx = exprCtx.updateFacts(facts))
    }

    fun subBlock(loop: C_LoopUid?): Pair<C_StmtContext, C_OwnerBlockContext> {
        val subBlkCtx = blkCtx.createSubContext("blk")
        val subExprCtx = exprCtx.update(blkCtx = subBlkCtx, nameCtx = subBlkCtx.nameCtx)
        val subCtx = update(blkCtx = subBlkCtx, exprCtx = subExprCtx, loop = loop, topLevel = subBlkCtx.isTopLevelBlock())
        return Pair(subCtx, subBlkCtx)
    }

    fun checkDbUpdateAllowed(pos: S_Pos) {
        exprCtx.checkDbUpdateAllowed(pos)
    }

    companion object {
        fun createRoot(blkCtx: C_BlockContext): C_StmtContext {
            val exprCtx = C_ExprContext(blkCtx, blkCtx.nameCtx, C_VarFactsContext.EMPTY)
            return C_StmtContext(blkCtx, exprCtx, loop = null, topLevel = true)
        }
    }
}

sealed class C_NameContext {
    abstract fun hasPlaceholder(): Boolean
    abstract fun resolvePlaceholder(pos: S_Pos): V_Expr
    abstract fun resolveNameLocalValue(name: String): C_LocalVar?

    protected abstract fun findDefinitionByAlias(alias: S_Name): C_NameResolution?
    abstract fun findAttributesByName(name: S_Name): List<C_ExprContextAttr>
    abstract fun findAttributesByType(type: R_Type): List<C_ExprContextAttr>

    fun resolveAttr(name: S_Name): V_Expr {
        val attrs = findAttributesByName(name)

        if (attrs.isEmpty()) {
            throw C_Errors.errUnknownAttr(name)
        } else if (attrs.size > 1) {
            val nameStr = name.str
            throw C_Errors.errMultipleAttrs(name.pos, attrs, "at_attr_name_ambig:$nameStr",
                    "Multiple attributes with name '$nameStr'")
        }

        val attr = attrs[0]
        return attr.compile(name.pos)
    }

    fun resolveNameValue(ctx: C_ExprContext, name: S_Name): C_NameResolution? {
        val nameStr = name.str

        val entity = findDefinitionByAlias(name)
        val loc = resolveNameLocalValue(nameStr)
        val glob = ctx.nsCtx.getValueOpt(name)

        if (entity != null && loc != null) {
            throw C_Errors.errNameConflictAliasLocal(name)
        }

        if (entity != null) return entity
        if (loc != null) return C_NameResolution_Local(name, ctx, loc)
        if (glob != null) return C_NameResolution_Value(name, ctx.nsValueCtx, glob)

        return null
    }

    fun resolveName(ctx: C_ExprContext, name: S_Name): C_Expr {
        val value = resolveNameValue(ctx, name)
        val valueExpr = value?.toExpr()
        val fn = ctx.nsCtx.getFunctionOpt(listOf(name))
        val fnExpr = if (fn == null) null else C_FunctionExpr(name, fn)
        val expr = C_ValueFunctionExpr.create(name, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name)
    }

    companion object {
        fun createBlock(blkCtx: C_BlockContext): C_NameContext {
            return C_BlockNameContext(blkCtx)
        }

        fun createAt(blkCtx: C_BlockContext, from: C_AtFrom): C_NameContext {
            return C_AtNameContext(blkCtx, from)
        }
    }
}

private class C_BlockNameContext(private val blkCtx: C_BlockContext): C_NameContext() {
    override fun resolveNameLocalValue(name: String) = blkCtx.lookupLocalVar(name)
    override fun hasPlaceholder() = false
    override fun resolvePlaceholder(pos: S_Pos) = throw C_Error.stop(pos, "expr:dollar:no_at", "Not in at-expression")
    override fun findDefinitionByAlias(alias: S_Name) = null
    override fun findAttributesByName(name: S_Name) = listOf<C_ExprContextAttr>()
    override fun findAttributesByType(type: R_Type) = listOf<C_ExprContextAttr>()
}

private class C_AtNameContext(private val blkCtx: C_BlockContext, private val from: C_AtFrom): C_NameContext() {
    override fun resolveNameLocalValue(name: String) = blkCtx.lookupLocalVar(name)
    override fun hasPlaceholder() = from.hasPlaceholder()
    override fun resolvePlaceholder(pos: S_Pos) = from.resolvePlaceholder(pos)
    override fun findDefinitionByAlias(alias: S_Name) = from.findDefinitionByAlias(alias)
    override fun findAttributesByName(name: S_Name) = from.findAttributesByName(name)
    override fun findAttributesByType(type: R_Type) = from.findAttributesByType(type)
}

sealed class C_NameResolution(val name: S_Name) {
    abstract fun toExpr(): C_Expr
}

class C_NameResolution_Entity(name: S_Name, private val entity: C_AtEntity): C_NameResolution(name) {
    override fun toExpr(): C_Expr {
        val vExpr = createVExpr(entity, name.pos)
        return C_VExpr(vExpr)
    }

    companion object {
        fun createVExpr(entity: C_AtEntity, pos: S_Pos): V_Expr {
            val rAtEntity = entity.compile()
            return V_AtEntityExpr(pos, rAtEntity)
        }
    }
}

class C_NameResolution_Local(
        name: S_Name,
        private val ctx: C_ExprContext,
        private val localVar: C_LocalVar
): C_NameResolution(name) {
    override fun toExpr(): C_Expr {
        val nulled = ctx.factsCtx.nulled(localVar.uid)
        val smartType = if (localVar.type is R_NullableType && nulled == C_VarFact.NO) localVar.type.valueType else null
        val vExpr = V_LocalVarExpr(ctx, name, localVar, nulled, smartType)
        return C_VExpr(vExpr)
    }
}

private class C_NameResolution_Value(
        name: S_Name,
        private val ctx: C_NamespaceValueContext,
        private val value: C_DefRef<C_NamespaceValue>
): C_NameResolution(name) {
    override fun toExpr(): C_Expr = C_NamespaceValueExpr(ctx, listOf(name), value)
}

class C_AssignOp(val pos: S_Pos, val code: String, val rOp: R_BinaryOp, val dbOp: Db_BinaryOp?)

abstract class C_Destination {
    abstract fun type(): R_Type
    open fun effectiveType(): R_Type = type()
    open fun resultType(srcType: R_Type): R_Type = srcType
    abstract fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement
    abstract fun compileAssignExpr(startPos: S_Pos, resType: R_Type, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr
}

class C_SimpleDestination(private val rDstExpr: R_DestinationExpr): C_Destination() {
    override fun type() = rDstExpr.type

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        return R_AssignStatement(rDstExpr, srcExpr, op?.rOp)
    }

    override fun compileAssignExpr(startPos: S_Pos, resType: R_Type, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
        return R_AssignExpr(resType, op.rOp, rDstExpr, srcExpr, post)
    }
}

class C_EntityAttrDestination(
        private val msgCtx: C_MessageContext,
        private val base: V_Expr,
        private val rEntity: R_Entity,
        private val attr: R_Attribute
): C_Destination() {
    override fun type() = attr.type
    override fun resultType(srcType: R_Type) = R_UnitType

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        if (op != null && op.dbOp == null) {
            C_BinOp.errTypeMismatch(msgCtx, op.pos, op.code, attr.type, srcExpr.type)
            return C_Utils.ERROR_STATEMENT
        }

        val atEntity = R_DbAtEntity(rEntity, 0)
        val whereLeft = Db_EntityExpr(atEntity)
        val whereRight = Db_ParameterExpr(atEntity.rEntity.type, 0)
        val where = C_Utils.makeDbBinaryExprEq(whereLeft, whereRight)

        val rBase = base.toRExpr()
        val rTarget = R_UpdateTarget_Expr_One(atEntity, where, rBase)
        val dbExpr = Db_InterpretedExpr(srcExpr)
        val rWhat = R_UpdateStatementWhat(attr, dbExpr, op?.dbOp)
        return R_UpdateStatement(rTarget, listOf(rWhat))
    }

    override fun compileAssignExpr(startPos: S_Pos, resType: R_Type, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
        val rStmt = compileAssignStatement(srcExpr, op)
        return R_StatementExpr(rStmt)
    }
}

class C_ObjectAttrDestination(
        private val msgCtx: C_MessageContext,
        private val rObject: R_Object,
        private val attr: R_Attribute
): C_Destination() {
    override fun type() = attr.type
    override fun resultType(srcType: R_Type) = R_UnitType

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        if (op != null && op.dbOp == null) {
            C_BinOp.errTypeMismatch(msgCtx, op.pos, op.code, attr.type, srcExpr.type)
            return C_Utils.ERROR_STATEMENT
        }

        val rTarget = R_UpdateTarget_Object(rObject)
        val dbExpr = Db_InterpretedExpr(srcExpr)
        val rWhat = R_UpdateStatementWhat(attr, dbExpr, op?.dbOp)
        return R_UpdateStatement(rTarget, listOf(rWhat))
    }

    override fun compileAssignExpr(startPos: S_Pos, resType: R_Type, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
        val rStmt = compileAssignStatement(srcExpr, op)
        return R_StatementExpr(rStmt)
    }
}

enum class C_ExprKind(val code: String) {
    VALUE("expression"),
    NAMESPACE("namespace"),
    TYPE("type"),
    STRUCT("struct"),
    OBJECT("object"),
    ENUM("enum"),
    FUNCTION("function")
}

abstract class C_Expr {
    abstract fun kind(): C_ExprKind
    abstract fun startPos(): S_Pos
    open fun value(): V_Expr = throw errNoValue()

    open fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        throw errNoValue()
    }

    open fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        args.forEach { it.expr.compileSafe(ctx) }

        val type = value().type() // May fail with "not a value" - that's OK.
        if (type == R_CtErrorType) {
            return C_Utils.errorExpr(pos)
        } else {
            val typeStr = type.toStrictString()
            throw C_Error.stop(pos, "expr_call_nofn:$typeStr", "Not a function: value of type $typeStr")
        }
    }

    private fun errNoValue(): C_Error {
        val pos = startPos()
        val kind = kind().code
        return C_Error.stop(pos, "expr_novalue:$kind", "Expression has no value: $kind")
    }
}

class C_VExpr(private val vExpr: V_Expr): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = vExpr.pos
    override fun value() = vExpr

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        return vExpr.member(ctx, memberName, safe)
    }
}

class C_NamespaceExpr(private val name: List<S_Name>, private val nsRef: C_NamespaceRef): C_Expr() {
    override fun kind() = C_ExprKind.NAMESPACE
    override fun startPos() = name[0].pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val valueExpr = memberValue(ctx.nsValueCtx, memberName)

        val fnRef = nsRef.function(memberName)
        val fnExpr = if (fnRef != null) C_FunctionExpr(memberName, fnRef) else null

        val expr = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name, memberName)
    }

    private fun memberValue(ctx: C_NamespaceValueContext, memberName: S_Name): C_Expr? {
        val valueRef = nsRef.value(memberName)
        val valueExpr = valueRef?.getDef()?.toExpr(ctx, name + listOf(memberName))
        if (valueExpr != null) return valueExpr

        val typeRef = nsRef.type(memberName)
        return if (typeRef == null) null else C_TypeNameExpr(memberName.pos, typeRef)
    }
}

class C_StructExpr(
        private val name: List<S_Name>,
        private val struct: R_Struct,
        private val nsRef: C_NamespaceRef
): C_Expr() {
    override fun kind() = C_ExprKind.STRUCT
    override fun startPos() = name[0].pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val nsExpr = C_NamespaceExpr(name, nsRef)
        return nsExpr.member(ctx, memberName, safe)
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        return C_StructGlobalFunction.compileCall(struct, ctx, name.last(), args)
    }
}

class C_ObjectExpr(name: List<S_Name>, rObject: R_Object): C_Expr() {
    private val vExpr = V_ObjectExpr(name, rObject)

    override fun kind() = C_ExprKind.OBJECT
    override fun startPos() = vExpr.pos
    override fun value(): V_Expr = vExpr

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        return vExpr.member(ctx, memberName, safe)
    }
}

class C_EnumExpr(private val msgCtx: C_MessageContext, private val name: List<S_Name>, private val rEnum: R_Enum): C_Expr() {
    override fun kind() = C_ExprKind.ENUM
    override fun startPos() = name[0].pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val valueExpr = memberValue(memberName)
        val fnExpr = memberFn(memberName)
        val expr = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name, memberName)
    }

    private fun memberValue(memberName: S_Name): C_Expr? {
        val attr = rEnum.attr(memberName.str)
        if (attr == null) {
            return null
        }

        val rValue = Rt_EnumValue(rEnum.type, attr)
        val rExpr = R_ConstantExpr(rValue)
        return V_RExpr.makeExpr(startPos(), rExpr)
    }

    private fun memberFn(memberName: S_Name): C_Expr? {
        val fn = C_LibFunctions.getTypeStaticFunction(rEnum.type, memberName.str)
        return if (fn == null) null else {
            val fnRef = C_DefRef(msgCtx, name + memberName, C_DefProxy.create(fn))
            C_FunctionExpr(memberName, fnRef)
        }
    }
}

class C_FunctionExpr(private val name: S_Name, private val fnRef: C_DefRef<C_GlobalFunction>): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = name.pos

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        val fn = fnRef.getDef()
        return fn.compileCall(ctx, name, args)
    }
}

class C_TypeNameExpr(private val pos: S_Pos, private val typeRef: C_DefRef<R_Type>): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val type = typeRef.getDef()
        val fn = C_LibFunctions.getTypeStaticFunction(type, memberName.str)
        if (fn == null) throw C_Errors.errUnknownName(type, memberName)
        val fnRef = typeRef.sub(memberName, C_DefProxy.create(fn))
        return C_FunctionExpr(memberName, fnRef)
    }
}

class C_TypeExpr(private val pos: S_Pos, private val type: R_Type): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val fn = C_LibFunctions.getTypeStaticFunction(type, memberName.str)
        if (fn == null) throw C_Errors.errUnknownName(type, memberName)
        val fnRef = C_DefRef(ctx.msgCtx, listOf(memberName), C_DefProxy.create(fn))
        return C_FunctionExpr(memberName, fnRef)
    }
}

class C_ValueFunctionExpr private constructor(
        private val name: S_Name,
        private val valueExpr: C_Expr,
        private val fnExpr: C_Expr
): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = name.pos
    override fun value() = valueExpr.value()
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = valueExpr.member(ctx, memberName, safe)
    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>) = fnExpr.call(ctx, pos, args)

    companion object {
        fun create(name: S_Name, valueExpr: C_Expr?, fnExpr: C_Expr?): C_Expr? {
            if (valueExpr != null && fnExpr != null) {
                return C_ValueFunctionExpr(name, valueExpr, fnExpr)
            } else if (valueExpr != null) {
                return valueExpr
            } else {
                return fnExpr
            }
        }
    }
}

class C_NamespaceValueExpr(
        private val ctx: C_NamespaceValueContext,
        private val name: List<S_Name>,
        private val value: C_DefRef<C_NamespaceValue>
): C_Expr() {
    private fun expr(): C_Expr = value.getDef().toExpr(ctx, name)
    override fun kind() = expr().kind()
    override fun startPos() = name.last().pos
    override fun value() = expr().value()
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = expr().member(ctx, memberName, safe)
    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>) = expr().call(ctx, pos, args)
}
