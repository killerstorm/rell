package net.postchain.rell.parser

import net.postchain.rell.model.*

class C_MemberRef(val pos: S_Pos, val base: C_Value, val name: S_Name, val safe: Boolean) {
    fun qualifiedName() = "${base.type().toStrictString()}.${name.str}"
}

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

    private fun valueForRecord(type: R_RecordType, ref: C_MemberRef): C_Expr? {
        val attr = type.attributes[ref.name.str]
        if (attr == null) {
            return null
        }

        val field = C_MemberAttr_RecordAttr(attr)
        return makeMemberExpr(ref, field)
    }

    private fun valueForVirtualRecord(type: R_VirtualRecordType, ref: C_MemberRef): C_Expr? {
        val attr = type.innerType.attributes[ref.name.str]
        if (attr == null) {
            return null
        }

        val virtualType = S_VirtualType.virtualMemberType(attr.type)
        val field = C_MemberAttr_VirtualRecordAttr(virtualType, attr)
        return makeMemberExpr(ref, field)
    }

    private fun valueForClass(type: R_ClassType, ref: C_MemberRef): C_Expr? {
        val attrRef = C_ClassAttrRef.resolveByName(type.rClass, ref.name.str)
        return attrRef?.createIpMemberExpr(ref)
    }

    private fun valueForEnum(ref: C_MemberRef): C_Expr? {
        val calculator = if (ref.name.str == "name") {
            R_MemberCalculator_SysFn(R_TextType, R_SysFn_Enum.Name, listOf())
        } else if (ref.name.str == "value") {
            R_MemberCalculator_SysFn(R_IntegerType, R_SysFn_Enum.Value, listOf())
        } else {
            return null
        }
        val field = C_MemberAttr_SimpleReadOnly(ref.name, calculator)
        return makeMemberExpr(ref, field)
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

private class C_MemberAttr_RecordAttr(private val attr: R_Attrib): C_MemberAttr(attr.type) {
    override fun calculator() = R_MemberCalculator_RecordAttr(attr)

    override fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        return R_RecordMemberExpr(base, attr)
    }
}

private class C_MemberAttr_VirtualRecordAttr(type: R_Type, private val attr: R_Attrib): C_MemberAttr(type) {
    override fun calculator() = R_MemberCalculator_VirtualRecordAttr(type, attr)
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

    override fun toRExpr(): R_Expr {
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

sealed class C_ClassAttrRef(protected val rClass: R_Class, val name: String) {
    abstract fun type(): R_Type
    abstract fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr
    abstract fun createIpMemberExpr(ref: C_MemberRef): C_Expr
    abstract fun createDbMemberExpr(base: Db_TableExpr, pos: S_Pos, sName: S_Name): C_Expr
    abstract fun createIpClassMemberExpr(baseValue: C_ClassAttrValueLike, baseExpr: Db_TableExpr, ref: C_MemberRef): C_Expr

    companion object {
        const val ROWID_NAME = "rowid"
        val ROWID_TYPE: R_Type = R_RowidType

        fun isAllowedRegularAttrName(name: String) = name != ROWID_NAME

        fun resolveByName(rClass: R_Class, name: String): C_ClassAttrRef? {
            return if (name == ROWID_NAME) {
                C_ClassAttrRef_Rowid(rClass)
            } else {
                val attr = rClass.attributes[name]
                if (attr == null) null else C_ClassAttrRef_Regular(rClass, attr)
            }
        }

        fun resolveByType(rClass: R_Class, type: R_Type): List<C_ClassAttrRef> {
            val res = mutableListOf<C_ClassAttrRef>()
            if (type == ROWID_TYPE) {
                res.add(C_ClassAttrRef_Rowid(rClass))
            }
            for (attr in rClass.attributes.values) {
                if (attr.type == type) {
                    res.add(C_ClassAttrRef_Regular(rClass, attr))
                }
            }
            return res
        }
    }
}

private class C_ClassAttrRef_Regular(rClass: R_Class, private val attr: R_Attrib): C_ClassAttrRef(rClass, attr.name) {
    override fun type() = attr.type

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return makeDbAttrExpr(baseExpr, attr)
    }

    override fun createIpMemberExpr(ref: C_MemberRef): C_Expr {
        val atClass = R_AtClass(rClass, 0)
        val baseDbExpr = Db_ClassExpr(atClass)
        val attrInfo = createAttrInfo(baseDbExpr, ref.name)
        return C_ClassAttrValue.createIpMemberExpr(ref, atClass, attrInfo)
    }

    override fun createDbMemberExpr(base: Db_TableExpr, pos: S_Pos, sName: S_Name): C_Expr {
        val dbExpr = makeDbAttrExpr(base, attr)
        return C_DbValue.makeExpr(pos, dbExpr)
    }

    override fun createIpClassMemberExpr(baseValue: C_ClassAttrValueLike, baseExpr: Db_TableExpr, ref: C_MemberRef): C_Expr {
        val attrInfo = createAttrInfo(baseExpr, ref.name)
        return baseValue.memberAttr(ref, attrInfo)
    }

    private fun createAttrInfo(baseExpr: Db_TableExpr, sName: S_Name): C_DbAttrInfo {
        val dbExpr = makeDbAttrExpr(baseExpr, attr)
        return C_DbAttrInfo(rClass, sName, attr, dbExpr)
    }
}

private class C_ClassAttrRef_Rowid(rClass: R_Class): C_ClassAttrRef(rClass, ROWID_NAME) {
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
        val memExpr = C_DbAttrInfo(base.rClass, sName, null, dbExpr)
        return C_DbValue.makeExpr(pos, memExpr.dbExpr)
    }

