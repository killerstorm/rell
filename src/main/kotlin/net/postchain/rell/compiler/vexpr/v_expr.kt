/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.core.C_VarUid
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.fn.C_FunctionCallInfo
import net.postchain.rell.compiler.base.fn.C_FunctionCallTarget_FunctionType
import net.postchain.rell.compiler.base.fn.C_FunctionUtils
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.Db_Expr
import net.postchain.rell.model.expr.R_BlockCheckExpr
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.model.expr.R_StackTraceExpr
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.immSetOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmSet

class V_ExprInfo(
        val type: R_Type,
        subExprs: List<V_Expr>,
        val hasDbModifications: Boolean = false,
        val canBeDbExpr: Boolean = true,
        val dependsOnDbAtEntity: Boolean = false,
        dependsOnAtExprs: Set<R_AtExprId> = immSetOf()
) {
    val subExprs = subExprs.toImmList()
    val dependsOnAtExprs = dependsOnAtExprs.toImmSet()

    companion object {
        fun simple(
                type: R_Type,
                vararg subExprs: V_Expr,
                hasDbModifications: Boolean = false,
                canBeDbExpr: Boolean = true,
                dependsOnDbAtEntity: Boolean = false
        ): V_ExprInfo {
            return simple(
                    type,
                    subExprs.toImmList(),
                    hasDbModifications = hasDbModifications,
                    canBeDbExpr = canBeDbExpr,
                    dependsOnDbAtEntity = dependsOnDbAtEntity
            )
        }

        fun simple(
                type: R_Type,
                subExprs: List<V_Expr>,
                hasDbModifications: Boolean = false,
                canBeDbExpr: Boolean = true,
                dependsOnDbAtEntity: Boolean = false
        ): V_ExprInfo {
            val depsOnDbAtEnt = dependsOnDbAtEntity || subExprs.any { it.info.dependsOnDbAtEntity }
            val canBeDb = !depsOnDbAtEnt || (canBeDbExpr && subExprs.all { it.info.canBeDbExpr })
            return V_ExprInfo(
                    type = type,
                    subExprs = subExprs,
                    hasDbModifications = hasDbModifications || subExprs.any { it.info.hasDbModifications },
                    canBeDbExpr = canBeDb,
                    dependsOnDbAtEntity = depsOnDbAtEnt,
                    dependsOnAtExprs = subExprs.flatMap { it.info.dependsOnAtExprs }.toImmSet()
            )
        }
    }
}

class V_ConstantValueEvalContext {
    private val constIds = mutableSetOf<R_GlobalConstantId>()

    fun <T> addConstId(constId: R_GlobalConstantId, code: () -> T): T? {
        if (!constIds.add(constId)) {
            return null
        }
        try {
            return code()
        } finally {
            constIds.remove(constId)
        }
    }
}

class V_GlobalConstantRestriction(val code: String, val msg: String?)

abstract class V_Expr(protected val exprCtx: C_ExprContext, val pos: S_Pos) {
    protected val msgCtx = exprCtx.msgCtx

    val info: V_ExprInfo by lazy {
        exprInfo0()
    }

    val type: R_Type by lazy {
        info.type
    }

    val varFacts: C_ExprVarFacts by lazy {
        varFacts0()
    }

    protected abstract fun exprInfo0(): V_ExprInfo

    protected open fun varFacts0(): C_ExprVarFacts {
        return C_ExprVarFacts.forSubExpressions(info.subExprs)
    }

    protected abstract fun toRExpr0(): R_Expr
    protected open fun toDbExpr0(): Db_Expr = throw C_Errors.errExprDbNotAllowed(pos)

    fun toRExpr(): R_Expr {
        var rExpr = toRExpr0()
        val filePos = pos.toFilePos()
        rExpr = R_StackTraceExpr(rExpr, filePos)
        if (exprCtx.globalCtx.compilerOptions.blockCheck) {
            rExpr = R_BlockCheckExpr(rExpr, exprCtx.blkCtx.blockUid)
        }
        return rExpr
    }

    fun toDbExpr(): Db_Expr {
        if (info.dependsOnDbAtEntity) {
            return toDbExpr0()
        }
        val rExpr = toRExpr()
        return C_ExprUtils.toDbExpr(exprCtx.msgCtx, pos, rExpr)
    }

