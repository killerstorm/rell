package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Expr
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.vexpr.V_DbExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_RExpr
import net.postchain.rell.model.*
import net.postchain.rell.utils.checkEquals

class C_SysFn_Exists(private val not: Boolean): C_SpecialSysGlobalFunction() {
    override fun paramCount() = 1

    override fun compileCall0(ctx: C_ExprContext, name: S_Name, args: List<S_Expr>): C_Expr {
        checkEquals(1, args.size)

        val arg = args[0]

        val atCtx = ctx.dbAtCtx

        val vExpr = if (atCtx != null) {
            val cMaybeAt = arg.compileNestedAt(ctx, atCtx)
            when (cMaybeAt) {
                is C_MaybeNestedAt_No -> compileGeneralExpr(ctx, name, cMaybeAt.cExpr)
                is C_MaybeNestedAt_Yes -> compileNestedAt(ctx, name, cMaybeAt.vExpr)
            }
        } else {
            val cArg = arg.compile(ctx)
            compileGeneralExpr(ctx, name, cArg)
        }

        return C_VExpr(vExpr)
    }

    private fun compileGeneralExpr(ctx: C_ExprContext, name: S_Name, cArg: C_Expr): V_Expr {
        val arg = cArg.value()
        val condition = compileCondition(arg)
        if (condition == null) {
            C_FuncMatchUtils.errNoMatch(ctx, name.pos, name.str, listOf(arg.type()))
            return C_Utils.errorVExpr(ctx, name.pos, R_BooleanType)
        }

        val fn = R_SysFn_General.Exists(condition, not)
        val rArgs = listOf(arg.toRExpr())
        val rExpr = C_Utils.createSysCallExpr(R_BooleanType, fn, rArgs, listOf(name))

        val preFacts = C_ExprVarFacts.forSubExpressions(listOf(arg))
        val varFacts = preFacts.and(C_ExprVarFacts.forNullCheck(arg, not))
        return V_RExpr(ctx, name.pos, rExpr, varFacts)
    }

    private fun compileCondition(arg: V_Expr): R_RequireCondition? {
        val argType = arg.type()

        val (_, condition) = C_SysFn_Require_Collection.getCondition(argType)
        if (condition != null) {
            return condition
        }

        val argN = arg.asNullable()
        val argTypeN = argN.type()
        if (argTypeN is R_NullableType) {
            return R_RequireCondition_Nullable
        }

        return null
    }

    private fun compileNestedAt(ctx: C_ExprContext, name: S_Name, vAtExpr: V_Expr): V_Expr {
        val atType = vAtExpr.type()
        if (atType !is R_ListType) {
            C_FuncMatchUtils.errNoMatch(ctx, name.pos, name.str, listOf(atType))
            return C_Utils.errorVExpr(ctx, name.pos, R_BooleanType)
        }

        val dbAtExpr = vAtExpr.toDbExpr()
        val dbResExpr = Db_ExistsExpr(dbAtExpr, not)
        return V_DbExpr.create(ctx, name.pos, dbResExpr, vAtExpr.varFacts())
    }
}
