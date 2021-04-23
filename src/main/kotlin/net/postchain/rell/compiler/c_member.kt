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
import net.postchain.rell.utils.toImmList

class C_MemberRef(val base: V_Expr, val name: S_Name, val safe: Boolean) {
    fun toLink() = C_MemberLink(base, safe, name.pos)
}

class C_MemberLink(val base: V_Expr, val safe: Boolean, val linkPos: S_Pos)

abstract class C_MemberName {
    abstract fun simpleName(): String
    abstract fun qualifiedName(base: String): String
}

class C_MemberName_Name(private val name: String): C_MemberName() {
    override fun simpleName() = name
    override fun qualifiedName(base: String) = "$base.$name"
}

sealed class C_MemberValue {
    abstract fun type(): R_Type
    abstract fun memberName(): C_MemberName
    abstract fun compile(ctx: C_ExprContext, link: C_MemberLink): V_Expr
}

private class C_MemberValue_BasicAttr(private val attr: C_MemberAttr): C_MemberValue() {
    override fun type() = attr.type
    override fun memberName() = attr.memberName()

    override fun compile(ctx: C_ExprContext, link: C_MemberLink): V_Expr {
        return makeMemberExpr(ctx, link, attr)
    }
}

private class C_MemberValue_EntityAttr(private val attr: C_EntityAttrRef): C_MemberValue() {
    override fun type() = attr.type()
    override fun memberName() = C_MemberName_Name(attr.attrName)

    override fun compile(ctx: C_ExprContext, link: C_MemberLink): V_Expr {
        return attr.createIpMemberExpr(ctx, link)
    }
}

private class C_MemberValue_EnumAttr(
        private val prop: C_SysMemberFormalParamsFuncBody,
        private val propName: String
): C_MemberValue() {
    override fun type() = prop.resType
    override fun memberName() = C_MemberName_Name(propName)

    override fun compile(ctx: C_ExprContext, link: C_MemberLink): V_Expr {
        val caseCtx = C_MemberFuncCaseCtx(link, propName)
        return V_SysMemberPropertyExpr(ctx, caseCtx, prop)
    }
}

object C_MemberResolver {
    fun valueForType(ctx: C_ExprContext, type: R_Type, ref: C_MemberRef): C_Expr? {
        val member = findMemberValueForTypeByName(type, ref.name.str)
        member ?: return null
        val link = ref.toLink()
        val vExpr = member.compile(ctx, link)
        return C_VExpr(vExpr)
    }

    fun findMemberValueForTypeByName(type: R_Type, name: String): C_MemberValue? {
        val resolver = getTypeMemberResolver(type)
        return resolver?.findByName(name)
    }

    fun findMemberValuesForTypeByType(type: R_Type, memberType: R_Type): List<C_MemberValue> {
        val resolver = getTypeMemberResolver(type)
        return resolver?.findByType(memberType) ?: listOf()
    }

    private fun getTypeMemberResolver(type: R_Type): C_TypeMemberResolver<*>? {
        return when (type) {
            is R_TupleType -> C_TypeMemberResolver_Tuple(type)
            is R_VirtualTupleType -> C_TypeMemberResolver_VirtualTuple(type)
            is R_StructType -> C_TypeMemberResolver_Struct(type)
            is R_VirtualStructType -> C_TypeMemberResolver_VirtualStruct(type)
            is R_EntityType -> C_TypeMemberResolver_Entity(type)
            is R_EnumType -> C_TypeMemberResolver_Enum()
            else -> null
        }
    }

    fun functionForType(ctx: C_ExprContext, type: R_Type, ref: C_MemberRef): C_Expr? {
        val name = ref.name.str
        val fn = C_LibFunctions.getMemberFunctionOpt(ctx, type, name)
        val link = ref.toLink()
        return if (fn == null) null else C_MemberFunctionExpr(link, fn, name)
    }

    fun checkNullAccess(type: R_Type, name: S_Name, safe: Boolean) {
        checkNullAccess(type, safe, name.pos, name.str)
    }

