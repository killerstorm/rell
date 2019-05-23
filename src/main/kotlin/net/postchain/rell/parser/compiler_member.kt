package net.postchain.rell.parser

import net.postchain.rell.model.*

class C_MemberRef(val pos: S_Pos, val base: C_Value, val name: S_Name, val safe: Boolean)

object C_MemberResolver {
    fun valueForType(type: R_Type, ref: C_MemberRef): C_Expr? {
        return when (type) {
            is R_TupleType -> valueForTuple(type, ref)
            is R_VirtualTupleType -> valueForVirtualTuple(type, ref)
            is R_RecordType -> valueForRecord(type, ref)
            is R_VirtualRecordType -> valueForVirtualRecord(type, ref)
            is R_ClassType -> valueForClass(type, ref)
            is R_EnumType -> valueForEnum(ref)
            else -> null
        }
    }

    private fun valueForTuple(type: R_TupleType, ref: C_MemberRef): C_Expr? {
        val idx = type.fields.indexOfFirst { it.name == ref.name.str }
        if (idx == -1) {
            return null
        }

        val field = C_MemberField_TupleField(type.fields[idx].type, idx)
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
        val memberField = C_MemberField_VirtualTupleField(virtualType, idx)
        return makeMemberExpr(ref, memberField)
    }

    private fun valueForRecord(type: R_RecordType, ref: C_MemberRef): C_Expr? {
        val attr = type.attributes[ref.name.str]
        if (attr == null) {
            return null
        }

        val field = C_MemberField_RecordAttr(attr)
        return makeMemberExpr(ref, field)
    }

    private fun valueForVirtualRecord(type: R_VirtualRecordType, ref: C_MemberRef): C_Expr? {
        val attr = type.innerType.attributes[ref.name.str]
        if (attr == null) {
            return null
        }

        val virtualType = S_VirtualType.virtualMemberType(attr.type)
        val field = C_MemberField_VirtualRecordAttr(virtualType, attr)
        return makeMemberExpr(ref, field)
    }

    private fun valueForClass(type: R_ClassType, ref: C_MemberRef): C_Expr? {
        return C_ClassFieldValue.createExpr(type, ref)
    }

    private fun valueForEnum(ref: C_MemberRef): C_Expr? {
        val calculator = if (ref.name.str == "name") {
            R_MemberCalculator_SysFn(R_TextType, R_SysFn_Enum_Name, listOf())
        } else if (ref.name.str == "value") {
            R_MemberCalculator_SysFn(R_IntegerType, R_SysFn_Enum_Value, listOf())
        } else {
            return null
        }
        val field = C_MemberField_SimpleReadOnly(ref.name, calculator)
        return makeMemberExpr(ref, field)
    }

    private fun makeMemberExpr(ref: C_MemberRef, field: C_MemberField): C_Expr {
        val fieldType = field.type
        val effectiveType = C_Utils.effectiveMemberType(fieldType, ref.safe)
        val exprFacts = C_ExprVarFacts.of(postFacts = ref.base.varFacts().postFacts)
        val value = C_MemberFieldValue(ref, field, effectiveType, exprFacts)
        return C_ValueExpr(value)
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

private sealed class C_MemberField(val type: R_Type) {
    abstract fun calculator(): R_MemberCalculator
    abstract fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr
}

private class C_MemberField_TupleField(type: R_Type, private val fieldIndex: Int): C_MemberField(type) {
    override fun calculator() = R_MemberCalculator_TupleField(type, fieldIndex)
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(pos)
}

private class C_MemberField_VirtualTupleField(type: R_Type, private val fieldIndex: Int): C_MemberField(type) {
    override fun calculator() = R_MemberCalculator_VirtualTupleField(type, fieldIndex)
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(pos)
}

private class C_MemberField_RecordAttr(private val attr: R_Attrib): C_MemberField(attr.type) {
    override fun calculator() = R_MemberCalculator_RecordAttr(attr)

    override fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        return R_RecordMemberExpr(base, attr)
    }
}

private class C_MemberField_VirtualRecordAttr(type: R_Type, private val attr: R_Attrib): C_MemberField(type) {
    override fun calculator() = R_MemberCalculator_VirtualRecordAttr(type, attr)
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errAttrNotMutable(pos, attr.name)
}

private class C_MemberField_SimpleReadOnly(
        private val name: S_Name,
        private val calculator: R_MemberCalculator
): C_MemberField(calculator.type) {
    override fun calculator() = calculator
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(name)
}

private class C_MemberFieldValue(
        private val memberRef: C_MemberRef,
        private val field: C_MemberField,
        private val type: R_Type,
        private val varFacts: C_ExprVarFacts
): C_Value(memberRef.pos) {
    override fun type() = type
    override fun isDb() = memberRef.base.isDb()

    override fun toRExpr(): R_Expr {
        val rBase = memberRef.base.toRExpr()
        val calculator = field.calculator()
        return R_MemberExpr(rBase, memberRef.safe, calculator)
    }

    override fun toDbExpr(): Db_Expr {
        val rExpr = toRExpr()
        return C_Utils.toDbExpr(memberRef.name.pos, rExpr)
    }

    override fun varFacts() = varFacts

    override fun destination(ctx: C_ExprContext): C_Destination {
        val rBase = memberRef.base.toRExpr()
        val rDstExpr = field.destination(memberRef.name.pos, rBase)
        return C_SimpleDestination(rDstExpr)
    }
}

private class C_ClassFieldValueBase(
        val value: C_Value,
        val safe: Boolean,
        val atClass: R_AtClass
)

private class C_ClassFieldValue private constructor(
        pos: S_Pos,
        private val base: C_ClassFieldValueBase,
        private val parent: C_Value,
        private val memberInfo: C_DbMemberInfo,
        private val resultType: R_Type
): C_Value(pos) {
    override fun type() = resultType
    override fun isDb() = false // Important: node does not belong to an outer @-expression

    override fun toRExpr(): R_Expr {
        val from = listOf(base.atClass)

        val whereLeft = Db_ClassExpr(base.atClass)
        val whereRight = Db_ParameterExpr(base.atClass.type, 0)
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Eq, whereLeft, whereRight)

        val atBase = R_AtExprBase(from, listOf(memberInfo.dbExpr), where, listOf())
        val calculator = R_MemberCalculator_DataAttribute(memberInfo.dbExpr.type, atBase)

        val rBase = base.value.toRExpr()
        val rExpr = R_MemberExpr(rBase, base.safe, calculator)
        return rExpr
    }

