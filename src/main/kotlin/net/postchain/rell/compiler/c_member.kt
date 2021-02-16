/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_NameExprPair
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_VirtualType
import net.postchain.rell.compiler.vexpr.V_DbExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_SysMemberPropertyExpr
import net.postchain.rell.model.*

class C_MemberRef(val pos: S_Pos, val base: V_Expr, val name: S_Name, val safe: Boolean) {
    fun qualifiedName() = "${base.type().toStrictString()}.${name.str}"
}

sealed class C_MemberValue {
    abstract fun type(): R_Type
    abstract fun compile(ctx: C_ExprContext, ref: C_MemberRef): V_Expr
}

private class C_MemberValue_BasicAttr(private val attr: C_MemberAttr): C_MemberValue() {
    override fun type() = attr.type

    override fun compile(ctx: C_ExprContext, ref: C_MemberRef): V_Expr {
        return makeMemberExpr(ctx, ref, attr)
    }
}

private class C_MemberValue_EntityAttr(private val attr: C_EntityAttrRef): C_MemberValue() {
    override fun type() = attr.type()

    override fun compile(ctx: C_ExprContext, ref: C_MemberRef): V_Expr {
        return attr.createIpMemberExpr(ctx, ref)
    }
}

private class C_MemberValue_EnumAttr(private val prop: C_SysMemberFormalParamsFuncBody): C_MemberValue() {
    override fun type() = prop.resType

    override fun compile(ctx: C_ExprContext, ref: C_MemberRef): V_Expr {
        val caseCtx = C_MemberFuncCaseCtx(ref)
        return V_SysMemberPropertyExpr(ctx, caseCtx, prop)
    }
}

object C_MemberResolver {
    fun valueForType(ctx: C_ExprContext, type: R_Type, ref: C_MemberRef): C_Expr? {
        val member = findMemberValueForType(type, ref.name.str)
        member ?: return null
        val vExpr = member.compile(ctx, ref)
        return C_VExpr(vExpr)
    }

    fun findMemberValueForType(type: R_Type, name: String): C_MemberValue? {
        return when (type) {
            is R_TupleType -> valueForTuple(type, name)
            is R_VirtualTupleType -> valueForVirtualTuple(type, name)
            is R_StructType -> valueForStruct(type, name)
            is R_VirtualStructType -> valueForVirtualStruct(type, name)
            is R_EntityType -> valueForEntity(type, name)
            is R_EnumType -> valueForEnum(name)
            else -> null
        }
    }

    private fun valueForTuple(type: R_TupleType, name: String): C_MemberValue? {
        val idx = type.fields.indexOfFirst { it.name == name }
        if (idx == -1) {
            return null
        }

        val memAttr = C_MemberAttr_TupleAttr(type.fields[idx].type, idx)
        return C_MemberValue_BasicAttr(memAttr)
    }

    private fun valueForVirtualTuple(type: R_VirtualTupleType, name: String): C_MemberValue? {
        val tupleType = type.innerType
        val idx = tupleType.fields.indexOfFirst { it.name == name }
        if (idx == -1) {
            return null
        }

        val field = tupleType.fields[idx]
        val virtualType = S_VirtualType.virtualMemberType(field.type)
        val memAttr = C_MemberAttr_VirtualTupleAttr(virtualType, idx)
        return C_MemberValue_BasicAttr(memAttr)
    }

    private fun valueForStruct(type: R_StructType, name: String): C_MemberValue? {
        val attr = type.struct.attributes[name]
        if (attr == null) {
            return null
        }

        val memAttr = C_MemberAttr_StructAttr(attr)
        return C_MemberValue_BasicAttr(memAttr)
    }

    private fun valueForVirtualStruct(type: R_VirtualStructType, name: String): C_MemberValue? {
        val attr = type.innerType.struct.attributes[name]
        if (attr == null) {
            return null
        }

        val virtualType = S_VirtualType.virtualMemberType(attr.type)
        val memAttr = C_MemberAttr_VirtualStructAttr(virtualType, attr)
        return C_MemberValue_BasicAttr(memAttr)
    }

    private fun valueForEntity(type: R_EntityType, name: String): C_MemberValue? {
        val attrRef = C_EntityAttrRef.resolveByName(type.rEntity, name)
        return if (attrRef == null) null else C_MemberValue_EntityAttr(attrRef)
    }

    private fun valueForEnum(name: String): C_MemberValue? {
        val prop = C_LibFunctions.getEnumPropertyOpt(name)
        return if (prop == null) null else C_MemberValue_EnumAttr(prop)
    }

