package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibRellTestAssertTest: BaseRellTest(false) {
    init {
        tst.testLib = true
    }

    @Test fun testAssertEqualsBasic() {
        chkAssertEquals("0", "1", "int[0]", "int[1]")
        chkAssertEquals("'Hello'", "'World'", "text[Hello]", "text[World]")
        chkAssertEquals("true", "false", "boolean[true]", "boolean[false]")
        chkAssertEquals("12.34", "56.78", "dec[12.34]", "dec[56.78]")
        chkAssertEquals("x'beef'", "x'feed'", "byte_array[beef]", "byte_array[feed]")
        chkAssertEquals("json('[1,2,3]')", "json('[4,5,6]')", "json[[1,2,3]]", "json[[4,5,6]]")
        chkAssertEquals("range(5)", "range(6)", "range[0,5,1]", "range[0,6,1]")
    }

    @Test fun testAssertEqualsComplex() {
        def("struct rec { x: integer; }")

        chkAssertEquals("(1,'a')", "(1,'b')", "(int[1],text[a])", "(int[1],text[b])")
        chkAssertEquals("(1,'a')", "(2,'a')", "(int[1],text[a])", "(int[2],text[a])")
        chkAssertEquals("rec(123)", "rec(456)", "rec[x=int[123]]", "rec[x=int[456]]")

        chkAssertEqualsBad("(1,'a')", "('a',1)", "(integer,text)", "(text,integer)")
        chkAssertEqualsBad("(1,'a')", "(1,2)", "(integer,text)", "(integer,integer)")
        chkAssertEqualsBad("(1,'a')", "(1,'a',2)", "(integer,text)", "(integer,text,integer)")
        chkAssertEqualsBad("(x=1,y='a')", "(x=1,z='a')", "(x:integer,y:text)", "(x:integer,z:text)")
    }

    @Test fun testAssertEqualsCollections() {
        chkAssertEquals("[1,2,3]", "[4,5,6]", "list<integer>[int[1],int[2],int[3]]", "list<integer>[int[4],int[5],int[6]]")
        chkAssertEqualsBad("[1,2,3]", "['a','b','c']", "list<integer>", "list<text>")

        chkAssertEquals("set([1,2,3])", "set([4,5,6])", "set<integer>[int[1],int[2],int[3]]", "set<integer>[int[4],int[5],int[6]]")
        chkAssertEqualsBad("set([1,2,3])", "set(['a','b','c'])", "set<integer>", "set<text>")
        chkAssertEqualsBad("set([1,2,3])", "[1,2,3]", "set<integer>", "list<integer>")

        chkAssertEquals("[1:'a']", "[1:'b']", "map<integer,text>[int[1]=text[a]]", "map<integer,text>[int[1]=text[b]]")
        chkAssertEqualsBad("[1:'a']", "['a':1]", "map<integer,text>", "map<text,integer>")
    }

    @Test fun testAssertEqualsSpecial() {
        def("struct rec { x: integer; }")

        chkAssertEquals("_nullable_int(123)", "456", "int[123]", "int[456]")
        chkAssertEquals("_nullable_int(123)", "_nullable_int(456)", "int[123]", "int[456]")
        chkAssertEquals("_nullable_int(123)", "null", "int[123]", "null")
        chkAssertEquals("123", "_nullable_int(456)", "int[123]", "int[456]")
        chkAssertEquals("null", "_nullable_int(123)", "null", "int[123]")

        chkAssertEquals("_nullable(rec(123))", "rec(456)", "rec[x=int[123]]", "rec[x=int[456]]")
        chkAssertEquals("rec(123)", "_nullable(rec(456))", "rec[x=int[123]]", "rec[x=int[456]]")
        chkAssertEquals("_nullable(rec(123))", "null", "rec[x=int[123]]", "null")
        chkAssertEquals("null", "_nullable(rec(123))", "null", "rec[x=int[123]]")
    }

    @Test fun testAssertEqualsNumericPromotions() {
        chkAssert("assert_equals(123.0, 456)", "rt_err:assert_equals:dec[123]:dec[456]")
        chkAssert("assert_equals(123, 456.0)", "rt_err:assert_equals:dec[123]:dec[456]")
        chkAssert("assert_equals(123.0, 123)", "int[0]")
        chkAssert("assert_equals(123, 123.0)", "int[0]")

        chkAssert("assert_not_equals(123.0, 456)", "int[0]")
        chkAssert("assert_not_equals(123, 456.0)", "int[0]")
        chkAssert("assert_not_equals(123.0, 123)", "rt_err:assert_not_equals:dec[123]")
        chkAssert("assert_not_equals(123, 123.0)", "rt_err:assert_not_equals:dec[123]")

        // More complex type promotions not supported yet.
        chkAssertEqualsBad("_nullable_int(123)", "123.0", "integer?", "decimal")
        chkAssertEqualsBad("123.0", "_nullable_int(123)", "decimal", "integer?")
        chkAssertEqualsBad("_nullable(123.0)", "123", "decimal?", "integer")
        chkAssertEqualsBad("123", "_nullable(123.0)", "integer", "decimal?")
    }

    @Test fun testAssertEqualsBadArgs() {
        chkAssertEqualsBadArgs("assert_equals")
        chkAssertEqualsBadArgs("assert_not_equals")
    }

    private fun chkAssertEqualsBadArgs(fn: String) {
        chkAssert("$fn()", "ct_err:expr_call_argtypes:$fn:")
        chkAssert("$fn(0)", "ct_err:expr_call_argtypes:$fn:integer")
        chkAssert("$fn(0, 1, 'Hello')", "ct_err:expr_call_argtypes:$fn:integer,integer,text")
        chkAssert("$fn(0, 1, 2)", "ct_err:expr_call_argtypes:$fn:integer,integer,integer")
        chkAssert("$fn(123, 'Hello')", "ct_err:expr_call_argtypes:$fn:integer,text")
    }

    private fun chkAssertEquals(e1: String, e2: String, v1: String, v2: String) {
        chkAssert("assert_equals($e1, $e2)", "rt_err:assert_equals:$v1:$v2")
        chkAssert("assert_equals($e1, $e1)", "int[0]")
        chkAssert("assert_equals($e2, $e2)", "int[0]")

        chkAssert("assert_not_equals($e1, $e2)", "int[0]")
        chkAssert("assert_not_equals($e1, $e1)", "rt_err:assert_not_equals:$v1")
        chkAssert("assert_not_equals($e2, $e2)", "rt_err:assert_not_equals:$v2")
    }

    private fun chkAssertEqualsBad(e1: String, e2: String, t1: String, t2: String) {
        chkAssert("assert_equals($e1, $e2)", "ct_err:expr_call_argtypes:assert_equals:$t1,$t2")
        chkAssert("assert_not_equals($e1, $e2)", "ct_err:expr_call_argtypes:assert_not_equals:$t1,$t2")
    }

    @Test fun testAssertTrueFalse() {
        chkAssert("assert_true(true)", "int[0]")
        chkAssert("assert_true(false)", "rt_err:assert_boolean:true")
        chkAssert("assert_false(true)", "rt_err:assert_boolean:false")
        chkAssert("assert_false(false)", "int[0]")

        chkAssert("assert_true(123)", "ct_err:expr_call_argtypes:assert_true:integer")
        chkAssert("assert_false(123)", "ct_err:expr_call_argtypes:assert_false:integer")
        chkAssert("assert_true(_nullable(true))", "ct_err:expr_call_argtypes:assert_true:boolean?")
        chkAssert("assert_false(_nullable(true))", "ct_err:expr_call_argtypes:assert_false:boolean?")
    }

    @Test fun testAssertNull() {
        chkAssert("assert_null(_nullable_int(123))", "rt_err:assert_null:123")
        chkAssert("assert_null(_nullable_int(null))", "int[0]")
        chkAssert("assert_null(123)", "ct_err:expr_call_argtypes:assert_null:integer")
        chkAssert("assert_null(null)", "int[0]")

        chkAssert("assert_not_null(_nullable_int(123))", "int[0]")
        chkAssert("assert_not_null(_nullable_int(null))", "rt_err:assert_not_null")
        chkAssert("assert_not_null(123)", "ct_err:expr_call_argtypes:assert_not_null:integer")
        chkAssert("assert_not_null(null)", "ct_err:expr_call_argtypes:assert_not_null:null")
    }

    @Test fun testAssertNullNullabilityAnalysis() {
        chkEx("{ val x = _nullable_int(123); return _type_of(x); }", "text[integer?]")
        chkEx("{ val x = _nullable_int(null); return _type_of(x); }", "text[integer?]")
        chkEx("{ val x = _nullable_int(123); assert_not_null(x); return _type_of(x); }", "text[integer]")
        chkEx("{ val x = _nullable_int(null); assert_null(x); return _type_of(x); }", "text[integer?]")
    }

    @Test fun testAssertCompare() {
        chkAssertCompare("1 2 3", "int[1] int[2] int[3]")
        chkAssertCompare("1.2 3.4 5.6", "dec[1.2] dec[3.4] dec[5.6]")
        chkAssertCompare("'a' 'b' 'c'", "text[a] text[b] text[c]")
        chkAssertCompare("x'a1' x'b2' x'c3'", "byte_array[a1] byte_array[b2] byte_array[c3]")
    }

    private fun chkAssertCompare(exprs: String, values: String) {
        chkAssertCompare("assert_lt", CmpOp.LT, exprs, values)
        chkAssertCompare("assert_gt", CmpOp.GT, exprs, values)
        chkAssertCompare("assert_le", CmpOp.LE, exprs, values)
        chkAssertCompare("assert_ge", CmpOp.GE, exprs, values)
    }

    private fun chkAssertCompare(fn: String, op: CmpOp, exprsStr: String, valuesStr: String) {
        val exprs = exprsStr.split(" ")
        val values = valuesStr.split(" ")
        chkAssertCompare(fn, op, exprs, values, 0, 1)
        chkAssertCompare(fn, op, exprs, values, 1, 1)
        chkAssertCompare(fn, op, exprs, values, 2, 1)
        chkAssertCompare(fn, op, exprs, values, 1, 0)
        chkAssertCompare(fn, op, exprs, values, 1, 1)
        chkAssertCompare(fn, op, exprs, values, 1, 2)
    }

    private fun chkAssertCompare(fn: String, op: CmpOp, exprs: List<String>, values: List<String>, i: Int, j: Int) {
        val t = op.fn(i.compareTo(j))
        val exp = if (t) "int[0]" else "rt_err:assert_compare:${op.op}:${values[i]}:${values[j]}"
        chkAssert("$fn(${exprs[i]}, ${exprs[j]})", exp)
    }

    @Test fun testAssertCompareNumericPromotion() {
        chkAssertCompareNP("assert_lt", CmpOp.LT)
        chkAssertCompareNP("assert_gt", CmpOp.GT)
        chkAssertCompareNP("assert_le", CmpOp.LE)
        chkAssertCompareNP("assert_ge", CmpOp.GE)
    }

    private fun chkAssertCompareNP(fn: String, op: CmpOp) {
        chkAssertCompareNP(fn, op, "1", "2.0", -1)
        chkAssertCompareNP(fn, op, "2", "2.0", 0)
        chkAssertCompareNP(fn, op, "3", "2.0", 1)
        chkAssertCompareNP(fn, op, "1.0", "2", -1)
        chkAssertCompareNP(fn, op, "2.0", "2", 0)
        chkAssertCompareNP(fn, op, "3.0", "2", 1)

        chkAssertCompareNP(fn, op, "2.0", "1", 1)
        chkAssertCompareNP(fn, op, "2.0", "2", 0)
        chkAssertCompareNP(fn, op, "2.0", "3", -1)
        chkAssertCompareNP(fn, op, "2", "1.0", 1)
        chkAssertCompareNP(fn, op, "2", "2.0", 0)
        chkAssertCompareNP(fn, op, "2", "3.0", -1)
    }

    private fun chkAssertCompareNP(fn: String, op: CmpOp, a: String, b: String, cmp: Int) {
        val av = "dec[${a.toDouble().toInt()}]"
        val bv = "dec[${b.toDouble().toInt()}]"
        val exp = if (op.fn(cmp)) "int[0]" else "rt_err:assert_compare:${op.op}:$av:$bv"
        chkAssert("$fn($a, $b)", exp)
    }

    @Test fun testAssertRange() {
        chkAssertRange("assert_gt_lt", CmpOp.GT, CmpOp.LT)
        chkAssertRange("assert_gt_le", CmpOp.GT, CmpOp.LE)
        chkAssertRange("assert_ge_lt", CmpOp.GE, CmpOp.LT)
        chkAssertRange("assert_ge_le", CmpOp.GE, CmpOp.LE)
    }

    private fun chkAssertRange(fn: String, op1: CmpOp, op2: CmpOp) {
        chkAssertRange(fn, op1, op2, "1 2 3 4 5", "int[1] int[2] int[3] int[4] int[5]")
        chkAssertRange(fn, op1, op2, "1.0 2.0 3.0 4.0 5.0", "dec[1] dec[2] dec[3] dec[4] dec[5]")
        chkAssertRange(fn, op1, op2, "'a' 'b' 'c' 'd' 'e'", "text[a] text[b] text[c] text[d] text[e]")
        chkAssertRange(fn, op1, op2, "x'a1' x'b2' x'c3' x'd4' x'e5'",
                "byte_array[a1] byte_array[b2] byte_array[c3] byte_array[d4] byte_array[e5]")
    }

    private fun chkAssertRange(fn: String, op1: CmpOp, op2: CmpOp, exprsStr: String, valuesStr: String) {
        val exprs = exprsStr.split(" ")
        val values = valuesStr.split(" ")
        chkAssertRange(fn, op1, op2, exprs, values, 0)
        chkAssertRange(fn, op1, op2, exprs, values, 1)
        chkAssertRange(fn, op1, op2, exprs, values, 2)
        chkAssertRange(fn, op1, op2, exprs, values, 3)
        chkAssertRange(fn, op1, op2, exprs, values, 4)
    }

    private fun chkAssertRange(fn: String, op1: CmpOp, op2: CmpOp, exprs: List<String>, values: List<String>, i: Int) {
        val exp = if (!op1.fn(i.compareTo(1))) {
            "rt_err:assert_compare:${op1.op}:${values[i]}:${values[1]}"
        } else if (!op2.fn(i.compareTo(3))) {
            "rt_err:assert_compare:${op2.op}:${values[i]}:${values[3]}"
        } else {
            "int[0]"
        }
        chkAssert("$fn(${exprs[i]}, ${exprs[1]}, ${exprs[3]})", exp)
    }

    @Test fun testAssertRangeNumericPromotion() {
        chkAssertRangeNP("assert_gt_lt", CmpOp.GT, CmpOp.LT)
        chkAssertRangeNP("assert_gt_le", CmpOp.GT, CmpOp.LE)
        chkAssertRangeNP("assert_ge_lt", CmpOp.GE, CmpOp.LT)
        chkAssertRangeNP("assert_ge_le", CmpOp.GE, CmpOp.LE)
    }

    private fun chkAssertRangeNP(fn: String, op1: CmpOp, op2: CmpOp) {
        chkAssertRangeNP(fn, op1, op2, "1.0, 2, 4", 0)
        chkAssertRangeNP(fn, op1, op2, "2.0, 2, 4", 1)
        chkAssertRangeNP(fn, op1, op2, "3.0, 2, 4", 2)
        chkAssertRangeNP(fn, op1, op2, "4.0, 2, 4", 3)
        chkAssertRangeNP(fn, op1, op2, "5.0, 2, 4", 4)

        chkAssertRangeNP(fn, op1, op2, "1, 2.0, 4", 0)
        chkAssertRangeNP(fn, op1, op2, "2, 2.0, 4", 1)
        chkAssertRangeNP(fn, op1, op2, "3, 2.0, 4", 2)
        chkAssertRangeNP(fn, op1, op2, "4, 2.0, 4", 3)
        chkAssertRangeNP(fn, op1, op2, "5, 2.0, 4", 4)

        chkAssertRangeNP(fn, op1, op2, "1, 2, 4.0", 0)
        chkAssertRangeNP(fn, op1, op2, "2, 2, 4.0", 1)
        chkAssertRangeNP(fn, op1, op2, "3, 2, 4.0", 2)
        chkAssertRangeNP(fn, op1, op2, "4, 2, 4.0", 3)
        chkAssertRangeNP(fn, op1, op2, "5, 2, 4.0", 4)
    }

    private fun chkAssertRangeNP(fn: String, op1: CmpOp, op2: CmpOp, argsStr: String, i: Int) {
        val args = argsStr.split(",")
        val vals = args.map { "dec[${it.toDouble().toInt()}]" }
        val exp = if (!op1.fn(i.compareTo(1))) {
            "rt_err:assert_compare:${op1.op}:${vals[0]}:${vals[1]}"
        } else if (!op2.fn(i.compareTo(3))) {
            "rt_err:assert_compare:${op2.op}:${vals[0]}:${vals[2]}"
        } else {
            "int[0]"
        }
        chkAssert("$fn($argsStr)", exp)
    }

    private fun chkAssert(expr: String, expected: String) {
        chkEx("{ $expr; return 0; }", expected)
        chkEx("{ rell.test.$expr; return 0; }", expected)
    }

    private enum class CmpOp(val op: String, val fn: (Int) -> Boolean) {
        LT("<", { it < 0}),
        GT(">", { it > 0}),
        LE("<=", { it <= 0}),
        GE(">=", { it >= 0}),
    }
}