    fun checkNullAccess(type: R_Type, safe: Boolean, linkPos: S_Pos, memberNameMsg: String) {
        if (!safe && type is R_NullableType) {
            throw C_Error.stop(linkPos, "expr_mem_null:$memberNameMsg",
                    "Cannot access member '$memberNameMsg' of nullable type $type")
        }
    }

    private abstract class C_TypeMemberResolver<MemberT> {
        protected abstract fun toMemberValue(member: MemberT): C_MemberValue
        protected abstract fun findByName0(name: String): MemberT?
        protected abstract fun findByType0(memberType: R_Type): List<MemberT>

        fun findByName(name: String): C_MemberValue? {
            val member = findByName0(name)
            return if (member == null) null else toMemberValue(member)
        }

        fun findByType(memberType: R_Type): List<C_MemberValue> {
            val members = findByType0(memberType)
            return members.map { toMemberValue(it) }
        }
    }

    private class C_TypeMemberResolver_Tuple(val type: R_TupleType): C_TypeMemberResolver<Int>() {
        override fun toMemberValue(member: Int): C_MemberValue {
            val field = type.fields[member]
            val memAttr = C_MemberAttr_RegularTupleAttr(field.type, member, field)
            return C_MemberValue_BasicAttr(memAttr)
        }

        override fun findByName0(name: String): Int? {
            val idx = type.fields.indexOfFirst { it.name == name }
            return if (idx == -1) null else idx
        }

        override fun findByType0(memberType: R_Type): List<Int> {
            return type.fields.withIndex().filter { it.value.type == memberType }.map { it.index }.toImmList()
        }
    }

    private class C_TypeMemberResolver_VirtualTuple(val type: R_VirtualTupleType): C_TypeMemberResolver<Int>() {
        override fun toMemberValue(member: Int): C_MemberValue {
            val field = type.innerType.fields[member]
            val virtualType = S_VirtualType.virtualMemberType(field.type)
            val memAttr = C_MemberAttr_VirtualTupleAttr(virtualType, member, field)
            return C_MemberValue_BasicAttr(memAttr)
        }

        override fun findByName0(name: String): Int? {
            val idx = type.innerType.fields.indexOfFirst { it.name == name }
            return if (idx == -1) null else idx
        }

        override fun findByType0(memberType: R_Type): List<Int> {
            return type.innerType.fields.withIndex().filter { it.value.type == memberType }.map { it.index }.toImmList()
        }
    }

    private class C_TypeMemberResolver_Struct(val type: R_StructType): C_TypeMemberResolver<R_Attribute>() {
        override fun toMemberValue(member: R_Attribute): C_MemberValue {
            val memAttr = C_MemberAttr_RegularStructAttr(member)
            return C_MemberValue_BasicAttr(memAttr)
        }

        override fun findByName0(name: String) = type.struct.attributes[name]

        override fun findByType0(memberType: R_Type): List<R_Attribute> {
            return type.struct.attributes.values.filter { it.type == memberType }.toImmList()
        }
    }

    private class C_TypeMemberResolver_VirtualStruct(val type: R_VirtualStructType): C_TypeMemberResolver<R_Attribute>() {
        override fun toMemberValue(member: R_Attribute): C_MemberValue {
            val virtualType = S_VirtualType.virtualMemberType(member.type)
            val memAttr = C_MemberAttr_VirtualStructAttr(virtualType, member)
            return C_MemberValue_BasicAttr(memAttr)
        }

        override fun findByName0(name: String) = type.innerType.struct.attributes[name]

        override fun findByType0(memberType: R_Type): List<R_Attribute> {
            return type.innerType.struct.attributes.values.filter { it.type == memberType }.toImmList()
        }
    }

    private class C_TypeMemberResolver_Entity(val type: R_EntityType): C_TypeMemberResolver<C_EntityAttrRef>() {
        override fun toMemberValue(member: C_EntityAttrRef) = C_MemberValue_EntityAttr(member)
        override fun findByName0(name: String) = C_EntityAttrRef.resolveByName(type.rEntity, name)
        override fun findByType0(memberType: R_Type) = C_EntityAttrRef.resolveByType(type.rEntity, memberType)
    }

    private class C_TypeMemberResolver_Enum: C_TypeMemberResolver<C_MemberValue>() {
        override fun toMemberValue(member: C_MemberValue) = member