    fun functionForType(type: R_Type, ref: C_MemberRef): C_Expr? {
        val fn = C_LibFunctions.getMemberFunctionOpt(type, ref.name.str)
        return if (fn == null) null else C_MemberFunctionExpr(ref, fn)
    }

    fun checkNullAccess(type: R_Type, name: S_Name, safe: Boolean) {
        if (!safe && type is R_NullableType) {
            throw C_Error.stop(name.pos, "expr_mem_null:${name.str}", "Cannot access member '${name.str}' of nullable value")
        }
    }
}

private sealed class C_MemberAttr(val type: R_Type) {
    abstract fun calculator(): R_MemberCalculator
    abstract fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr
}

private class C_MemberAttr_TupleAttr(type: R_Type, private val fieldIndex: Int): C_MemberAttr(type) {
    override fun calculator() = R_MemberCalculator_TupleAttr(type, fieldIndex)
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(pos)
}

private class C_MemberAttr_VirtualTupleAttr(type: R_Type, private val fieldIndex: Int): C_MemberAttr(type) {
    override fun calculator() = R_MemberCalculator_VirtualTupleAttr(type, fieldIndex)
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(pos)
}

private class C_MemberAttr_StructAttr(private val attr: R_Attribute): C_MemberAttr(attr.type) {
    override fun calculator() = R_MemberCalculator_StructAttr(attr)

    override fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        return R_StructMemberExpr(base, attr)
    }
}

private class C_MemberAttr_VirtualStructAttr(type: R_Type, private val attr: R_Attribute): C_MemberAttr(type) {
    override fun calculator() = R_MemberCalculator_VirtualStructAttr(type, attr)
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errAttrNotMutable(pos, attr.name)
}

private class C_MemberAttr_SimpleReadOnly(
        private val name: S_Name,
        private val calculator: R_MemberCalculator
): C_MemberAttr(calculator.type) {
    override fun calculator() = calculator
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(name)
}

private class V_MemberAttrExpr(
        exprCtx: C_ExprContext,
        private val memberRef: C_MemberRef,
        private val memAttr: C_MemberAttr,
        private val type: R_Type,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, memberRef.pos) {
    private val isDb = isDb(memberRef.base)

    override fun type() = type
    override fun isDb() = isDb

    override fun toRExpr0(): R_Expr {
        val rBase = memberRef.base.toRExpr()
        val calculator = memAttr.calculator()
        return R_MemberExpr(rBase, memberRef.safe, calculator)
    }

    override fun toDbExpr0(): Db_Expr {
        val rExpr = toRExpr()
        return C_Utils.toDbExpr(memberRef.name.pos, rExpr)
    }

    override fun varFacts() = varFacts

    override fun destination(): C_Destination {
        val rBase = memberRef.base.toRExpr()
        val rDstExpr = memAttr.destination(memberRef.name.pos, rBase)
        return C_SimpleDestination(rDstExpr)
    }

    override fun implicitWhatName(): String? {
        val isAt = memberRef.base.isAtExprItem()
        return if (isAt) memberRef.name.str else null
    }
}

sealed class C_EntityAttrRef(protected val rEntity: R_EntityDefinition, val name: String) {
    abstract fun type(): R_Type
    abstract fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr
    abstract fun createIpMemberExpr(ctx: C_ExprContext, ref: C_MemberRef): V_Expr
    abstract fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr, pos: S_Pos, sName: S_Name): C_Expr
    abstract fun createIpEntityMemberExpr(ctx: C_ExprContext, baseValue: C_EntityAttrValueLike, baseExpr: Db_TableExpr, ref: C_MemberRef): C_Expr

    companion object {
        const val ROWID_NAME = "rowid"
        val ROWID_TYPE: R_Type = R_RowidType

        fun isAllowedRegularAttrName(name: String) = name != ROWID_NAME

        fun create(rEntity: R_EntityDefinition, attr: R_Attribute): C_EntityAttrRef {
            return C_EntityAttrRef_Regular(rEntity, attr)
        }

        fun resolveByName(rEntity: R_EntityDefinition, name: String): C_EntityAttrRef? {
            return if (name == ROWID_NAME) {
                C_EntityAttrRef_Rowid(rEntity)
            } else {
                val attr = rEntity.attributes[name]
                if (attr == null) null else C_EntityAttrRef_Regular(rEntity, attr)
            }
        }

        fun resolveByType(rEntity: R_EntityDefinition, type: R_Type): List<C_EntityAttrRef> {
            val res = mutableListOf<C_EntityAttrRef>()
            if (type == ROWID_TYPE) {
                res.add(C_EntityAttrRef_Rowid(rEntity))
            }
            for (attr in rEntity.attributes.values) {
                if (attr.type == type) {
                    res.add(C_EntityAttrRef_Regular(rEntity, attr))
                }
            }
            return res
        }
    }
}

