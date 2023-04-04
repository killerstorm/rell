/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.test

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.fn.*
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_SpecialGlobalFuncCaseMatch
import net.postchain.rell.compiler.vexpr.V_BinaryOp
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.lib.type.C_Lib_Type
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.R_BinaryOp
import net.postchain.rell.model.expr.R_BinaryOp_Eq
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.module.GtvRtConversion
import net.postchain.rell.module.GtvRtConversion_None
import net.postchain.rell.runtime.*
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.immListOf

private const val FAILURE_SNAME = "failure"
private val FAILURE_QNAME = C_Lib_Test.NAMESPACE_NAME.add(FAILURE_SNAME)

object C_Lib_Test_Assert {
    val FAILURE_TYPE: R_Type = R_TestFailureType

    private val FUNCTIONS = C_GlobalFuncBuilder(C_Lib_Test.NAMESPACE_DEF_PATH)
            .add("assert_equals", C_FuncCase_AssertEquals(true))
            .add("assert_not_equals", C_FuncCase_AssertEquals(false))
            .add("assert_true", R_UnitType, listOf(R_BooleanType), R_Fns.AssertBoolean(true))
            .add("assert_false", R_UnitType, listOf(R_BooleanType), R_Fns.AssertBoolean(false))
            .addEx("assert_null", R_UnitType, listOf(C_ArgTypeMatcher_Nullable), R_Fns.AssertNull)
            .add("assert_not_null", C_FuncCase_AssertNotNull)
            .add("assert_fails", R_TestFailureType, listOf(R_FunctionType(listOf(), R_UnitType)), R_Fns.AssertFails1)
            .add("assert_fails", R_TestFailureType, listOf(R_TextType, R_FunctionType(listOf(), R_UnitType)), R_Fns.AssertFails2)

            .add("assert_lt", C_FuncCase_AssertCompare(C_BinOp_Lt))
            .add("assert_gt", C_FuncCase_AssertCompare(C_BinOp_Gt))
            .add("assert_le", C_FuncCase_AssertCompare(C_BinOp_Le))
            .add("assert_ge", C_FuncCase_AssertCompare(C_BinOp_Ge))
            .add("assert_gt_lt", C_FuncCase_AssertRange(C_BinOp_Gt, C_BinOp_Lt))
            .add("assert_gt_le", C_FuncCase_AssertRange(C_BinOp_Gt, C_BinOp_Le))
            .add("assert_ge_lt", C_FuncCase_AssertRange(C_BinOp_Ge, C_BinOp_Lt))
            .add("assert_ge_le", C_FuncCase_AssertRange(C_BinOp_Ge, C_BinOp_Le))
            .build()

    fun bind(b: C_SysNsProtoBuilder) {
        C_Lib_Type_Failure.bind(b)
        C_LibUtils.bindFunctions(b, FUNCTIONS)
    }

    fun failureValue(message: String): Rt_Value = Rt_TestFailureValue(message)

    fun assertEquals(fn: String, expected: Rt_Value, actual: Rt_Value, op: R_BinaryOp = R_BinaryOp_Eq) {
        val equalsValue = op.evaluate(actual, expected)
        if (!equalsValue.asBoolean()) {
            val code = "$fn:${actual.strCode()}:${expected.strCode()}"
            throw Rt_AssertError.exception(code, "expected <${expected.str()}> but was <${actual.str()}>")
        }
    }
}

private object R_Fns {
    class AssertBoolean(val expected: Boolean): R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val v = arg.asBoolean()
            if (v != expected) {
                throw Rt_AssertError.exception("assert_boolean:$expected", "expected $expected")
            }
            return Rt_UnitValue
        }
    }

    object AssertNull: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            if (arg != Rt_NullValue) {
                throw Rt_AssertError.exception("assert_null:${arg.strCode()}", "expected null but was <${arg.str()}>")
            }
            return Rt_UnitValue
        }
    }

    object AssertNotNull: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            if (arg == Rt_NullValue) {
                throw Rt_AssertError.exception("assert_not_null", "expected not null")
            }
            return Rt_UnitValue
        }
    }

    object AssertFails1: R_SysFunctionEx_1() {
        override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
            val fn = arg.asFunction()
            return AssertFails2.call0(ctx, fn, null)
        }
    }

    object AssertFails2: R_SysFunctionEx_2() {
        override fun call(ctx: Rt_CallContext, arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            val expected = arg1.asString()
            val fn = arg2.asFunction()
            return call0(ctx, fn, expected)
        }

        fun call0(ctx: Rt_CallContext, fn: Rt_FunctionValue, expected: String?): Rt_Value {
            var err: Rt_Error? = null
            try {
                fn.call(ctx, immListOf())
            } catch (e: Rt_Exception) {
                if (e.err is Rt_AssertError) {
                    throw e
                }
                err = e.err
            }

            if (err == null) {
                throw Rt_AssertError.exception("assert_fails:no_fail:${fn.strCode()}", "code did not fail")
            }

            val message = err.message()
            if (expected != null && message != expected) {
                val code = "assert_fails:mismatch:[$expected]:[$message]"
                val msg = "expected <$expected> but was <$message>"
                throw Rt_AssertError.exception(code, msg)
            }

            return Rt_TestFailureValue(message)
        }
    }
}

