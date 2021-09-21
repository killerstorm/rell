/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.def.C_StructGlobalFunction
import net.postchain.rell.compiler.base.namespace.*
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.vexpr.V_ConstantValueExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_ObjectExpr
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.*
import net.postchain.rell.model.stmt.*
import net.postchain.rell.runtime.Rt_EnumValue

class C_ExprContext private constructor(
        val blkCtx: C_BlockContext,
        val factsCtx: C_VarFactsContext,
        val atCtx: C_AtContext?,
        val insideGuardBlock: Boolean
) {
    val defCtx = blkCtx.defCtx
    val modCtx = defCtx.modCtx
    val nsCtx = defCtx.nsCtx
    val globalCtx = defCtx.globalCtx
    val appCtx = defCtx.appCtx
    val msgCtx = nsCtx.msgCtx
    val executor = defCtx.executor
    val nsValueCtx = C_NamespaceValueContext(this)

    fun makeAtEntity(rEntity: R_EntityDefinition, atExprId: R_AtExprId) = R_DbAtEntity(rEntity, appCtx.nextAtEntityId(atExprId))

    fun update(
            blkCtx: C_BlockContext = this.blkCtx,
            factsCtx: C_VarFactsContext = this.factsCtx,
            atCtx: C_AtContext? = this.atCtx,
            insideGuardBlock: Boolean = this.insideGuardBlock
    ): C_ExprContext {
        val insideGuardBlock2 = insideGuardBlock || this.insideGuardBlock
        return if (
                blkCtx === this.blkCtx
                && factsCtx === this.factsCtx
                && atCtx === this.atCtx
                && insideGuardBlock2 == this.insideGuardBlock
        ) this else C_ExprContext(
                blkCtx = blkCtx,
                factsCtx = factsCtx,
                atCtx = atCtx,
                insideGuardBlock = insideGuardBlock2
        )
    }

    fun updateFacts(facts: C_VarFactsAccess): C_ExprContext {
        if (facts.isEmpty()) return this
        return update(factsCtx = this.factsCtx.sub(facts))
    }

    fun getDbModificationRestriction(): C_CodeMsg? {
        val r = defCtx.getDbModificationRestriction()
        return r ?: if (insideGuardBlock) {
            C_CodeMsg("no_db_update:guard", "Database modifications are not allowed inside or before a guard block")
        } else {
            null
        }
    }

    fun checkDbUpdateAllowed(pos: S_Pos) {
        val r = getDbModificationRestriction()
        if (r != null) {
            msgCtx.error(pos, r.code, r.msg)
        }
    }

    fun findWhereAttributesByName(name: String) = blkCtx.lookupAtAttributesByName(name, false)
    fun findWhereAttributesByType(type: R_Type) = blkCtx.lookupAtAttributesByType(type)

    fun resolveAttr(name: S_Name): V_Expr {
        val attrs = blkCtx.lookupAtAttributesByName(name.str, true)

        if (attrs.isEmpty()) {
            throw C_Errors.errUnknownAttr(name)
        } else if (attrs.size > 1) {
            val nameStr = name.str
            throw C_Errors.errMultipleAttrs(name.pos, attrs, "at_attr_name_ambig:$nameStr",
                    "Multiple attributes with name '$nameStr'")
        }

        val attr = attrs[0]
        return attr.compile(this, name.pos)
    }

    companion object {
        fun createRoot(blkCtx: C_BlockContext) = C_ExprContext(
                blkCtx = blkCtx,
                factsCtx = C_VarFactsContext.EMPTY,
                insideGuardBlock = false,
                atCtx = null
        )
    }
}

class C_StmtContext private constructor(
        val blkCtx: C_BlockContext,
        val exprCtx: C_ExprContext,
        val loop: C_LoopUid?,
        val afterGuardBlock: Boolean = false,
        val topLevel: Boolean = false
) {
    val appCtx = blkCtx.appCtx
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
        val subExprCtx = exprCtx.update(blkCtx = subBlkCtx)
        val subCtx = update(blkCtx = subBlkCtx, exprCtx = subExprCtx, loop = loop, topLevel = subBlkCtx.isTopLevelBlock())
        return Pair(subCtx, subBlkCtx)
    }

    fun checkDbUpdateAllowed(pos: S_Pos) {
        exprCtx.checkDbUpdateAllowed(pos)
    }

    companion object {
        fun createRoot(blkCtx: C_BlockContext): C_StmtContext {
            val exprCtx = C_ExprContext.createRoot(blkCtx)
            return C_StmtContext(blkCtx, exprCtx, loop = null, topLevel = true)
        }
    }
}