    // Cannot inject the corresponding Db_Expr directly into another Db_Expr - must wrap it in R_Expr.
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(ctx: C_ExprContext): C_Destination {
        if (!memberInfo.attr.mutable) {
            throw C_Errors.errAttrNotMutable(memberInfo.name.pos, memberInfo.attr.name)
        }
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(pos)
        return C_ClassFieldDestination(parent, memberInfo.rClass, memberInfo.attr)
    }

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val memberRef = C_MemberRef(pos, this, memberName, safe)
        val valueExpr = create0(base, memberInfo.dbExpr, memberRef)
        val fnExpr = C_MemberResolver.functionForType(memberInfo.dbExpr.type, memberRef)

        val res = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        res ?: throw C_Errors.errUnknownMember(memberInfo.dbExpr.type, memberName)

        C_MemberResolver.checkNullAccess(resultType, memberName, safe)
        return res
    }

    companion object {
        fun createExpr(type: R_ClassType, ref: C_MemberRef): C_Expr? {
            val atClass = R_AtClass(type.rClass, 0)
            val baseDbExpr = Db_ClassExpr(atClass)
            val base = C_ClassFieldValueBase(ref.base, ref.safe, atClass)
            return create0(base, baseDbExpr, ref)
        }

        private fun create0(base: C_ClassFieldValueBase, baseDbExpr: Db_Expr, ref: C_MemberRef): C_Expr? {
            val memExpr = C_DbMemberInfo.create(baseDbExpr, ref.name)
            memExpr ?: return null
            C_MemberResolver.checkNullAccess(ref.base.type(), ref.name, ref.safe)
            val resultType = C_Utils.effectiveMemberType(memExpr.dbExpr.type, ref.safe)
            val value = C_ClassFieldValue(ref.pos, base, ref.base, memExpr, resultType)
            return C_ValueExpr(value)
        }
    }
}

private class C_MemberFunctionExpr(private val memberRef: C_MemberRef, private val fn: C_SysMemberFunction): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = memberRef.pos

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        val cArgs = C_RegularGlobalFunction.compileArgs(ctx, args)
        return fn.compileCall(ctx, memberRef, cArgs)
    }
}

class C_DbMemberInfo private constructor(val rClass: R_Class, val name: S_Name, val attr: R_Attrib, val dbExpr: Db_Expr) {
    companion object {
        fun create(base: Db_Expr, name: S_Name): C_DbMemberInfo? {
            if (base !is Db_TableExpr) return null
            val attr = base.rClass.attributes[name.str]
            if (attr == null) {
                return null
            }

            val resultType = attr.type
            val resultClass = if (resultType is R_ClassType) resultType.rClass else null
            val dbExpr = if (resultClass == null) Db_AttrExpr(base, attr) else Db_RelExpr(base, attr, resultClass)
            return C_DbMemberInfo(base.rClass, name, attr, dbExpr)
        }
    }
}