private object C_FuncCase_AssertNotNull: C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null

        val expr = args[0]
        val type = expr.type
        if (type !is R_NullableType) return null

        val preFacts = expr.varFacts.postFacts
        val varFacts = C_ExprVarFacts.forNullCast(preFacts, expr)
        return CaseMatch(expr, varFacts)
    }

    private class CaseMatch(
            val actual: V_Expr,
            val varFacts: C_ExprVarFacts
    ): C_SpecialGlobalFuncCaseMatch(R_UnitType) {
        override fun varFacts() = varFacts
        override fun subExprs() = immListOf(actual)

        override fun compileCallR(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr {
            val rActual = actual.toRExpr()
            return C_ExprUtils.createSysCallRExpr(resType, R_Fns.AssertNotNull, listOf(rActual), caseCtx)
        }
    }
}

private class C_FuncCase_AssertEquals(private val positive: Boolean): C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 2) return null

        val cOp = C_BinOp_Eq
        val (actual, expected) = cOp.adaptLeftRight(ctx, args[0], args[1])

        var actualType = actual.type
        var expectedType = expected.type
        if (actualType is R_NullableType && expectedType == actualType.valueType) {
            expectedType = actualType
        } else if (expectedType is R_NullableType && actualType == expectedType.valueType) {
            actualType = expectedType
        }

        val vOp = cOp.compileOp(actualType, expectedType)
        vOp ?: return null

        return CaseMatch(actual, expected, vOp, positive)
    }

    private class CaseMatch(
            val actual: V_Expr,
            val expected: V_Expr,
            val vOp: V_BinaryOp,
            val positive: Boolean
    ): C_SpecialGlobalFuncCaseMatch(R_UnitType) {
        override fun varFacts() = C_ExprVarFacts.forSubExpressions(listOf(actual, expected))
        override fun subExprs() = immListOf(actual, expected)

        override fun compileCallR(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr {
            val rActual = actual.toRExpr()
            val rExpected = expected.toRExpr()
            return if (positive) {
                R_AssertEqualsExpr(rActual, rExpected, vOp.rOp)
            } else {
                R_AssertNotEqualsExpr(rActual, rExpected, vOp.rOp)
            }
        }
    }
}

private class C_FuncCase_AssertCompare(private val op: C_BinOp_Cmp): C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 2) return null

        val (left, right) = op.adaptLeftRight(ctx, args[0], args[1])
        val vOp = op.compileOp(left.type, right.type)
        vOp ?: return null

        return CaseMatch(left, right, vOp)
    }

    private class CaseMatch(
            val left: V_Expr,
            val right: V_Expr,
            val vOp: V_BinaryOp
    ): C_SpecialGlobalFuncCaseMatch(R_UnitType) {
        override fun varFacts() = C_ExprVarFacts.forSubExpressions(listOf(left, right))
        override fun subExprs() = immListOf(left, right)

        override fun compileCallR(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr {
            val rLeft = left.toRExpr()
            val rRight = right.toRExpr()
            return R_AssertCompareExpr(rLeft, rRight, vOp.rOp)
        }
    }
}

