/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_VirtualType
import net.postchain.rell.compiler.vexpr.*
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
        val fieldType = attr.type
        val effectiveType = C_Utils.effectiveMemberType(fieldType, link.safe)
        return V_MemberAttrExpr(ctx, link, attr, effectiveType)
    }
}

private class C_MemberValue_EntityAttr(private val attr: C_EntityAttrRef): C_MemberValue() {
    override fun type() = attr.type()
    override fun memberName() = C_MemberName_Name(attr.attrName)

    override fun compile(ctx: C_ExprContext, link: C_MemberLink): V_Expr {
        C_MemberResolver.checkNullAccess(link.base.type, link.safe, link.linkPos, attr.attrName)
        val resultType = C_Utils.effectiveMemberType(attr.type(), link.safe)
        return V_EntityAttrExpr(ctx, link, attr, resultType)
    }
}

private class C_MemberValue_EnumAttr(
        private val prop: C_SysMemberProperty,
        private val propName: String
): C_MemberValue() {
    override fun type() = prop.type
    override fun memberName() = C_MemberName_Name(propName)

    override fun compile(ctx: C_ExprContext, link: C_MemberLink): V_Expr {
        val caseCtx = C_MemberFuncCaseCtx(link, propName)

        val pos = caseCtx.linkPos
        val effResType = C_Utils.effectiveMemberType(prop.type, link.safe)

        val fullName = caseCtx.qualifiedNameMsg()
        val desc = V_SysFunctionTargetDescriptor(prop.type, prop.rFn, prop.dbFn, fullName, pure = prop.pure, synth = true)
        val callTarget = V_FunctionCallTarget_SysMemberFunction(desc, caseCtx.member)

        var res: V_Expr = V_FullFunctionCallExpr(ctx, pos, pos, effResType, callTarget, V_FunctionCallArgs.EMPTY)

        if (caseCtx.member.base.isAtExprItem()) {
            // Wrap just to add implicit what-expr name.
            res = V_SysMemberPropertyExpr(ctx, res, propName)
        }

        return res
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
                    .filter { it.value.type == memberType }
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

private class V_MemberAttrExpr(
        exprCtx: C_ExprContext,
        private val memberLink: C_MemberLink,
        private val memberAttr: C_MemberAttr,
        private val resType: R_Type
): V_Expr(exprCtx, memberLink.base.pos) {
    override fun exprInfo0() = V_ExprInfo.simple(resType, memberLink.base)

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

    override fun destination(): C_Destination {
        val rBase = memberLink.base.toRExpr()
        val rDstExpr = memberAttr.destination(memberLink.linkPos, rBase)
        return C_SimpleDestination(rDstExpr)
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        if (memberLink.safe && memberAttr.type !is R_NullableType && resType == R_NullableType(memberAttr.type)) {
            return callCommon(ctx, pos, args, resTypeHint, memberAttr.type, true)
        } else {
            return super.call(ctx, pos, args, resTypeHint)
        }
    }
}

sealed class C_EntityAttrRef(val rEntity: R_EntityDefinition, val attrName: String) {
    abstract fun type(): R_Type
    abstract fun attribute(): R_Attribute?
    abstract fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr
    abstract fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr): Db_Expr

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

private class C_EntityAttrRef_Regular(
        rEntity: R_EntityDefinition,
        private val attr: R_Attribute
): C_EntityAttrRef(rEntity, attr.name) {
    override fun type() = attr.type
    override fun attribute() = attr

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return makeDbAttrExpr(baseExpr, attr)
    }

    override fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr): Db_Expr {
        return makeDbAttrExpr(base, attr)
    }
}

private class C_EntityAttrRef_Rowid(rEntity: R_EntityDefinition): C_EntityAttrRef(rEntity, ROWID_NAME) {
    override fun type() = ROWID_TYPE
    override fun attribute() = null

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return Db_RowidExpr(baseExpr)
    }

    override fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr): Db_Expr {
        return Db_RowidExpr(base)
    }
}

private class C_MemberFunctionExpr(
        private val memberLink: C_MemberLink,
        private val fn: C_SysMemberFunction,
        private val fnName: String
): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = memberLink.base.pos
    override fun isCallable() = true

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val callTarget = C_FunctionCallTarget_MemberFunction(ctx)
        val vExpr = C_FunctionCallArgsUtils.compileCall(ctx, args, resTypeHint, callTarget)
        vExpr ?: return C_Utils.errorExpr(ctx, pos)
        return C_VExpr(vExpr)
    }

    private inner class C_FunctionCallTarget_MemberFunction(val ctx: C_ExprContext): C_FunctionCallTarget() {
        override fun retType() = null
        override fun typeHints() = fn.getParamsHints()

        override fun compileFull(args: C_FullCallArguments): V_Expr? {
            val vArgs = args.compileSimpleArgs(fnName)
            vArgs ?: return null
            val callCtx = C_MemberFuncCaseCtx(memberLink, fnName)
            return fn.compileCallFull(ctx, callCtx, vArgs)
        }

        override fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_Expr? {
            val callCtx = C_MemberFuncCaseCtx(memberLink, fnName)
            return fn.compileCallPartial(ctx, callCtx, args, resTypeHint)
        }
    }
}

private fun makeDbAttrExpr(base: Db_TableExpr, attr: R_Attribute): Db_Expr {
    val resultType = attr.type
    val resultEntity = (resultType as? R_EntityType)?.rEntity
    return if (resultEntity == null) Db_AttrExpr(base, attr) else Db_RelExpr(base, attr, resultEntity)
}
