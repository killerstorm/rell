/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_NameExprPair
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_VirtualType
import net.postchain.rell.model.*

class C_MemberRef(val pos: S_Pos, val base: C_Value, val name: S_Name, val safe: Boolean) {
    fun qualifiedName() = "${base.type().toStrictString()}.${name.str}"
}

object C_MemberResolver {
    fun valueForType(ctx: C_ExprContext, type: R_Type, ref: C_MemberRef): C_Expr? {
        return when (type) {
            is R_TupleType -> valueForTuple(type, ref)
            is R_VirtualTupleType -> valueForVirtualTuple(type, ref)
            is R_StructType -> valueForStruct(type, ref)
            is R_VirtualStructType -> valueForVirtualStruct(type, ref)
            is R_EntityType -> valueForEntity(type, ref)
            is R_EnumType -> valueForEnum(ctx, ref)
            else -> null
        }
    }

    private fun valueForTuple(type: R_TupleType, ref: C_MemberRef): C_Expr? {
        val idx = type.fields.indexOfFirst { it.name == ref.name.str }
        if (idx == -1) {
            return null
        }

        val field = C_MemberAttr_TupleAttr(type.fields[idx].type, idx)
        return makeMemberExpr(ref, field)
    }

    private fun valueForVirtualTuple(type: R_VirtualTupleType, ref: C_MemberRef): C_Expr? {
        val tupleType = type.innerType
        val idx = tupleType.fields.indexOfFirst { it.name == ref.name.str }
        if (idx == -1) {
            return null
        }

        val field = tupleType.fields[idx]
        val virtualType = S_VirtualType.virtualMemberType(field.type)
        val memberField = C_MemberAttr_VirtualTupleAttr(virtualType, idx)
        return makeMemberExpr(ref, memberField)
    }

    private fun valueForStruct(type: R_StructType, ref: C_MemberRef): C_Expr? {
        val attr = type.struct.attributes[ref.name.str]
        if (attr == null) {
            return null
        }

        val field = C_MemberAttr_StructAttr(attr)
        return makeMemberExpr(ref, field)
    }

    private fun valueForVirtualStruct(type: R_VirtualStructType, ref: C_MemberRef): C_Expr? {
        val attr = type.innerType.struct.attributes[ref.name.str]
        if (attr == null) {
            return null
        }

        val virtualType = S_VirtualType.virtualMemberType(attr.type)
        val field = C_MemberAttr_VirtualStructAttr(virtualType, attr)
        return makeMemberExpr(ref, field)
    }

    private fun valueForEntity(type: R_EntityType, ref: C_MemberRef): C_Expr? {
        val attrRef = C_EntityAttrRef.resolveByName(type.rEntity, ref.name.str)
        return attrRef?.createIpMemberExpr(ref)
    }

    private fun valueForEnum(ctx: C_ExprContext, ref: C_MemberRef): C_Expr? {
        val fn = C_LibFunctions.getEnumPropertyOpt(ref.name.str)
        return if (fn == null) null else {
            val fnExpr = C_MemberFunctionExpr(ref, fn)
            fnExpr.call(ctx, ref.pos, listOf())
        }
    }

    fun functionForType(type: R_Type, ref: C_MemberRef): C_Expr? {
        val fn = C_LibFunctions.getMemberFunctionOpt(type, ref.name.str)
        return if (fn == null) null else C_MemberFunctionExpr(ref, fn)
    }