class C_AssignOp(val pos: S_Pos, val code: String, val rOp: R_BinaryOp, val dbOp: Db_BinaryOp?)

abstract class C_Destination {
    abstract fun type(): R_Type
    open fun effectiveType(): R_Type = type()
    open fun resultType(srcType: R_Type): R_Type = srcType

    abstract fun compileAssignStatement(ctx: C_ExprContext, srcExpr: R_Expr, op: C_AssignOp?): R_Statement

    abstract fun compileAssignExpr(
            ctx: C_ExprContext,
            startPos: S_Pos,
            resType: R_Type,
            srcExpr: R_Expr,
            op: C_AssignOp,
            post: Boolean
    ): R_Expr
}

class C_SimpleDestination(private val rDstExpr: R_DestinationExpr): C_Destination() {
    override fun type() = rDstExpr.type

    override fun compileAssignStatement(ctx: C_ExprContext, srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        return R_AssignStatement(rDstExpr, srcExpr, op?.rOp)
    }

    override fun compileAssignExpr(
            ctx: C_ExprContext,
            startPos: S_Pos,
            resType: R_Type,
            srcExpr: R_Expr,
            op: C_AssignOp,
            post: Boolean
    ): R_Expr {
        return R_AssignExpr(resType, op.rOp, rDstExpr, srcExpr, post)
    }
}

class C_EntityAttrDestination(
        private val base: V_Expr,
        private val rEntity: R_EntityDefinition,
        private val attr: R_Attribute
): C_Destination() {
    override fun type() = attr.type
    override fun resultType(srcType: R_Type) = R_UnitType

    override fun compileAssignStatement(ctx: C_ExprContext, srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        if (op != null && op.dbOp == null) {
            C_BinOp.errTypeMismatch(ctx.msgCtx, op.pos, op.code, attr.type, srcExpr.type)
            return C_Utils.ERROR_STATEMENT
        }

        val metaName = "${rEntity.appLevelName}.${attr.name}="

        val lambdaBlkCtx = ctx.blkCtx.createSubContext("<$metaName:lambda>")
        val lambdaCtx = ctx.update(blkCtx = lambdaBlkCtx)
        val baseVar = lambdaBlkCtx.newLocalVar("<$metaName:base>", base.type, false, null)
        val srcVar = lambdaBlkCtx.newLocalVar("<$metaName:src>", srcExpr.type, false, null)

        val cLambdaB = C_LambdaBlock.builder(lambdaCtx, rEntity.type)
        val fromBlkCtx = cLambdaB.innerBlkCtx.createSubContext("<$metaName:from>")
        val rFromBlock = fromBlkCtx.buildBlock().rBlock
        val cLambda = cLambdaB.build()

        val atEntity = ctx.makeAtEntity(rEntity, ctx.appCtx.nextAtExprId())
        val whereLeft = Db_EntityExpr(atEntity)
        val whereRight = cLambda.compileVarDbExpr(rFromBlock.uid)
        val where = C_Utils.makeDbBinaryExprEq(whereLeft, whereRight)

        val rBaseVarExpr = baseVar.toRef(lambdaBlkCtx.blockUid).toRExpr()
        val rTarget = R_UpdateTarget_Expr_One(atEntity, where, rBaseVarExpr, cLambda.rLambda)
        val dbSrcVarExpr = srcVar.toRef(rFromBlock.uid).toDbExpr()
        val rWhat = R_UpdateStatementWhat(attr, dbSrcVarExpr, op?.dbOp)
        val rUpdateStmt = R_UpdateStatement(rTarget, rFromBlock, listOf(rWhat))

        val rBaseExpr = base.toRExpr()
        val lambdaArgs = listOf(
                rBaseExpr to baseVar.toRef(lambdaBlkCtx.blockUid).ptr,
                srcExpr to srcVar.toRef(lambdaBlkCtx.blockUid).ptr
        )

        val rSubBlock = lambdaBlkCtx.buildBlock().rBlock
        return R_LambdaStatement(lambdaArgs, rSubBlock, rUpdateStmt)
    }

    override fun compileAssignExpr(
            ctx: C_ExprContext,
            startPos: S_Pos,
            resType: R_Type,
            srcExpr: R_Expr,
            op: C_AssignOp,
            post: Boolean
    ): R_Expr {
        val rStmt = compileAssignStatement(ctx, srcExpr, op)
        return R_StatementExpr(rStmt)
    }
}