private class C_FuncCase_AssertRange(
        private val op1: C_BinOp_Cmp,
        private val op2: C_BinOp_Cmp
): C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 3) return null

        val adapted = op1.adaptOperands(ctx, args)
        val actual = adapted[0]
        val expected1 = adapted[1]
        val expected2 = adapted[2]

        val vOp1 = op1.compileOp(actual.type, expected1.type)
        val vOp2 = op2.compileOp(actual.type, expected2.type)
        vOp1 ?: return null
        vOp2 ?: return null

        return CaseMatch(actual, expected1, expected2, vOp1, vOp2)
    }

    private class CaseMatch(
            val actual: V_Expr,
            val expected1: V_Expr,
            val expected2: V_Expr,
            val vOp1: V_BinaryOp,
            val vOp2: V_BinaryOp
    ): C_SpecialGlobalFuncCaseMatch(R_UnitType) {
        override fun varFacts() = C_ExprVarFacts.forSubExpressions(listOf(actual, expected1, expected2))
        override fun subExprs() = immListOf(actual, expected1, expected2)

        override fun compileCallR(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr {
            val rActual = actual.toRExpr()
            val rExpected1 = expected1.toRExpr()
            val rExpected2 = expected2.toRExpr()
            return R_AssertRangeExpr(rActual, rExpected1, rExpected2, vOp1.rOp, vOp2.rOp)
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
        C_Lib_Test_Assert.assertEquals("assert_equals", expectedValue, actualValue, op)
        return Rt_UnitValue
    }
}

private class R_AssertNotEqualsExpr(
        private val actual: R_Expr,
        private val expected: R_Expr,
        private val op: R_BinaryOp
): R_Expr(R_UnitType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val actualValue = actual.evaluate(frame)
        val expectedValue = expected.evaluate(frame)
        val equalsValue = op.evaluate(actualValue, expectedValue)
        if (equalsValue.asBoolean()) {
            val code = "assert_not_equals:${actualValue.strCode()}"
            throw Rt_AssertError.exception(code, "expected not <${actualValue.str()}>")
        }
        return Rt_UnitValue
    }
}

private class R_AssertCompareExpr(
        private val left: R_Expr,
        private val right: R_Expr,
        private val op: R_BinaryOp
): R_Expr(R_UnitType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val leftValue = left.evaluate(frame)
        eval(frame, leftValue, right, op)
        return Rt_UnitValue
    }

    companion object {
        fun eval(frame: Rt_CallFrame, actualValue: Rt_Value, expectedExpr: R_Expr, op: R_BinaryOp) {
            val expectedValue = expectedExpr.evaluate(frame)
            val resValue = op.evaluate(actualValue, expectedValue)
            if (!resValue.asBoolean()) {
                val code = "assert_compare:${op.code}:${actualValue.strCode()}:${expectedValue.strCode()}"
                throw Rt_AssertError.exception(code, "comparison failed: ${actualValue.str()} ${op.code} ${expectedValue.str()}")
            }
        }
    }
}

private class R_AssertRangeExpr(
        private val actual: R_Expr,
        private val expected1: R_Expr,
        private val expected2: R_Expr,
        private val op1: R_BinaryOp,
        private val op2: R_BinaryOp
): R_Expr(R_UnitType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val actualValue = actual.evaluate(frame)
        R_AssertCompareExpr.eval(frame, actualValue, expected1, op1)
        R_AssertCompareExpr.eval(frame, actualValue, expected2, op2)
        return Rt_UnitValue
    }
}

class Rt_AssertError private constructor(val code: String, val msg: String): Rt_Error() {
    override fun code() = "asrt_err:$code"
    override fun message() = msg

    companion object {
        fun exception(code: String, msg: String) = Rt_Exception(Rt_AssertError(code, msg))
    }
}

private object R_TestFailureType: R_BasicType(FAILURE_QNAME.str(), C_Lib_Test.typeDefName(FAILURE_QNAME)) {
    override fun isReference() = true
    override fun isDirectPure() = false
    override fun createGtvConversion(): GtvRtConversion = GtvRtConversion_None
    override fun getLibType(): C_Lib_Type = C_Lib_Type_Failure
}

private object C_Lib_Type_Failure: C_Lib_Type(FAILURE_SNAME, R_TestFailureType, defaultMemberFns = false) {
    val VALUE_TYPE = Rt_LibValueType.of("TEST_FAILURE")

    override fun bindMemberValues(b: MutableList<C_TypeValueMember>) {
        val ideName = C_LibUtils.ideName("message", IdeSymbolKind.MEM_STRUCT_ATTR)
        val attr: C_MemberAttr = C_MemberAttr_SysProperty(ideName, PropMessage)
        b.add(C_TypeValueMember_BasicAttr(attr))
    }

    private val PropMessage = C_SysMemberProperty.simple(R_TextType, pure = true) { a ->
        val v = a as Rt_TestFailureValue
        v.messageValue
    }
}

private class Rt_TestFailureValue(val message: String): Rt_Value() {
    val messageValue = Rt_TextValue(message)

    override val valueType = C_Lib_Type_Failure.VALUE_TYPE
    override fun type(): R_Type = R_TestFailureType
    override fun str() = message
    override fun strCode(showTupleFieldNames: Boolean) = "${R_TestFailureType.name}[$message]"
}