        override fun findByName0(name: String): C_MemberValue? {
            val prop = C_LibFunctions.getEnumPropertyOpt(name)
            return if (prop == null) null else C_MemberValue_EnumAttr(prop, name)
        }

        override fun findByType0(memberType: R_Type): List<C_MemberValue> {
            return C_LibFunctions.getEnumProperties()
                    .filter { it.value.resType == memberType }
                    .map { C_MemberValue_EnumAttr(it.value, it.key) }
                    .toImmList()
        }
    }
}

private sealed class C_MemberAttr(val type: R_Type) {
    abstract fun attrName(): String?
    abstract fun memberName(): C_MemberName
    abstract fun calculator(): R_MemberCalculator
    abstract fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr
}

private sealed class C_MemberAttr_TupleAttr(
        type: R_Type,
        protected val fieldIndex: Int,
        protected val field: R_TupleField
): C_MemberAttr(type) {
    final override fun attrName() = field.name

    final override fun memberName(): C_MemberName {
        return if (field.name != null) C_MemberName_TupleName(field.name) else C_MemberName_TupleIndex(fieldIndex)
    }

    final override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(pos)

    private class C_MemberName_TupleName(private val name: String): C_MemberName() {
        override fun simpleName() = name
        override fun qualifiedName(base: String) = ".$name"
    }

    private class C_MemberName_TupleIndex(private val index: Int): C_MemberName() {
        override fun simpleName() = "[$index]"
        override fun qualifiedName(base: String) = simpleName()
    }
}

private class C_MemberAttr_RegularTupleAttr(type: R_Type, fieldIndex: Int, field: R_TupleField)
    : C_MemberAttr_TupleAttr(type, fieldIndex, field)
{
    override fun calculator() = R_MemberCalculator_TupleAttr(type, fieldIndex)
}

private class C_MemberAttr_VirtualTupleAttr(type: R_Type, fieldIndex: Int, field: R_TupleField)
    : C_MemberAttr_TupleAttr(type, fieldIndex, field)
{
    override fun calculator() = R_MemberCalculator_VirtualTupleAttr(type, fieldIndex)
}

private sealed class C_MemberAttr_StructAttr(type: R_Type, protected val attr: R_Attribute): C_MemberAttr(type) {
    final override fun attrName() = attr.name
    final override fun memberName() = C_MemberName_Name(attr.name)
}

private class C_MemberAttr_RegularStructAttr(attr: R_Attribute): C_MemberAttr_StructAttr(attr.type, attr) {
    override fun calculator() = R_MemberCalculator_StructAttr(attr)

    override fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        return R_StructMemberExpr(base, attr)
    }
}

private class C_MemberAttr_VirtualStructAttr(type: R_Type, attr: R_Attribute): C_MemberAttr_StructAttr(type, attr) {
    override fun calculator() = R_MemberCalculator_VirtualStructAttr(type, attr)
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errAttrNotMutable(pos, attr.name)
}

private class C_MemberAttr_SimpleReadOnly(
        private val calculator: R_MemberCalculator,
        private val attrName: String
): C_MemberAttr(calculator.type) {
    override fun attrName() = attrName
    override fun memberName() = C_MemberName_Name(attrName)
    override fun calculator() = calculator
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(pos, attrName)
}

private class V_MemberAttrExpr(
        exprCtx: C_ExprContext,
        private val memberLink: C_MemberLink,
        private val memberAttr: C_MemberAttr,
        private val type: R_Type,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, memberLink.base.pos) {
    private val isDb = isDb(memberLink.base)
    private val atDependencies = memberLink.base.atDependencies()

    override fun type() = type
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies

    override fun implicitAtWhereAttrName(): String? {
        val isAt = memberLink.base.isAtExprItem()
        return if (isAt) memberAttr.attrName() else null
    }

    override fun implicitAtWhatAttrName(): String? {
        val isAt = memberLink.base.isAtExprItem()
        return if (isAt) memberAttr.attrName() else null
    }

    override fun toRExpr0(): R_Expr {
        val rBase = memberLink.base.toRExpr()
        val calculator = memberAttr.calculator()
        return R_MemberExpr(rBase, memberLink.safe, calculator)
    }

    override fun toDbExpr0(): Db_Expr {
        val rExpr = toRExpr()
        return C_Utils.toDbExpr(memberLink.linkPos, rExpr)
    }

    override fun varFacts() = varFacts

    override fun destination(): C_Destination {
        val rBase = memberLink.base.toRExpr()
        val rDstExpr = memberAttr.destination(memberLink.linkPos, rBase)
        return C_SimpleDestination(rDstExpr)
    }
}