    fun checkNullAccess(type: R_Type, name: S_Name, safe: Boolean) {
        if (!safe && type is R_NullableType) {
            throw C_Error(name.pos, "expr_mem_null:${name.str}", "Cannot access member '${name.str}' of nullable value")
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

private class C_MemberAttr_StructAttr(private val attr: R_Attrib): C_MemberAttr(attr.type) {
    override fun calculator() = R_MemberCalculator_StructAttr(attr)

    override fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        return R_StructMemberExpr(base, attr)
    }
}

private class C_MemberAttr_VirtualStructAttr(type: R_Type, private val attr: R_Attrib): C_MemberAttr(type) {
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

private class C_MemberAttrValue(
        private val memberRef: C_MemberRef,
        private val memAttr: C_MemberAttr,
        private val type: R_Type,
        private val varFacts: C_ExprVarFacts
): C_Value(memberRef.pos) {
    override fun type() = type
    override fun isDb() = memberRef.base.isDb()

    override fun toRExpr0(): R_Expr {
        val rBase = memberRef.base.toRExpr()
        val calculator = memAttr.calculator()
        return R_MemberExpr(rBase, memberRef.safe, calculator)
    }

    override fun toDbExpr(): Db_Expr {
        val rExpr = toRExpr()
        return C_Utils.toDbExpr(memberRef.name.pos, rExpr)
    }

    override fun varFacts() = varFacts

    override fun destination(ctx: C_ExprContext): C_Destination {
        val rBase = memberRef.base.toRExpr()
        val rDstExpr = memAttr.destination(memberRef.name.pos, rBase)
        return C_SimpleDestination(rDstExpr)
    }
}

sealed class C_EntityAttrRef(protected val rEntity: R_Entity, val name: String) {
    abstract fun type(): R_Type
    abstract fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr
    abstract fun createIpMemberExpr(ref: C_MemberRef): C_Expr
    abstract fun createDbMemberExpr(base: Db_TableExpr, pos: S_Pos, sName: S_Name): C_Expr
    abstract fun createIpEntityMemberExpr(baseValue: C_EntityAttrValueLike, baseExpr: Db_TableExpr, ref: C_MemberRef): C_Expr

    companion object {
        const val ROWID_NAME = "rowid"
        val ROWID_TYPE: R_Type = R_RowidType

        fun isAllowedRegularAttrName(name: String) = name != ROWID_NAME

        fun resolveByName(rEntity: R_Entity, name: String): C_EntityAttrRef? {
            return if (name == ROWID_NAME) {
                C_EntityAttrRef_Rowid(rEntity)
            } else {
                val attr = rEntity.attributes[name]
                if (attr == null) null else C_EntityAttrRef_Regular(rEntity, attr)
            }
        }

        fun resolveByType(rEntity: R_Entity, type: R_Type): List<C_EntityAttrRef> {
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

private class C_EntityAttrRef_Regular(rEntity: R_Entity, private val attr: R_Attrib): C_EntityAttrRef(rEntity, attr.name) {
    override fun type() = attr.type

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return makeDbAttrExpr(baseExpr, attr)
    }

    override fun createIpMemberExpr(ref: C_MemberRef): C_Expr {
        val atEntity = R_AtEntity(rEntity, 0)
        val baseDbExpr = Db_EntityExpr(atEntity)
        val attrInfo = createAttrInfo(baseDbExpr, ref.name)
        return C_EntityAttrValue.createIpMemberExpr(ref, atEntity, attrInfo)
    }

    override fun createDbMemberExpr(base: Db_TableExpr, pos: S_Pos, sName: S_Name): C_Expr {
        val dbExpr = makeDbAttrExpr(base, attr)
        return C_DbValue.createExpr(pos, dbExpr)
    }

    override fun createIpEntityMemberExpr(baseValue: C_EntityAttrValueLike, baseExpr: Db_TableExpr, ref: C_MemberRef): C_Expr {
        val attrInfo = createAttrInfo(baseExpr, ref.name)
        return baseValue.memberAttr(ref, attrInfo)
    }

    private fun createAttrInfo(baseExpr: Db_TableExpr, sName: S_Name): C_DbAttrInfo {
        val dbExpr = makeDbAttrExpr(baseExpr, attr)
        return C_DbAttrInfo(rEntity, sName, attr, dbExpr)
    }
}

private class C_EntityAttrRef_Rowid(rEntity: R_Entity): C_EntityAttrRef(rEntity, ROWID_NAME) {
    override fun type() = ROWID_TYPE

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return Db_RowidExpr(baseExpr)
    }

    override fun createIpMemberExpr(ref: C_MemberRef): C_Expr {
        val field = C_MemberAttr_SimpleReadOnly(ref.name, R_MemberCalculator_Rowid)
        return makeMemberExpr(ref, field)
    }

    override fun createDbMemberExpr(base: Db_TableExpr, pos: S_Pos, sName: S_Name): C_Expr {
        val dbExpr = Db_RowidExpr(base)
        val memExpr = C_DbAttrInfo(base.rEntity, sName, null, dbExpr)
        return C_DbValue.createExpr(pos, memExpr.dbExpr)
    }

    override fun createIpEntityMemberExpr(baseValue: C_EntityAttrValueLike, baseExpr: Db_TableExpr, ref: C_MemberRef): C_Expr {
        return createIpMemberExpr(ref)
    }
}

private class C_EntityAttrValueBase(
        val value: C_Value,
        val safe: Boolean,
        val atEntity: R_AtEntity
)

interface C_EntityAttrValueLike {
    fun memberAttr(ref: C_MemberRef, memAttrInfo: C_DbAttrInfo): C_Expr
}

private class C_EntityAttrValue private constructor(
        pos: S_Pos,
        private val base: C_EntityAttrValueBase,
        private val parent: C_Value,
        private val attrInfo: C_DbAttrInfo,
        private val resultType: R_Type
): C_Value(pos), C_EntityAttrValueLike {
    override fun type() = resultType
    override fun isDb() = false // Important: value does not belong to an outer @-expression

    override fun toRExpr0(): R_Expr {
        val from = listOf(base.atEntity)

        val whereLeft = Db_EntityExpr(base.atEntity)
        val whereRight = Db_ParameterExpr(base.atEntity.rEntity.type, 0)
        val where = C_Utils.makeDbBinaryExprEq(whereLeft, whereRight)

        val atBase = R_AtExprBase(from, listOf(attrInfo.dbExpr), where, listOf())
        val calculator = R_MemberCalculator_DataAttribute(attrInfo.dbExpr.type, atBase)

        val rBase = base.value.toRExpr()
        val rExpr = R_MemberExpr(rBase, base.safe, calculator)
        return rExpr
    }

    // Cannot inject the corresponding Db_Expr directly into another Db_Expr - must wrap it in R_Expr.
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(ctx: C_ExprContext): C_Destination {
        if (attrInfo.attr == null || !attrInfo.attr.mutable) {
            throw C_Errors.errAttrNotMutable(attrInfo.name.pos, attrInfo.name.str)
        }
        ctx.checkDbUpdateAllowed(pos)
        return C_EntityAttrDestination(parent, attrInfo.rEntity, attrInfo.attr)
    }

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val memberRef = C_MemberRef(pos, this, memberName, safe)
        val valueExpr = createDbMemberExpr(memberRef)
        val fnExpr = C_MemberResolver.functionForType(attrInfo.dbExpr.type, memberRef)

        val res = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        res ?: throw C_Errors.errUnknownMember(attrInfo.dbExpr.type, memberName)

        C_MemberResolver.checkNullAccess(resultType, memberName, safe)
        return res
    }

    private fun createDbMemberExpr(ref: C_MemberRef): C_Expr? {
        val baseDbExpr = attrInfo.dbExpr
        if (baseDbExpr !is Db_TableExpr) return null
        val attrRef = C_EntityAttrRef.resolveByName(baseDbExpr.rEntity, ref.name.str)
        attrRef ?: return null
        return attrRef.createIpEntityMemberExpr(this, baseDbExpr, ref)
    }

    override fun memberAttr(ref: C_MemberRef, memAttrInfo: C_DbAttrInfo): C_Expr {
        return create0(base, memAttrInfo, ref)
    }

    companion object {
        fun createIpMemberExpr(ref: C_MemberRef, atEntity: R_AtEntity, memExpr: C_DbAttrInfo): C_Expr {
            val base = C_EntityAttrValueBase(ref.base, ref.safe, atEntity)
            return create0(base, memExpr, ref)
        }

        private fun create0(base: C_EntityAttrValueBase, memAttrInfo: C_DbAttrInfo, ref: C_MemberRef): C_Expr {
            C_MemberResolver.checkNullAccess(ref.base.type(), ref.name, ref.safe)
            val resultType = C_Utils.effectiveMemberType(memAttrInfo.dbExpr.type, ref.safe)
            val value = C_EntityAttrValue(ref.pos, base, ref.base, memAttrInfo, resultType)
            return C_ValueExpr(value)
        }
    }
}

private class C_MemberFunctionExpr(private val memberRef: C_MemberRef, private val fn: C_SysMemberFunction): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = memberRef.pos

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        val cArgs = C_FunctionUtils.compileRegularArgs(ctx, args)
        if (!cArgs.named.isEmpty()) {
            val arg = cArgs.named[0]
            ctx.msgCtx.error(arg.first.pos, "expr:call:sys_member_fn_named_arg:${arg.first}",
                    "Named arguments not supported for this function")
            val rExpr = C_Utils.crashExpr()
            return C_RValue.makeExpr(pos, rExpr)
        } else if (!cArgs.valid) {
            val rExpr = C_Utils.crashExpr()
            return C_RValue.makeExpr(pos, rExpr)
        }
        return fn.compileCall(ctx, memberRef, cArgs.positional)
    }
}

class C_DbAttrInfo(val rEntity: R_Entity, val name: S_Name, val attr: R_Attrib?, val dbExpr: Db_Expr)

private fun makeDbAttrExpr(base: Db_TableExpr, attr: R_Attrib): Db_Expr {
    val resultType = attr.type
    val resultEntity = (resultType as? R_EntityType)?.rEntity
    return if (resultEntity == null) Db_AttrExpr(base, attr) else Db_RelExpr(base, attr, resultEntity)
}

private fun makeMemberExpr(ref: C_MemberRef, memAttr: C_MemberAttr): C_Expr {
    val fieldType = memAttr.type
    val effectiveType = C_Utils.effectiveMemberType(fieldType, ref.safe)
    val exprFacts = C_ExprVarFacts.of(postFacts = ref.base.varFacts().postFacts)
    val value = C_MemberAttrValue(ref, memAttr, effectiveType, exprFacts)
    return C_ValueExpr(value)
}