    fun toDbExprWhat(): C_DbAtWhatValue {
        return if (info.canBeDbExpr && type.sqlAdapter.isSqlCompatible()) {
            toDbExprWhatDirect()
        } else {
            toDbExprWhat0()
        }
    }

    protected open fun toDbExprWhat0(): C_DbAtWhatValue {
        return toDbExprWhatDirect()
    }

    private fun toDbExprWhatDirect(): C_DbAtWhatValue {
        val dbExpr = toDbExpr()
        return C_DbAtWhatValue_Simple(dbExpr)
    }

    open fun destination(): C_Destination {
        throw C_Errors.errBadDestination(pos)
    }

    fun member(ctx: C_ExprContext, memberName: C_Name, safe: Boolean, exprHint: C_ExprHint): C_ExprMember {
        if (safe) {
            if (asNullable().type !is R_NullableType && type.isNotError()) {
                val typeStr = type.strCode()
                ctx.msgCtx.error(memberName.pos, "expr_safemem_type:[$typeStr]", "Wrong type for operator '?.': $typeStr")
            }
        } else {
            if (type is R_NullableType) {
                val nameStr = memberName.str
                val msg = "Cannot access member '$nameStr' of nullable type ${type.str()}"
                ctx.msgCtx.error(memberName.pos, "expr_mem_null:$nameStr", msg)
            }
        }

        val member = C_TypeValueMember.getTypeMember(ctx, type, memberName, exprHint)
        member ?: return C_ExprUtils.errorMember(ctx, memberName.pos)

        return member0(ctx, memberName, member, safe && (type is R_NullableType), exprHint)
    }

    protected open fun member0(
        ctx: C_ExprContext,
        memberName: C_Name,
        memberValue: C_TypeValueMember,
        safe: Boolean,
        exprHint: C_ExprHint,
    ): C_ExprMember {
        val link = C_MemberLink(this, memberName.pos, safe)
        return memberValue.compile(ctx, link)
    }

    open fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        return callCommon(ctx, pos, args, resTypeHint, type, false)
    }

    protected fun callCommon(
            ctx: C_ExprContext,
            pos: S_Pos,
            args: List<S_CallArgument>,
            resTypeHint: C_TypeHint,
            type: R_Type,
            safe: Boolean
    ): V_Expr {
        if (type is R_FunctionType) {
            val callInfo = C_FunctionCallInfo.forFunctionType(pos, type)
            val callTarget = C_FunctionCallTarget_FunctionType(ctx, callInfo, this, type, safe)
            val vCall = C_FunctionUtils.compileRegularCall(ctx, callInfo, callTarget, args, resTypeHint)
            return vCall.vExpr()
        }

        // Validate args.
        args.forEachIndexed { index, arg ->
            ctx.msgCtx.consumeError {
                arg.compile(ctx, index, true, C_CallTypeHints_None, C_CallArgumentIdeInfoProvider_Unknown)
            }
        }

        if (type == R_CtErrorType) {
            return C_ExprUtils.errorVExpr(ctx, pos)
        } else {
            val typeStr = type.strCode()
            throw C_Error.stop(pos, "expr_call_nofn:$typeStr", "Not a function: value of type $typeStr")
        }
    }

    fun traverse(code: (V_Expr) -> Boolean) {
        val more = code(this)
        if (more) {
            for (expr in info.subExprs) {
                expr.traverse(code)
            }
        }
    }

    fun <T> traverseToSet(code: (V_Expr) -> Collection<T>): Set<T> {
        val res = mutableListOf<T>()
        traverseToCollection(res, code)
        return res.toImmSet()
    }

    private fun <T> traverseToCollection(res: MutableCollection<T>, code: (V_Expr) -> Collection<T>) {
        traverse {
            val l = code(it)
            res.addAll(l)
            true
        }
    }

    open fun constantValue(ctx: V_ConstantValueEvalContext): Rt_Value? = null

    open fun isAtExprItem(): Boolean = false
    open fun implicitTargetAttrName(): R_Name? = null
    open fun implicitAtWhereAttrName(): R_Name? = implicitTargetAttrName()
    open fun implicitAtWhatAttrName(): R_Name? = null
    open fun varId(): C_VarUid? = null
    open fun globalConstantId(): R_GlobalConstantId? = null
    open fun globalConstantRestriction(): V_GlobalConstantRestriction? = null
    open fun asNullable(): V_Expr = this
}
