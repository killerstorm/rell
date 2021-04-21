package net.postchain.rell.lib.test

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.C_BinOp_Eq
import net.postchain.rell.compiler.vexpr.V_BinaryOp
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.R_BinaryOp
import net.postchain.rell.model.R_Expr
import net.postchain.rell.model.R_NullableType
import net.postchain.rell.model.R_UnitType
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_Error
import net.postchain.rell.runtime.Rt_UnitValue
import net.postchain.rell.runtime.Rt_Value

object C_Lib_Rell_Test_Assert {
    val FUNCTIONS = C_GlobalFuncBuilder()
            .add("assert_equals", C_FuncCase_AssertEquals)
            .build()
}

private object C_FuncCase_AssertEquals: C_GlobalFuncCase() {
    override fun getParamTypeHint(index: Int) = C_TypeHint.NONE

    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 2) return null

        val cOp = C_BinOp_Eq
        val (actual, expected) = cOp.adaptLeftRight(ctx, args[0], args[1])

        var actualType = actual.type()
        var expectedType = expected.type()
        if (actualType is R_NullableType && expectedType == actualType.valueType) {
            expectedType = actualType
        } else if (expectedType is R_NullableType && actualType == expectedType.valueType) {
            actualType = expectedType
        }

        val vOp = cOp.compileOp(actualType, expectedType)
        vOp ?: return null

        return CaseMatch(actual, expected, vOp)
    }

    private class CaseMatch(
            val actual: V_Expr,
            val expected: V_Expr,
            val vOp: V_BinaryOp
    ): C_GlobalFuncCaseMatch(R_UnitType) {
        override fun varFacts() = C_ExprVarFacts.forSubExpressions(listOf(actual, expected))

        override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr {
            val rActual = actual.toRExpr()
            val rExpected = expected.toRExpr()
            return R_AssertEqualsExpr(rActual, rExpected, vOp.rOp)
        }
    }
}

private class R_AssertEqualsExpr(
        private val actual: R_Expr,
        private val expected: R_Expr,
        private val op: R_BinaryOp
): R_Expr(R_UnitType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val actualValue = actual.evaluate(frame)
        val expectedValue = expected.evaluate(frame)
        val equalsValue = op.evaluate(actualValue, expectedValue)
        if (!equalsValue.asBoolean()) {
            val code = "assert_equals:${expectedValue.toStrictString()}:${actualValue.toStrictString()}"
            throw Rt_Error(code, "expected <$expectedValue> but was <$actualValue>")
        }
        return Rt_UnitValue
    }
}