sealed class C_EntityAttrRef(protected val rEntity: R_EntityDefinition, val attrName: String) {
    abstract fun type(): R_Type
    abstract fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr
    abstract fun createIpMemberExpr(ctx: C_ExprContext, link: C_MemberLink): V_Expr
    abstract fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr, basePos: S_Pos, linkPos: S_Pos): C_Expr

    abstract fun createIpEntityMemberExpr(
            ctx: C_ExprContext,
            baseValue: C_EntityAttrValueLike,
            baseExpr: Db_TableExpr,
            link: C_MemberLink
    ): C_Expr

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

    override fun createIpMemberExpr(ctx: C_ExprContext, link: C_MemberLink): V_Expr {
        val atEntity = ctx.makeAtEntity(rEntity, ctx.appCtx.nextAtExprId())
        val baseDbExpr = Db_EntityExpr(atEntity)
        val attrInfo = createAttrInfo(baseDbExpr, link)
        return V_EntityAttrExpr.createIpMemberExpr(ctx, link, atEntity, attrInfo)
    }

    override fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr, basePos: S_Pos, linkPos: S_Pos): C_Expr {
        val dbExpr = makeDbAttrExpr(base, attr)
        return V_DbExpr.createExpr(ctx, basePos, dbExpr)
    }

    override fun createIpEntityMemberExpr(
            ctx: C_ExprContext,
            baseValue: C_EntityAttrValueLike,
            baseExpr: Db_TableExpr,
            link: C_MemberLink
    ): C_Expr {
        val attrInfo = createAttrInfo(baseExpr, link)
        return baseValue.memberAttr(ctx, link, attrInfo)
    }

    private fun createAttrInfo(baseExpr: Db_TableExpr, link: C_MemberLink): C_DbAttrInfo {
        val dbExpr = makeDbAttrExpr(baseExpr, attr)
        return C_DbAttrInfo(rEntity, dbExpr, attr, link.linkPos, attrName)
    }
}

private class C_EntityAttrRef_Rowid(rEntity: R_EntityDefinition): C_EntityAttrRef(rEntity, ROWID_NAME) {
    override fun type() = ROWID_TYPE

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return Db_RowidExpr(baseExpr)
    }

    override fun createIpMemberExpr(ctx: C_ExprContext, link: C_MemberLink): V_Expr {
        val field = C_MemberAttr_SimpleReadOnly(R_MemberCalculator_Rowid, attrName)
        return makeMemberExpr(ctx, link, field)
    }

    override fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr, basePos: S_Pos, linkPos: S_Pos): C_Expr {
        val dbExpr = Db_RowidExpr(base)
        val memExpr = C_DbAttrInfo(base.rEntity, dbExpr, null, linkPos, attrName)
        return V_DbExpr.createExpr(ctx, basePos, memExpr.dbExpr)
    }

    override fun createIpEntityMemberExpr(
            ctx: C_ExprContext,
            baseValue: C_EntityAttrValueLike,
            baseExpr: Db_TableExpr,
            link: C_MemberLink
    ): C_Expr {
        val vExpr = createIpMemberExpr(ctx, link)
        return C_VExpr(vExpr)
    }
}

private class C_EntityAttrValueBase(
        val value: V_Expr,
        val safe: Boolean,
        val atEntity: R_DbAtEntity
)

interface C_EntityAttrValueLike {
    fun memberAttr(ctx: C_ExprContext, link: C_MemberLink, memAttrInfo: C_DbAttrInfo): C_Expr
}