    override fun createIpClassMemberExpr(baseValue: C_ClassAttrValueLike, baseExpr: Db_TableExpr, ref: C_MemberRef): C_Expr {
        return createIpMemberExpr(ref)
    }
}

private class C_ClassAttrValueBase(
        val value: C_Value,
        val safe: Boolean,
        val atClass: R_AtClass
)

interface C_ClassAttrValueLike {
    fun memberAttr(ref: C_MemberRef, memAttrInfo: C_DbAttrInfo): C_Expr
}

private class C_ClassAttrValue private constructor(
        pos: S_Pos,
        private val base: C_ClassAttrValueBase,
        private val parent: C_Value,
        private val attrInfo: C_DbAttrInfo,
        private val resultType: R_Type
): C_Value(pos), C_ClassAttrValueLike {
    override fun type() = resultType
    override fun isDb() = false // Important: value does not belong to an outer @-expression

    override fun toRExpr(): R_Expr {
        val from = listOf(base.atClass)

        val whereLeft = Db_ClassExpr(base.atClass)
        val whereRight = Db_ParameterExpr(base.atClass.type, 0)
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
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(pos)
        return C_ClassAttrDestination(parent, attrInfo.rClass, attrInfo.attr)
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
        val attrRef = C_ClassAttrRef.resolveByName(baseDbExpr.rClass, ref.name.str)
        attrRef ?: return null
        return attrRef.createIpClassMemberExpr(this, baseDbExpr, ref)
    }

    override fun memberAttr(ref: C_MemberRef, memAttrInfo: C_DbAttrInfo): C_Expr {
        return create0(base, memAttrInfo, ref)
    }

    companion object {
        fun createIpMemberExpr(ref: C_MemberRef, atClass: R_AtClass, memExpr: C_DbAttrInfo): C_Expr {
            val base = C_ClassAttrValueBase(ref.base, ref.safe, atClass)
            return create0(base, memExpr, ref)
        }

        private fun create0(base: C_ClassAttrValueBase, memAttrInfo: C_DbAttrInfo, ref: C_MemberRef): C_Expr {
            C_MemberResolver.checkNullAccess(ref.base.type(), ref.name, ref.safe)
            val resultType = C_Utils.effectiveMemberType(memAttrInfo.dbExpr.type, ref.safe)
            val value = C_ClassAttrValue(ref.pos, base, ref.base, memAttrInfo, resultType)
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

class C_DbAttrInfo(val rClass: R_Class, val name: S_Name, val attr: R_Attrib?, val dbExpr: Db_Expr)

private fun makeDbAttrExpr(base: Db_TableExpr, attr: R_Attrib): Db_Expr {
    val resultType = attr.type
    val resultClass = (resultType as? R_ClassType)?.rClass
    return if (resultClass == null) Db_AttrExpr(base, attr) else Db_RelExpr(base, attr, resultClass)
}

private fun makeMemberExpr(ref: C_MemberRef, memAttr: C_MemberAttr): C_Expr {
    val fieldType = memAttr.type
    val effectiveType = C_Utils.effectiveMemberType(fieldType, ref.safe)
    val exprFacts = C_ExprVarFacts.of(postFacts = ref.base.varFacts().postFacts)
    val value = C_MemberAttrValue(ref, memAttr, effectiveType, exprFacts)
    return C_ValueExpr(value)
}