private class C_EntityAttrRef_Regular(rEntity: R_EntityDefinition, private val attr: R_Attribute): C_EntityAttrRef(rEntity, attr.name) {
    override fun type() = attr.type

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return makeDbAttrExpr(baseExpr, attr)
    }

    override fun createIpMemberExpr(ctx: C_ExprContext, ref: C_MemberRef): V_Expr {
        val atEntity = ctx.makeAtEntity(rEntity, ctx.appCtx.nextAtExprId())
        val baseDbExpr = Db_EntityExpr(atEntity)
        val attrInfo = createAttrInfo(baseDbExpr, ref.name)
        return V_EntityAttrExpr.createIpMemberExpr(ctx, ref, atEntity, attrInfo)
    }

    override fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr, pos: S_Pos, sName: S_Name): C_Expr {
        val dbExpr = makeDbAttrExpr(base, attr)
        return V_DbExpr.createExpr(ctx, pos, dbExpr)
    }

    override fun createIpEntityMemberExpr(ctx: C_ExprContext, baseValue: C_EntityAttrValueLike, baseExpr: Db_TableExpr, ref: C_MemberRef): C_Expr {
        val attrInfo = createAttrInfo(baseExpr, ref.name)
        return baseValue.memberAttr(ctx, ref, attrInfo)
    }

    private fun createAttrInfo(baseExpr: Db_TableExpr, sName: S_Name): C_DbAttrInfo {
        val dbExpr = makeDbAttrExpr(baseExpr, attr)
        return C_DbAttrInfo(rEntity, sName, attr, dbExpr)
    }
}

private class C_EntityAttrRef_Rowid(rEntity: R_EntityDefinition): C_EntityAttrRef(rEntity, ROWID_NAME) {
    override fun type() = ROWID_TYPE

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return Db_RowidExpr(baseExpr)
    }

    override fun createIpMemberExpr(ctx: C_ExprContext, ref: C_MemberRef): V_Expr {
        val field = C_MemberAttr_SimpleReadOnly(ref.name, R_MemberCalculator_Rowid)
        return makeMemberExpr(ctx, ref, field)
    }

    override fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr, pos: S_Pos, sName: S_Name): C_Expr {
        val dbExpr = Db_RowidExpr(base)
        val memExpr = C_DbAttrInfo(base.rEntity, sName, null, dbExpr)
        return V_DbExpr.createExpr(ctx, pos, memExpr.dbExpr)
    }

    override fun createIpEntityMemberExpr(ctx: C_ExprContext, baseValue: C_EntityAttrValueLike, baseExpr: Db_TableExpr, ref: C_MemberRef): C_Expr {
        val vExpr = createIpMemberExpr(ctx, ref)
        return C_VExpr(vExpr)
    }
}

private class C_EntityAttrValueBase(
        val value: V_Expr,
        val safe: Boolean,
        val atEntity: R_DbAtEntity
)

interface C_EntityAttrValueLike {
    fun memberAttr(ctx: C_ExprContext, ref: C_MemberRef, memAttrInfo: C_DbAttrInfo): C_Expr
}