class C_ObjectAttrDestination(
        private val rObject: R_ObjectDefinition,
        private val attr: R_Attribute
): C_Destination() {
    override fun type() = attr.type
    override fun resultType(srcType: R_Type) = R_UnitType

    override fun compileAssignStatement(ctx: C_ExprContext, srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        if (op != null && op.dbOp == null) {
            C_BinOp.errTypeMismatch(ctx.msgCtx, op.pos, op.code, attr.type, srcExpr.type)
            return C_Utils.ERROR_STATEMENT
        }

        val metaName = "${rObject.appLevelName}.${attr.name}="

        val lambdaBlkCtx = ctx.blkCtx.createSubContext("<$metaName:lambda>")
        val srcVar = lambdaBlkCtx.newLocalVar("<$metaName:src>", srcExpr.type, false, null)

        val fromBlkCtx = lambdaBlkCtx.createSubContext("<$metaName:from>")
        val rFromBlock = fromBlkCtx.buildBlock().rBlock
        val rLambdaBlock = lambdaBlkCtx.buildBlock().rBlock

        val rAtEntity = ctx.makeAtEntity(rObject.rEntity, ctx.appCtx.nextAtExprId())
        val rTarget = R_UpdateTarget_Object(rAtEntity)
        val dbSrcVarExpr = srcVar.toRef(rFromBlock.uid).toDbExpr()
        val rWhat = R_UpdateStatementWhat(attr, dbSrcVarExpr, op?.dbOp)
        val rUpdateStmt = R_UpdateStatement(rTarget, rFromBlock, listOf(rWhat))

        val lambdaArgs = listOf(
                srcExpr to srcVar.toRef(rLambdaBlock.uid).ptr
        )

        return R_LambdaStatement(lambdaArgs, rLambdaBlock, rUpdateStmt)
    }

    override fun compileAssignExpr(
            ctx: C_ExprContext,
            startPos: S_Pos,
            resType: R_Type,
            srcExpr: R_Expr,
            op: C_AssignOp,
            post: Boolean
    ): R_Expr {
        val rStmt = compileAssignStatement(ctx, srcExpr, op)
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
    open fun isCallable() = false

    open fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        throw errNoValue()
    }

    open fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val vExpr = value() // May fail with "not a value" - that's OK.
        val vResExpr = vExpr.call(ctx, pos, args, resTypeHint)
        return C_VExpr(vResExpr)
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
    override fun isCallable() = vExpr.type is R_FunctionType

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        return vExpr.member(ctx, memberName, safe)
    }
}

class C_NamespaceExpr(private val name: S_QualifiedName, private val nsRef: C_NamespaceRef): C_Expr() {
    override fun kind() = C_ExprKind.NAMESPACE
    override fun startPos() = name.pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val valueExpr = memberValue(ctx.nsValueCtx, memberName)

        val fnRef = nsRef.function(memberName)
        val fnExpr = if (fnRef != null) C_FunctionExpr(memberName, fnRef) else null

        val expr = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name, memberName)
    }

    private fun memberValue(ctx: C_NamespaceValueContext, memberName: S_Name): C_Expr? {
        val valueRef = nsRef.value(memberName)
        val valueExpr = valueRef?.getDef()?.toExpr(ctx, name.add(memberName))
        if (valueExpr != null) return valueExpr

        val typeRef = nsRef.type(memberName)
        return if (typeRef == null) null else C_TypeNameExpr(memberName.pos, typeRef)
    }
}

sealed class C_StructExpr(
        private val startPos: S_Pos,
        protected val struct: R_Struct
): C_Expr() {
    final override fun kind() = C_ExprKind.STRUCT
    final override fun startPos() = startPos
    final override fun isCallable() = true

    protected abstract val baseName: String

    protected abstract fun memberFunction(ctx: C_ExprContext, memberName: S_Name): C_Expr?

    final override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val fnExpr = memberFunction(ctx, memberName)
        return fnExpr ?: throw C_Errors.errUnknownName(startPos, "$baseName.$memberName")
    }

    final override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val vExpr = C_StructGlobalFunction.compileCall(ctx, struct, startPos, args)
        return C_VExpr(vExpr)
    }
}

class C_NamespaceStructExpr(
        name: S_QualifiedName,
        struct: R_Struct,
        private val nsRef: C_NamespaceRef
): C_StructExpr(name.pos, struct) {
    override val baseName = name.str()

    override fun memberFunction(ctx: C_ExprContext, memberName: S_Name): C_Expr? {
        val fnRef = nsRef.function(memberName)
        return if (fnRef != null) C_FunctionExpr(memberName, fnRef) else null
    }
}

