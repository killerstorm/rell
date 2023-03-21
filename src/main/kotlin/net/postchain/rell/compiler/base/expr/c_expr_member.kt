/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.core.C_Types
import net.postchain.rell.compiler.base.fn.*
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_MemberFunctionCall
import net.postchain.rell.compiler.vexpr.V_TypeValueMember
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.*
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList

class C_MemberLink(val base: V_Expr, val linkPos: S_Pos, val linkName: C_Name?, val safe: Boolean)

abstract class C_TypeValueMember(
    val ideName: R_IdeName?,
    val valueType: R_Type?,
) {
    abstract fun kindMsg(): String
    abstract fun nameMsg(): C_CodeMsg

    open fun isCallable(): Boolean = valueType is R_FunctionType

    open fun value(ctx: C_ExprContext, linkPos: S_Pos, linkName: C_Name?): V_TypeValueMember {
        throw C_NoValueExpr.errNoValue(linkPos, kindMsg(), nameMsg().msg)
    }

    open fun call(ctx: C_ExprContext, linkPos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_TypeValueMember? = null

    fun compile(ctx: C_ExprContext, link: C_MemberLink): C_ExprMember {
        val expr: C_Expr = C_ValueMemberExpr(ctx, link, this)
        val ideInfo = ideName?.ideInfo ?: IdeSymbolInfo.UNKNOWN
        return C_ExprMember(expr, ideInfo)
    }

    companion object {
        fun getTypeMember(ctx: C_ExprContext, baseType: R_Type, memberName: C_Name, hint: C_ExprHint): C_TypeValueMember? {
            val effectiveBaseType = C_Types.removeNullable(baseType)

            val members = effectiveBaseType.getValueMembers(memberName.rName)
            if (members.isEmpty()) {
                C_Errors.errUnknownMember(ctx.msgCtx, effectiveBaseType, memberName)
                return null
            }

            var filteredMembers = members.filter { if (hint.callable) it.isCallable() else it.valueType != null }
            if (filteredMembers.isEmpty()) {
                filteredMembers = members
            }

            if (filteredMembers.size > 1) {
                val kinds = filteredMembers.map { it.kindMsg() }
                val listCode = kinds.joinToString(",")
                val listMsg = kinds.joinToString()
                ctx.msgCtx.error(memberName.pos, "name:ambig:$memberName:[$listCode]", "Name '$memberName' is ambiguous: $listMsg")
            }

            return filteredMembers[0]
        }
    }
}

class C_TypeValueMember_BasicAttr(
    private val attr: C_MemberAttr,
): C_TypeValueMember(attr.ideName, attr.type) {
    override fun kindMsg() = "attribute"
    override fun nameMsg() = attr.nameMsg()

    override fun value(ctx: C_ExprContext, linkPos: S_Pos, linkName: C_Name?): V_TypeValueMember {
        return V_TypeValueMember_BasicAttr(attr, linkPos, linkName)
    }

    private class V_TypeValueMember_BasicAttr(
        private val attr: C_MemberAttr,
        private val memberPos: S_Pos,
        private val memberName: C_Name?,
    ): V_TypeValueMember(attr.type) {
        override fun implicitAttrName() = memberName
        override fun ideInfo() = attr.ideName?.ideInfo ?: IdeSymbolInfo.UNKNOWN
        override fun vExprs() = immListOf<V_Expr>()
        override fun calculator() = attr.calculator()

        override fun destination(base: V_Expr): C_Destination {
            val rBase = base.toRExpr()
            val rDstExpr = attr.destination(memberPos, rBase)
            return C_Destination_Simple(rDstExpr)
        }

        override fun canBeDbExpr() = attr.canBeDbExpr()
        override fun dbExpr(base: Db_Expr) = attr.dbExpr(base)
    }
}

class C_TypeValueMember_Function(
    private val realName: R_Name,
    private val baseType: R_Type,
    private val fn: C_SysMemberFunction
): C_TypeValueMember(R_IdeName(realName, fn.ideInfo), null) {
    override fun kindMsg() = "function"
    override fun nameMsg() = realName.str toCodeMsg realName.str
    override fun isCallable() = true

    override fun call(ctx: C_ExprContext, linkPos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_TypeValueMember {
        val callTargetInfo = C_FunctionCallTargetInfo_MemberFunction(fn)
        val callArgs = C_FunctionCallArgsUtils.compileCallArgs(ctx, args, callTargetInfo)
        val fullName = C_Utils.getFullNameLazy(baseType, realName)

        val vCall = when (callArgs) {
            null -> null
            is C_FullCallArguments -> {
                val vArgs = callArgs.compileSimpleArgs(fullName)
                val callCtx = C_MemberFuncCaseCtx(linkPos, realName, fullName)
                fn.compileCallFull(ctx, callCtx, vArgs)
            }
            is C_PartialCallArguments -> {
                val callCtx = C_MemberFuncCaseCtx(linkPos, realName, fullName)
                val fnType = resTypeHint.getFunctionType()
                fn.compileCallPartial(ctx, callCtx, callArgs, fnType)
            }
        }

        vCall ?: return C_ExprUtils.errorVMember(linkPos)

        val retType = vCall.returnType()
        return V_TypeValueMember_FunctionCall(retType, vCall, linkPos, fn.ideInfo)
    }

    private class V_TypeValueMember_FunctionCall(
        type: R_Type,
        private val call: V_MemberFunctionCall,
        private val memberPos: S_Pos,
        private val ideInfo: IdeSymbolInfo,
    ): V_TypeValueMember(type) {
        override fun implicitAttrName() = null
        override fun ideInfo() = ideInfo
        override fun vExprs() = call.vExprs()
        override fun globalConstantRestriction() = call.globalConstantRestriction()
        override fun safeCallable() = false

        override fun calculator() = call.calculator()
        override fun destination(base: V_Expr) = throw C_Errors.errBadDestination(memberPos)

        override fun canBeDbExpr() = call.canBeDbExpr()
        override fun dbExpr(base: Db_Expr) = call.dbExpr(base)
        override fun dbExprWhat(base: V_Expr, safe: Boolean) = call.dbExprWhat(base, safe)
    }

    private class C_FunctionCallTargetInfo_MemberFunction(val fn: C_SysMemberFunction): C_FunctionCallTargetInfo() {
        override fun retType() = null
        override fun typeHints() = fn.getParamsHints()
        override fun getParameter(name: R_Name) = null
    }
}

abstract class C_MemberAttr(val ideName: R_IdeName?, val type: R_Type) {
    abstract fun nameMsg(): C_CodeMsg
    abstract fun calculator(): R_MemberCalculator
    abstract fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr
    open fun canBeDbExpr(): Boolean = false
    open fun dbExpr(base: Db_Expr): Db_Expr? = null
}

abstract class C_MemberAttr_TupleAttr(
        type: R_Type,
        protected val fieldIndex: Int,
        protected val field: R_TupleField
): C_MemberAttr(field.name, type) {
    final override fun nameMsg(): C_CodeMsg {
        return if (field.name != null) {
            field.name.str toCodeMsg field.name.str
        } else {
            "$fieldIndex" toCodeMsg "[$fieldIndex]"
        }
    }

    final override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(pos)
}

abstract class C_MemberAttr_StructAttr(type: R_Type, protected val attr: R_Attribute): C_MemberAttr(attr.ideName, type) {
    final override fun nameMsg() = attr.rName.str toCodeMsg attr.rName.str
}

class C_MemberAttr_SysProperty(
    ideName: R_IdeName,
    private val prop: C_SysMemberProperty,
): C_MemberAttr(ideName, prop.type) {
    private val name = ideName.rName

    override fun nameMsg() = name.str toCodeMsg name.str

    override fun calculator(): R_MemberCalculator {
        return R_MemberCalculator_SysProperty(prop)
    }

    override fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr {
        throw C_Errors.errAttrNotMutable(pos, name.str)
    }

    override fun canBeDbExpr() = prop.fn.dbFn != null

    override fun dbExpr(base: Db_Expr): Db_Expr? {
        val dbFn = prop.fn.dbFn
        dbFn ?: return null
        return Db_CallExpr(prop.type, dbFn, immListOf(base))
    }

    private class R_MemberCalculator_SysProperty(private val prop: C_SysMemberProperty): R_MemberCalculator(prop.type) {
        override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
            val args = immListOf(baseValue)
            val callCtx = frame.callCtx()
            return prop.fn.rFn.call(callCtx, args)
        }
    }
}

sealed class C_EntityAttrRef(
        val rEntity: R_EntityDefinition,
        val ideName: R_IdeName,
        val type: R_Type,
) {
    val attrName = ideName.rName

    abstract fun attribute(): R_Attribute?
    abstract fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr
    abstract fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr): Db_Expr

    companion object {
        private const val ROWID_NAME = "rowid"
        val ROWID_IDENAME = C_LibUtils.ideName(ROWID_NAME, IdeSymbolKind.MEM_ENTITY_ATTR_ROWID)
        val ROWID_TYPE: R_Type = R_RowidType

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
): C_EntityAttrRef(rEntity, attr.ideName, attr.type) {
    override fun attribute() = attr

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

private class C_EntityAttrRef_Rowid(rEntity: R_EntityDefinition): C_EntityAttrRef(rEntity, ROWID_IDENAME, ROWID_TYPE) {
    override fun attribute() = null

    override fun createDbContextAttrExpr(baseExpr: Db_TableExpr): Db_Expr {
        return Db_RowidExpr(baseExpr)
    }

    override fun createDbMemberExpr(ctx: C_ExprContext, base: Db_TableExpr): Db_Expr {
        return Db_RowidExpr(base)
    }
}
