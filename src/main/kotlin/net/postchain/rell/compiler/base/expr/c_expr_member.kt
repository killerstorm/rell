/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.fn.*
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_ExprInfo
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.*
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.LazyString
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList

class C_MemberRef(val base: V_Expr, val name: C_Name, val safe: Boolean) {
    fun toLink() = C_MemberLink(base, safe, name.pos)
}

class C_MemberLink(val base: V_Expr, val safe: Boolean, val linkPos: S_Pos)

abstract class C_TypeValueMember(val name: R_Name, val valueType: R_Type?) {
    fun nameMsg(): C_CodeMsg = name.str toCodeMsg name.str
    abstract fun kindMsg(): String
    open fun isCallable(): Boolean = valueType is R_FunctionType
    abstract fun compile(ctx: C_ExprContext, link: C_MemberLink): C_ExprMember
}

class C_TypeValueMember_BasicAttr(
    name: R_Name,
    private val attr: C_MemberAttr,
    private val ideInfo: IdeSymbolInfo,
): C_TypeValueMember(name, attr.type) {
    override fun kindMsg() = "attribute"

    override fun compile(ctx: C_ExprContext, link: C_MemberLink): C_ExprMember {
        val fieldType = attr.type
        val effectiveType = C_Utils.effectiveMemberType(fieldType, link.safe)
        val vExpr: V_Expr = V_MemberAttrExpr(ctx, link, attr, effectiveType)
        val cExpr = C_ValueExpr(vExpr)
        return C_ExprMember(cExpr, ideInfo)
    }
}

class C_TypeValueMember_Function(
    name: R_Name,
    private val baseType: R_Type,
    private val fn: C_SysMemberFunction
): C_TypeValueMember(name, null) {
    override fun isCallable() = true
    override fun kindMsg() = "function"

    override fun compile(ctx: C_ExprContext, link: C_MemberLink): C_ExprMember {
        val fullName = C_Utils.getFullNameLazy(baseType, name)
        val expr: C_Expr = C_MemberFunctionExpr(link, fn, name, fullName)
        return C_ExprMember(expr, fn.ideInfo)
    }

    private class C_MemberFunctionExpr(
        private val memberLink: C_MemberLink,
        private val fn: C_SysMemberFunction,
        private val fnName: R_Name,
        private val fullName: LazyString,
    ): C_NoValueExpr() {
        override fun startPos() = memberLink.base.pos
        override fun isCallable() = true

        override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
            val callTarget = C_FunctionCallTarget_MemberFunction(ctx)
            val vExpr = C_FunctionCallArgsUtils.compileCall(ctx, args, resTypeHint, callTarget)
            vExpr ?: return C_ExprUtils.errorExpr(ctx, pos)
            return C_ValueExpr(vExpr)
        }

        override fun errKindName() = "member_function" to fullName.value

        private inner class C_FunctionCallTarget_MemberFunction(val ctx: C_ExprContext): C_FunctionCallTarget() {
            override fun retType() = null
            override fun typeHints() = fn.getParamsHints()
            override fun hasParameter(name: R_Name) = false

            override fun compileFull(args: C_FullCallArguments): V_Expr? {
                val vArgs = args.compileSimpleArgs(fullName)
                val callCtx = C_MemberFuncCaseCtx(memberLink, fnName, fullName)
                return fn.compileCallFull(ctx, callCtx, vArgs)
            }

            override fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_Expr? {
                val callCtx = C_MemberFuncCaseCtx(memberLink, fnName, fullName)
                return fn.compileCallPartial(ctx, callCtx, args, resTypeHint)
            }
        }
    }
}

abstract class C_MemberAttr(val type: R_Type) {
    abstract fun attrName(): R_Name?
    abstract fun nameMsg(): C_CodeMsg
    abstract fun calculator(): R_MemberCalculator
    abstract fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr
    open fun hasDbExpr(): Boolean = false
    open fun dbExpr(base: Db_Expr): Db_Expr? = null
}

abstract class C_MemberAttr_TupleAttr(
        type: R_Type,
        protected val fieldIndex: Int,
        protected val field: R_TupleField
): C_MemberAttr(type) {
    final override fun attrName() = field.name

    final override fun nameMsg(): C_CodeMsg {
        return if (field.name != null) {
            field.name.str toCodeMsg field.name.str
        } else {
            "$fieldIndex" toCodeMsg "[$fieldIndex]"
        }
    }

    final override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(pos)
}

abstract class C_MemberAttr_StructAttr(type: R_Type, protected val attr: R_Attribute): C_MemberAttr(type) {
    final override fun attrName() = attr.rName
    final override fun nameMsg() = attr.rName.str toCodeMsg attr.rName.str
}

class C_MemberAttr_SysProperty(
    private val name: R_Name,
    private val prop: C_SysMemberProperty,
): C_MemberAttr(prop.type) {
    override fun attrName() = name
    override fun nameMsg() = name.str toCodeMsg name.str

    override fun calculator(): R_MemberCalculator {
        return R_MemberCalculator_SysProperty(prop)
    }

    override fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr {
        throw C_Errors.errAttrNotMutable(pos, name.str)
    }

    override fun hasDbExpr() = prop.fn.dbFn != null

    override fun dbExpr(base: Db_Expr): Db_Expr? {
        val dbFn = prop.fn.dbFn
        dbFn ?: return null
        return Db_CallExpr(prop.type, dbFn, immListOf(base))
    }

    private class R_MemberCalculator_SysProperty(private val prop: C_SysMemberProperty): R_MemberCalculator(prop.type) {
        override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
            val args = immListOf(baseValue)
            return prop.fn.rFn.call(frame.defCtx.callCtx, args)
        }
    }
}