class V_EntityAttrExpr private constructor(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val base: C_EntityAttrValueBase,
        private val parent: V_Expr,
        private val attrInfo: C_DbAttrInfo,
        private val resultType: R_Type
): V_Expr(exprCtx, pos), C_EntityAttrValueLike {
    override fun type() = resultType
    override fun isDb() = false // Important: value does not belong to an outer @-expression

    override fun toRExpr0(): R_Expr {
        val dbAttrExpr = attrInfo.dbExpr
        val whatValue = Db_AtWhatValue_Simple(dbAttrExpr, dbAttrExpr.type)
        val whatField = Db_AtWhatField(R_AtWhatFieldFlags.DEFAULT, whatValue)
        return createRExpr(exprCtx, base.value, base.atEntity, whatField, base.safe, dbAttrExpr.type)
    }

    // Cannot inject the corresponding Db_Expr directly into another Db_Expr - must wrap it in R_Expr.
    override fun toDbExpr0() = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(): C_Destination {
        if (attrInfo.attr == null || !attrInfo.attr.mutable) {
            throw C_Errors.errAttrNotMutable(attrInfo.name.pos, attrInfo.name.str)
        }
        exprCtx.checkDbUpdateAllowed(pos)
        return C_EntityAttrDestination(parent, attrInfo.rEntity, attrInfo.attr)
    }

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val memberRef = C_MemberRef(pos, this, memberName, safe)
        val valueExpr = createDbMemberExpr(ctx, memberRef)
        val fnExpr = C_MemberResolver.functionForType(attrInfo.dbExpr.type, memberRef)

        val res = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        res ?: throw C_Errors.errUnknownMember(attrInfo.dbExpr.type, memberName)

        C_MemberResolver.checkNullAccess(resultType, memberName, safe)
        return res
    }

    private fun createDbMemberExpr(ctx: C_ExprContext, ref: C_MemberRef): C_Expr? {
        val baseDbExpr = attrInfo.dbExpr
        if (baseDbExpr !is Db_TableExpr) return null
        val attrRef = C_EntityAttrRef.resolveByName(baseDbExpr.rEntity, ref.name.str)
        attrRef ?: return null
        return attrRef.createIpEntityMemberExpr(ctx, this, baseDbExpr, ref)
    }

    override fun memberAttr(ctx: C_ExprContext, ref: C_MemberRef, memAttrInfo: C_DbAttrInfo): C_Expr {
        val vExpr = create0(ctx, base, memAttrInfo, ref)
        return C_VExpr(vExpr)
    }

    override fun implicitWhatName(): String? {
        val isAt = base.value.isAtExprItem()
        return if (isAt) attrInfo.name.str else null
    }

    companion object {
        fun createIpMemberExpr(ctx: C_ExprContext, ref: C_MemberRef, atEntity: R_DbAtEntity, memExpr: C_DbAttrInfo): V_Expr {
            val base = C_EntityAttrValueBase(ref.base, ref.safe, atEntity)
            return create0(ctx, base, memExpr, ref)
        }

        private fun create0(ctx: C_ExprContext, base: C_EntityAttrValueBase, memAttrInfo: C_DbAttrInfo, ref: C_MemberRef): V_Expr {
            C_MemberResolver.checkNullAccess(ref.base.type(), ref.name, ref.safe)
            val resultType = C_Utils.effectiveMemberType(memAttrInfo.dbExpr.type, ref.safe)
            return V_EntityAttrExpr(ctx, ref.pos, base, ref.base, memAttrInfo, resultType)
        }

        fun createRExpr(
                ctx: C_ExprContext,
                base: V_Expr,
                atEntity: R_DbAtEntity,
                whatField: Db_AtWhatField,
                safe: Boolean,
                resType: R_Type
        ): R_Expr {
            val cLambdaB = C_LambdaBlock.builder(ctx, atEntity.rEntity.type)
            val cLambda = cLambdaB.build()

            val whereLeft = Db_EntityExpr(atEntity)
            val whereRight = cLambda.compileVarDbExpr()
            val where = C_Utils.makeDbBinaryExprEq(whereLeft, whereRight)

            val what = listOf(whatField)

            val from = listOf(atEntity)
            val atBase = Db_AtExprBase(from, what, where)
            val calculator = R_MemberCalculator_DataAttribute(resType, atBase, cLambda.rLambda)

            val rBase = base.toRExpr()
            val rExpr = R_MemberExpr(rBase, safe, calculator)
            return rExpr
        }
    }
}

private class C_MemberFunctionExpr(private val memberRef: C_MemberRef, private val fn: C_SysMemberFunction): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = memberRef.pos

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        val cArgs = C_FunctionUtils.compileRegularArgs(ctx, args, fn.getParamsHints())
        if (!cArgs.named.isEmpty()) {
            val arg = cArgs.named[0]
            ctx.msgCtx.error(arg.first.pos, "expr:call:sys_member_fn_named_arg:${arg.first}",
                    "Named arguments not supported for this function")
            return C_Utils.errorExpr(ctx, pos)
        } else if (!cArgs.valid) {
            return C_Utils.errorExpr(ctx, pos)
        }
        return fn.compileCall(ctx, memberRef, cArgs.positional)
    }
}

class C_DbAttrInfo(val rEntity: R_EntityDefinition, val name: S_Name, val attr: R_Attribute?, val dbExpr: Db_Expr)

private fun makeDbAttrExpr(base: Db_TableExpr, attr: R_Attribute): Db_Expr {
    val resultType = attr.type
    val resultEntity = (resultType as? R_EntityType)?.rEntity
    return if (resultEntity == null) Db_AttrExpr(base, attr) else Db_RelExpr(base, attr, resultEntity)
}

private fun makeMemberExpr(ctx: C_ExprContext, ref: C_MemberRef, memAttr: C_MemberAttr): V_Expr {
    val fieldType = memAttr.type
    val effectiveType = C_Utils.effectiveMemberType(fieldType, ref.safe)
    val exprFacts = C_ExprVarFacts.of(postFacts = ref.base.varFacts().postFacts)
    return V_MemberAttrExpr(ctx, ref, memAttr, effectiveType, exprFacts)
}