class V_EntityAttrExpr private constructor(
        exprCtx: C_ExprContext,
        private val base: C_EntityAttrValueBase,
        private val parent: V_Expr,
        private val attrInfo: C_DbAttrInfo,
        private val resultType: R_Type,
        private val isDirectAtItemAttr: Boolean
): V_Expr(exprCtx, parent.pos), C_EntityAttrValueLike {
    private val atDependencies = base.value.atDependencies()

    override fun type() = resultType
    override fun isDb() = false // Important: value does not belong to an outer @-expression

    override fun atDependencies() = atDependencies

    override fun implicitAtWhereAttrName(): String? {
        return if (isDirectAtItemAttr) attrInfo.attr?.name else null
    }

    override fun implicitAtWhatAttrName(): String? {
        return if (isDirectAtItemAttr) attrInfo.attr?.name else null
    }

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
            throw C_Errors.errAttrNotMutable(attrInfo.linkPos, attrInfo.nameMsg)
        }
        exprCtx.checkDbUpdateAllowed(pos)
        return C_EntityAttrDestination(parent, attrInfo.rEntity, attrInfo.attr)
    }

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val memberRef = C_MemberRef(this, memberName, safe)
        val valueExpr = createDbMemberExpr(ctx, memberRef)
        val fnExpr = C_MemberResolver.functionForType(ctx, attrInfo.dbExpr.type, memberRef)

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
        val link = ref.toLink()
        return attrRef.createIpEntityMemberExpr(ctx, this, baseDbExpr, link)
    }

    override fun memberAttr(ctx: C_ExprContext, link: C_MemberLink, memAttrInfo: C_DbAttrInfo): C_Expr {
        val vExpr = create0(ctx, base, memAttrInfo, link, false)
        return C_VExpr(vExpr)
    }

    companion object {
        fun createIpMemberExpr(ctx: C_ExprContext, link: C_MemberLink, atEntity: R_DbAtEntity, memExpr: C_DbAttrInfo): V_Expr {
            val base = C_EntityAttrValueBase(link.base, link.safe, atEntity)
            val isDirectAtItemAttr = link.base.isAtExprItem()
            return create0(ctx, base, memExpr, link, isDirectAtItemAttr)
        }

        private fun create0(
                ctx: C_ExprContext,
                base: C_EntityAttrValueBase,
                memAttrInfo: C_DbAttrInfo,
                link: C_MemberLink,
                isDirectAtItemAttr: Boolean
        ): V_Expr {
            C_MemberResolver.checkNullAccess(link.base.type(), link.safe, link.linkPos, memAttrInfo.nameMsg)
            val resultType = C_Utils.effectiveMemberType(memAttrInfo.dbExpr.type, link.safe)
            return V_EntityAttrExpr(ctx, base, link.base, memAttrInfo, resultType, isDirectAtItemAttr)
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

private class C_MemberFunctionExpr(
        private val memberLink: C_MemberLink,
        private val fn: C_SysMemberFunction,
        private val fnName: String
): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = memberLink.base.pos

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

        val callCtx = C_MemberFuncCaseCtx(memberLink, fnName)
        return fn.compileCall(ctx, callCtx, cArgs.positional)
    }
}

class C_DbAttrInfo(
        val rEntity: R_EntityDefinition,
        val dbExpr: Db_Expr,
        val attr: R_Attribute?,
        val linkPos: S_Pos,
        val nameMsg: String
)

private fun makeDbAttrExpr(base: Db_TableExpr, attr: R_Attribute): Db_Expr {
    val resultType = attr.type
    val resultEntity = (resultType as? R_EntityType)?.rEntity
    return if (resultEntity == null) Db_AttrExpr(base, attr) else Db_RelExpr(base, attr, resultEntity)
}

private fun makeMemberExpr(ctx: C_ExprContext, link: C_MemberLink, memAttr: C_MemberAttr): V_Expr {
    val fieldType = memAttr.type
    val effectiveType = C_Utils.effectiveMemberType(fieldType, link.safe)
    val exprFacts = C_ExprVarFacts.of(postFacts = link.base.varFacts().postFacts)
    return V_MemberAttrExpr(ctx, link, memAttr, effectiveType, exprFacts)
}