class V_MemberAttrExpr(
        exprCtx: C_ExprContext,
        private val memberLink: C_MemberLink,
        private val memberAttr: C_MemberAttr,
        private val resType: R_Type
): V_Expr(exprCtx, memberLink.base.pos) {
    override fun exprInfo0() = V_ExprInfo.simple(resType, memberLink.base, canBeDbExpr = memberAttr.hasDbExpr())

    override fun implicitAtWhereAttrName(): R_Name? {
        val isAt = memberLink.base.isAtExprItem()
        return if (isAt) memberAttr.attrName() else null
    }

    override fun implicitAtWhatAttrName(): R_Name? {
        val isAt = memberLink.base.isAtExprItem()
        return if (isAt) memberAttr.attrName() else null
    }

    override fun toRExpr0(): R_Expr {
        val rBase = memberLink.base.toRExpr()
        val calculator = memberAttr.calculator()
        return R_MemberExpr(rBase, memberLink.safe, calculator)
    }

    override fun toDbExpr0(): Db_Expr {
        val dbBase = memberLink.base.toDbExpr()
        val dbExpr = memberAttr.dbExpr(dbBase)
        return if (dbExpr != null) dbExpr else {
            val rExpr = toRExpr()
            C_ExprUtils.toDbExpr(exprCtx.msgCtx, memberLink.linkPos, rExpr)
        }
    }

    override fun toDbExprWhat0(): C_DbAtWhatValue {
        val calculator = memberAttr.calculator()
        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_AtWhatItem>): Rt_Value {
                checkEquals(values.size, 1)
                val baseValue = values[0].value()
                return calculator.calculate(frame, baseValue)
            }
        }
        return C_DbAtWhatValue_Complex(immListOf(memberLink.base), evaluator)
    }

    override fun destination(): C_Destination {
        val rBase = memberLink.base.toRExpr()
        val rDstExpr = memberAttr.destination(memberLink.linkPos, rBase)
        return C_Destination_Simple(rDstExpr)
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        if (memberLink.safe && memberAttr.type !is R_NullableType && resType == R_NullableType(memberAttr.type)) {
            return callCommon(ctx, pos, args, resTypeHint, memberAttr.type, true)
        } else {
            return super.call(ctx, pos, args, resTypeHint)
        }
    }
}

sealed class C_EntityAttrRef(
        val rEntity: R_EntityDefinition,
        val attrName: R_Name,
        val type: R_Type,
) {
    abstract fun attribute(): R_Attribute?
    abstract fun ideSymbolInfo(): IdeSymbolInfo
    abstract fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr
    abstract fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr): Db_Expr

    companion object {
        private const val ROWID_NAME = "rowid"
        val ROWID_RNAME = R_Name.of(ROWID_NAME)
        val ROWID_TYPE: R_Type = R_RowidType
        val ROWID_NAME_INFO = IdeSymbolInfo(IdeSymbolKind.MEM_ENTITY_ATTR_ROWID)

        fun isAllowedRegularAttrName(name: String) = name != ROWID_NAME

        fun create(rEntity: R_EntityDefinition, attr: R_Attribute): C_EntityAttrRef {
            return C_EntityAttrRef_Regular(rEntity, attr)
        }

        fun getEntityAttrs(rEntity: R_EntityDefinition): List<C_EntityAttrRef> {
            val rowid = immListOf(C_EntityAttrRef_Rowid(rEntity))
            val attrs = rEntity.attributes.values.map { C_EntityAttrRef_Regular(rEntity, it) }
            return (rowid + attrs).toImmList()
        }
    }
}

private class C_EntityAttrRef_Regular(
        rEntity: R_EntityDefinition,
        private val attr: R_Attribute,
): C_EntityAttrRef(rEntity, attr.rName, attr.type) {
    override fun attribute() = attr
    override fun ideSymbolInfo() = attr.ideInfo

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return makeDbAttrExpr(baseExpr)
    }

    override fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr): Db_Expr {
        return makeDbAttrExpr(base)
    }

    private fun makeDbAttrExpr(base: Db_TableExpr): Db_Expr {
        val resultType = attr.type
        val resultEntity = (resultType as? R_EntityType)?.rEntity
        return if (resultEntity == null) Db_AttrExpr(base, attr) else Db_RelExpr(base, attr, resultEntity)
    }
}

private class C_EntityAttrRef_Rowid(rEntity: R_EntityDefinition): C_EntityAttrRef(rEntity, ROWID_RNAME, ROWID_TYPE) {
    override fun attribute() = null
    override fun ideSymbolInfo() = ROWID_NAME_INFO

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return Db_RowidExpr(baseExpr)
    }

    override fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr): Db_Expr {
        return Db_RowidExpr(base)
    }
}