class C_MirrorStructExpr(pos: S_Pos, struct: R_Struct, private val ns: C_Namespace): C_StructExpr(pos, struct) {
    override val baseName = struct.name

    override fun memberFunction(ctx: C_ExprContext, memberName: S_Name): C_Expr? {
        val fnProxy = ns.function(memberName.str)
        fnProxy ?: return null
        val fnRef = C_DefRef(ctx.msgCtx, S_QualifiedName(memberName), fnProxy)
        return C_FunctionExpr(memberName, fnRef)
    }
}

class C_ObjectExpr(exprCtx: C_ExprContext, name: S_QualifiedName, rObject: R_ObjectDefinition): C_Expr() {
    private val vExpr = V_ObjectExpr(exprCtx, name, rObject)

    override fun kind() = C_ExprKind.OBJECT
    override fun startPos() = vExpr.pos
    override fun value(): V_Expr = vExpr

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        return vExpr.member(ctx, memberName, safe)
    }
}

class C_EnumExpr(
        private val msgCtx: C_MessageContext,
        private val name: S_QualifiedName,
        private val rEnum: R_EnumDefinition
): C_Expr() {
    override fun kind() = C_ExprKind.ENUM
    override fun startPos() = name.pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val valueExpr = memberValue(ctx, memberName)
        val fnExpr = memberFn(ctx, memberName)
        val expr = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name, memberName)
    }

    private fun memberValue(ctx: C_ExprContext, memberName: S_Name): C_Expr? {
        val attr = rEnum.attr(memberName.str)
        if (attr == null) {
            return null
        }

        val rValue = Rt_EnumValue(rEnum.type, attr)
        val vExpr = V_ConstantValueExpr(ctx, startPos(), rValue)
        return C_VExpr(vExpr)
    }

    private fun memberFn(ctx: C_ExprContext, memberName: S_Name): C_Expr? {
        val fn = ctx.globalCtx.libFunctions.getTypeStaticFunction(rEnum.type, memberName.str)
        return if (fn == null) null else {
            val fnRef = C_DefRef(msgCtx, name.add(memberName), C_DefProxy.create(fn))
            C_FunctionExpr(memberName, fnRef)
        }
    }
}

class C_FunctionExpr(private val name: S_Name, private val fnRef: C_DefRef<C_GlobalFunction>): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = name.pos
    override fun isCallable() = true

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val fn = fnRef.getDef()
        val vExpr = fn.compileCall(ctx, name, args, resTypeHint)
        return C_VExpr(vExpr)
    }
}

class C_TypeNameExpr(private val pos: S_Pos, private val typeRef: C_DefRef<R_Type>): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val type = typeRef.getDef()
        val fn = ctx.globalCtx.libFunctions.getTypeStaticFunction(type, memberName.str)
        if (fn == null) throw C_Errors.errUnknownName(type, memberName)
        val fnRef = typeRef.sub(memberName, C_DefProxy.create(fn))
        return C_FunctionExpr(memberName, fnRef)
    }
}

class C_TypeExpr(private val pos: S_Pos, private val type: R_Type): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val fn = ctx.globalCtx.libFunctions.getTypeStaticFunction(type, memberName.str)
        if (fn == null) throw C_Errors.errUnknownName(type, memberName)
        val fnRef = C_DefRef(ctx.msgCtx, S_QualifiedName(memberName), C_DefProxy.create(fn))
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
    override fun isCallable() = true
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = valueExpr.member(ctx, memberName, safe)

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        return fnExpr.call(ctx, pos, args, resTypeHint)
    }

    companion object {
        fun create(name: S_Name, valueExpr: C_Expr?, fnExpr: C_Expr?): C_Expr? {
            if (valueExpr != null && (valueExpr.isCallable() || fnExpr == null)) {
                return valueExpr
            } else if (valueExpr != null && fnExpr != null) {
                return C_ValueFunctionExpr(name, valueExpr, fnExpr)
            } else {
                return fnExpr
            }
        }
    }
}

class C_NamespaceValueExpr(
        private val ctx: C_NamespaceValueContext,
        private val name: S_QualifiedName,
        private val value: C_DefRef<C_NamespaceValue>
): C_Expr() {
    private fun expr(): C_Expr = value.getDef().toExpr(ctx, name)
    override fun kind() = expr().kind()
    override fun startPos() = name.pos
    override fun value() = expr().value()
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = expr().member(ctx, memberName, safe)

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        return expr().call(ctx, pos, args, resTypeHint)
    }
}
