/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.C_BinOp_EqNe
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_QualifiedName
import net.postchain.rell.compiler.base.fn.C_FuncCaseCtx
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.R_BooleanType
import net.postchain.rell.model.R_CtErrorType
import net.postchain.rell.model.R_SysFunction
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.expr.*
import net.postchain.rell.model.stmt.R_ExprStatement
import net.postchain.rell.runtime.Rt_Error
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.LazyPosString
import net.postchain.rell.utils.LazyString

object C_ExprUtils {
    val ERROR_R_EXPR = errorRExpr()
    val ERROR_DB_EXPR = errorDbExpr()
    val ERROR_STATEMENT = R_ExprStatement(ERROR_R_EXPR)

    fun toDbExpr(errPos: S_Pos, rExpr: R_Expr): Db_Expr {
        val type = rExpr.type
        if (!type.sqlAdapter.isSqlCompatible()) {
            throw C_Errors.errExprNoDb(errPos, type)
        }
        return Db_InterpretedExpr(rExpr)
    }

    private fun makeDbBinaryExpr(type: R_Type, rOp: R_BinaryOp, dbOp: Db_BinaryOp, left: Db_Expr, right: Db_Expr): Db_Expr {
        return if (left is Db_InterpretedExpr && right is Db_InterpretedExpr) {
            val rExpr = R_BinaryExpr(type, rOp, left.expr, right.expr)
            Db_InterpretedExpr(rExpr)
        } else {
            Db_BinaryExpr(type, dbOp, left, right)
        }
    }

    fun makeDbBinaryExprEq(left: Db_Expr, right: Db_Expr): Db_Expr {
        return makeDbBinaryExpr(R_BooleanType, R_BinaryOp_Eq, Db_BinaryOp_Eq, left, right)
    }

    fun makeDbBinaryExprChain(type: R_Type, rOp: R_BinaryOp, dbOp: Db_BinaryOp, exprs: List<Db_Expr>): Db_Expr {
        return CommonUtils.foldSimple(exprs) { left, right -> makeDbBinaryExpr(type, rOp, dbOp, left, right) }
    }

    fun makeVBinaryExprEq(ctx: C_ExprContext, pos: S_Pos, left: V_Expr, right: V_Expr): V_Expr {
        val vOp = C_BinOp_EqNe.createVOp(true, left.type)
        return V_BinaryExpr(ctx, pos, vOp, left, right, C_ExprVarFacts.EMPTY)
    }

    fun createSysCallRExpr(type: R_Type, fn: R_SysFunction, args: List<R_Expr>, nameMsg: LazyPosString): R_Expr {
        return createSysCallRExpr(type, fn, args, nameMsg.pos, nameMsg.lazyStr)
    }

    fun createSysCallRExpr(type: R_Type, fn: R_SysFunction, args: List<R_Expr>, caseCtx: C_FuncCaseCtx): R_Expr {
        return createSysCallRExpr(type, fn, args, caseCtx.linkPos, caseCtx.qualifiedNameMsgLazy())
    }

    fun createSysCallRExpr(type: R_Type, fn: R_SysFunction, args: List<R_Expr>, pos: S_Pos, nameMsg: LazyString): R_Expr {
        val rCallTarget: R_FunctionCallTarget = R_FunctionCallTarget_SysGlobalFunction(fn, nameMsg)
        val filePos = pos.toFilePos()
        val rCallExpr: R_Expr = R_FullFunctionCallExpr(type, rCallTarget, filePos, listOf(), args, args.indices.toList())
        return R_StackTraceExpr(rCallExpr, filePos)
    }

    fun createSysGlobalPropExpr(
            exprCtx: C_ExprContext,
            type: R_Type,
            fn: R_SysFunction,
            qName: C_QualifiedName,
            pure: Boolean
    ): V_Expr {
        val nameStr = qName.str()
        return createSysGlobalPropExpr(exprCtx, type, fn, qName.pos, nameStr, pure)
    }

    fun createSysGlobalPropExpr(
            exprCtx: C_ExprContext,
            type: R_Type,
            fn: R_SysFunction,
            pos: S_Pos,
            nameMsg: String,
            pure: Boolean
    ): V_Expr {
        val nameMsgLazy = LazyString.of(nameMsg)
        val desc = V_SysFunctionTargetDescriptor(type, fn, null, nameMsgLazy, pure = pure, synth = true)
        val vCallTarget: V_FunctionCallTarget = V_FunctionCallTarget_SysGlobalFunction(desc)
        return V_FullFunctionCallExpr(exprCtx, pos, pos, type, vCallTarget, V_FunctionCallArgs.EMPTY)
    }

    fun errorRExpr(type: R_Type = R_CtErrorType, msg: String = "Compilation error"): R_Expr {
        return R_ErrorExpr(type, msg)
    }

    fun errorDbExpr(type: R_Type = R_CtErrorType, msg: String = "Compilation error"): Db_Expr {
        val rExpr = errorRExpr(type, msg)
        return Db_InterpretedExpr(rExpr)
    }

    fun errorVExpr(ctx: C_ExprContext, pos: S_Pos, type: R_Type = R_CtErrorType, msg: String = "Compilation error"): V_Expr {
        return V_ErrorExpr(ctx, pos, type, msg)
    }

    fun errorExpr(ctx: C_ExprContext, pos: S_Pos, type: R_Type = R_CtErrorType, msg: String = "Compilation error"): C_Expr {
        val value = errorVExpr(ctx, pos, type, msg)
        return C_ValueExpr(value)
    }

    fun errorMember(ctx: C_ExprContext, pos: S_Pos): C_ExprMember {
        return C_ExprMember(errorExpr(ctx, pos), IdeSymbolInfo.UNKNOWN)
    }

    fun <T> evaluate(pos: S_Pos, code: () -> T): T {
        try {
            val v = code()
            return v
        } catch (e: Rt_Error) {
            throw C_Error.stop(pos, "eval_fail:${e.code}", e.message ?: "Evaluation failed")
        } catch (e: Throwable) {
            throw C_Error.stop(pos, "eval_fail:${e.javaClass.canonicalName}", "Evaluation failed")
        }
    }
}
