package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class NullAnalysisTest: BaseRellTest(false) {
    @Test fun testInit() {
        tst.defs = listOf("function f(a: integer?): integer? = a;")

        chkEx("{ val x: integer? = null; return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ val x: integer? = f(123); return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ val x: integer? = 123; return x + 1; }", "int[124]")

        chkEx("{ val x: integer?; x = null; return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ val x: integer?; x = f(123); return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ val x: integer?; x = 123; return x + 1; }", "int[124]")

        chkEx("{ val x: integer? = null; return abs(x); }", "ct_err:expr_call_argtypes:abs:integer?")
        chkEx("{ val x: integer? = f(123); return abs(x); }", "ct_err:expr_call_argtypes:abs:integer?")
        chkEx("{ val x: integer? = 123; return abs(x); }", "int[123]")

        chkEx("{ val x: integer? = 123; val y: integer? = x; val z: integer? = y; return _typeOf(z); }", "text[integer]")
    }

    @Test fun testAssignment() {
        tst.defs = listOf("function f(a: integer?): integer? = a;")

        chkEx("{ var x: integer? = null; x = null; return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ var x: integer? = null; x = f(123); return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ var x: integer? = null; x = 123; return x + 1; }", "int[124]")

        chkEx("{ var x: integer? = f(123); x = null; return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ var x: integer? = f(123); x = f(123); return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ var x: integer? = f(123); x = 123; return x + 1; }", "int[124]")

        chkEx("{ var x: integer? = 123; x = null; return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ var x: integer? = 123; x = f(456); return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ var x: integer? = 123; x = 456; return x + 1; }", "int[457]")

        chkEx("{ var x: integer?; x = null; return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ var x: integer?; x = f(456); return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ var x: integer?; x = 456; return x + 1; }", "int[457]")

        chkEx("{ var x: integer? = _nullable(123); return _typeOf(x); }", "text[integer?]")
        chkEx("{ var x: integer? = _nullable(123); if (x == null) return ''; return _typeOf(x); }", "text[integer]")
        chkEx("{ var x: integer? = _nullable(123); if (x == null) return ''; x = _nullable(456); return _typeOf(x); }",
                "text[integer?]")
    }

    @Test fun testAssignmentIf() {
        tst.strictToString = false
        chkEx("{ var x = _nullable_int(null); if (abs(5)>0) x = 123; else x = 456; return _typeOf(x); }", "integer")
        chkEx("{ var x = _nullable_int(123); if (abs(5)>0) x = 456; else x = null; return _typeOf(x); }", "integer?")
        chkEx("{ var x = _nullable_int(123); if (abs(5)>0) x = null; else x = null; return _typeOf(x); }", "integer?")
        chkEx("{ var x = _nullable_int(null); if (x == null) x = 123; return _typeOf(x); }", "integer")
        chkEx("{ var x = _nullable_int(null); if (x != null) x = 123; return _typeOf(x); }", "integer?")
        chkEx("{ var x = _nullable_int(null); if (x != null) x = null; return _typeOf(x); }", "integer?")
    }

    @Test fun testAssignmentWhen() {
        tst.strictToString = false
        chkEx("{ var x = _nullable_int(null); when { abs(5)>0 -> x = 123; else -> x = 456; } return _typeOf(x); }", "integer")
        chkEx("{ var x = _nullable_int(123); when { abs(5)>0 -> x = 456; else -> x = null; } return _typeOf(x); }", "integer?")
        chkEx("{ var x = _nullable_int(123); when { abs(5)>0 -> x = null; else -> x = null; } return _typeOf(x); }", "integer?")
        chkEx("{ var x = _nullable_int(null); when { x == null -> x = 123; } return _typeOf(x); }", "integer")
        chkEx("{ var x = _nullable_int(null); when { x != null -> x = 123; } return _typeOf(x); }", "integer?")
    }

    @Test fun testCompoundAssignment() {
        chkEx("{ var x = _nullable_int(123); x += 1; return x; }", "ct_err:binop_operand_type:+=:integer?:integer")
        chkEx("{ var x: integer? = null; x += 1; return x; }", "ct_err:binop_operand_type:+=:integer?:integer")
        chkEx("{ var x: integer? = 123; x += 1; return x; }", "int[124]")

        chkEx("{ var x = _nullable_int(123); if (x != null) x += 1; return x; }", "int[124]")
        chkEx("{ var x = _nullable_int(null); if (x != null) x += 1; return x; }", "null")
    }

    @Test fun testIncrement() {
        chkEx("{ var x = _nullable_int(123); x++; return x; }", "ct_err:expr_incdec_type:++:integer?")

        chkEx("{ var x: integer? = null; x++; return x; }", "ct_err:expr_incdec_type:++:integer?")
        chkEx("{ var x: integer? = null; ++x; return x; }", "ct_err:expr_incdec_type:++:integer?")
        chkEx("{ var x: integer? = null; x--; return x; }", "ct_err:expr_incdec_type:--:integer?")
        chkEx("{ var x: integer? = null; --x; return x; }", "ct_err:expr_incdec_type:--:integer?")

        chkEx("{ var x: integer? = 123; x++; return x; }", "int[124]")
        chkEx("{ var x: integer? = 123; ++x; return x; }", "int[124]")
        chkEx("{ var x: integer? = 123; x--; return x; }", "int[122]")
        chkEx("{ var x: integer? = 123; --x; return x; }", "int[122]")

        chkEx("{ var x = _nullable_int(123); if (x != null) x++; return x; }", "int[124]")
        chkEx("{ var x = _nullable_int(123); if (x != null) ++x; return x; }", "int[124]")
        chkEx("{ var x = _nullable_int(123); if (x != null) x--; return x; }", "int[122]")
        chkEx("{ var x = _nullable_int(123); if (x != null) --x; return x; }", "int[122]")

        chkEx("{ var x = _nullable_int(null); if (x != null) x++; return x; }", "null")
        chkEx("{ var x = _nullable_int(null); if (x != null) ++x; return x; }", "null")
        chkEx("{ var x = _nullable_int(null); if (x != null) x--; return x; }", "null")
        chkEx("{ var x = _nullable_int(null); if (x != null) --x; return x; }", "null")
    }

    @Test fun testBooleanExpressions() {
        chkQueryEx("query q(x: integer?) = x > 5;", 6, "ct_err:binop_operand_type:>:integer?:integer")
        chkQueryEx("query q(x: integer?) = x != null;", 5, "boolean[true]")
        chkQueryEx("query q(x: integer?) = x == null;", 5, "boolean[false]")

        var q: String

        q = "query q(x: integer?) = x != null and x > 5;"
        chkQueryEx(q, 6, "boolean[true]")
        chkQueryEx(q, 5, "boolean[false]")
        chkQueryEx(q, null as Long?, "boolean[false]")
        chkQueryEx("query q(x: integer?) = x == null and x > 5;", 0, "ct_err:binop_operand_type:>:integer?:integer")

        q = "query q(x: integer?) = x == null or x > 5;"
        chkQueryEx(q, 6, "boolean[true]")
        chkQueryEx(q, 5, "boolean[false]")
        chkQueryEx(q, null as Long?, "boolean[true]")
        chkQueryEx("query q(x: integer?) = x != null or x > 5;", 0, "ct_err:binop_operand_type:>:integer?:integer")

        q = "query q(x: integer?, y: integer?) = x == null or y == null or x > y;"
        chkQueryEx(q, 6, 5, "boolean[true]")
        chkQueryEx(q, 5, 6, "boolean[false]")
        chkQueryEx(q, null as Long?, 5, "boolean[true]")
        chkQueryEx(q, 5, null as Long?, "boolean[true]")
        chkQueryEx(q, null as Long?, null as Long?, "boolean[true]")

        chkQueryEx("query q(x: integer?, y: integer?) = x == null or x > y;", 0, 0,
                "ct_err:binop_operand_type:>:integer:integer?")
        chkQueryEx("query q(x: integer?, y: integer?) = y == null or x > y;", 0, 0,
                "ct_err:binop_operand_type:>:integer?:integer")

        q = "query q(x: integer?) = not (x == null) and x > 5;"
        chkQueryEx(q, 6, "boolean[true]")
        chkQueryEx(q, 5, "boolean[false]")
        chkQueryEx(q, null as Long?, "boolean[false]")

        q = "query q(x: integer?, y: integer?) = not (x == null or y == null) and x > y;"
        chkQueryEx(q, 6, 5, "boolean[true]")
        chkQueryEx(q, 5, 6, "boolean[false]")
        chkQueryEx(q, null as Long?, 5, "boolean[false]")
        chkQueryEx(q, 5, null as Long?, "boolean[false]")
        chkQueryEx(q, null as Long?, null as Long?, "boolean[false]")
    }

    @Test fun testImplicationsOperatorNotNull() {
        chkImplicationsExpr("x!!")
    }

    @Test fun testImplicationsRequire() {
        chkImplicationsExpr("require(x)")
        chkImplicationsExpr("requireNotEmpty(x)")
    }

    private fun chkImplicationsExpr(expr: String) {
        chkEx("{ val x = _nullable(123); return _typeOf(x); }", "text[integer?]")
        chkEx("{ val x = _nullable(123); $expr; return _typeOf(x); }", "text[integer]")
        chkEx("{ val x = _nullable(123); val y = $expr; return _typeOf(x); }", "text[integer]")
        chkEx("{ val x = _nullable(123); val y: integer?; y = $expr; return _typeOf(x); }", "text[integer]")
        chkEx("{ val x = _nullable(123); var y: integer?; y = $expr; return _typeOf(x); }", "text[integer]")

        chkEx("{ val x = _nullable(false); return _typeOf(x); }", "text[boolean?]")
        chkEx("{ val x = _nullable(false); if ($expr) return ''; return _typeOf(x); }", "text[boolean]")
        chkEx("{ val x = _nullable(true); if ($expr) return _typeOf(x); return ''; }", "text[boolean]")
    }

    @Test fun testIf() {
        chkEx("{ val x = _nullable(123); return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ val x = _nullable(123); if (x != null) return x + 1; return -1; }", "int[124]")
        chkEx("{ val x = _nullable(123); if (x == null) return -1; return x + 1; }", "int[124]")

        chkEx("{ val x = _nullable(123); return abs(x); }", "ct_err:expr_call_argtypes:abs:integer?")
        chkEx("{ val x = _nullable(123); if (x == null) return 0; return abs(x); }", "int[123]")
        chkEx("{ val x = _nullable(123); if (x != null) return 0; return abs(x); }", "ct_err:expr_call_argtypes:abs:integer?")
        chkEx("{ val x = _nullable(123); if (x != null) return abs(x); return 0; }", "int[123]")
        chkEx("{ val x = _nullable(123); if (x == null) return abs(x); return 0; }", "ct_err:expr_call_argtypes:abs:integer?")
    }

    @Test fun testIfType() {
        tst.defs = listOf(
                "function f(a: integer?): integer? = a;",
                "function g(a: integer?): rec? = if (a == null) null else rec(a);",
                "record rec { a: integer; }"
        )

        chkEx("{ val x = f(123); return _typeOf(x); }", "text[integer?]")

        chkEx("{ val x = f(123); if (x != null) return _typeOf(x); return _typeOf(x); }", "text[integer]")
        chkEx("{ val x = f(null); if (x != null) return _typeOf(x); return _typeOf(x); }", "text[integer?]")
        chkEx("{ val x = f(123); if (x == null) return _typeOf(x); return _typeOf(x); }", "text[integer]")
        chkEx("{ val x = f(null); if (x == null) return _typeOf(x); return _typeOf(x); }", "text[integer?]")

        chkEx("{ val x = f(123); if (null != x) return _typeOf(x); return _typeOf(x); }", "text[integer]")
        chkEx("{ val x = f(null); if (null != x) return _typeOf(x); return _typeOf(x); }", "text[integer?]")
        chkEx("{ val x = f(123); if (null == x) return _typeOf(x); return _typeOf(x); }", "text[integer]")
        chkEx("{ val x = f(null); if (null == x) return _typeOf(x); return _typeOf(x); }", "text[integer?]")

        chkEx("{ val x = g(123); if (x !== null) return _typeOf(x); return _typeOf(x); }", "text[rec]")
        chkEx("{ val x = g(null); if (x !== null) return _typeOf(x); return _typeOf(x); }", "text[rec?]")
        chkEx("{ val x = g(123); if (x === null) return _typeOf(x); return _typeOf(x); }", "text[rec]")
        chkEx("{ val x = g(null); if (x === null) return _typeOf(x); return _typeOf(x); }", "text[rec?]")

        chkEx("{ val x = g(123); if (null !== x) return _typeOf(x); return _typeOf(x); }", "text[rec]")
        chkEx("{ val x = g(null); if (null !== x) return _typeOf(x); return _typeOf(x); }", "text[rec?]")
        chkEx("{ val x = g(123); if (null === x) return _typeOf(x); return _typeOf(x); }", "text[rec]")
        chkEx("{ val x = g(null); if (null === x) return _typeOf(x); return _typeOf(x); }", "text[rec?]")
    }

    @Test fun testIfBlockVar() {
        chkEx("{ val x = _nullable_int(123); if (x != null) { val y = x; return _typeOf(y); } return ''; }",
                "text[integer]")

        chkEx("{ val x = _nullable_int(null); if (x == null) { val y = x; return _typeOf(y); } return ''; }",
                "text[integer?]")

        chkEx("{ val x = _nullable_int(null); if (x != null) return ''; val y = x; return _typeOf(y); }",
                "text[integer?]")

        chkEx("{ val x = _nullable_int(123); if (x == null) return ''; val y = x; return _typeOf(y); }",
                "text[integer]")
    }

    @Test fun testIfExpr() {
        chkQueryEx("query q(x: integer?) = x + 1;", 0, "ct_err:binop_operand_type:+:integer?:integer")
        chkQueryEx("query q(x: integer?) = if (x == null) x + 1 else 0;", 0, "ct_err:binop_operand_type:+:integer?:integer")

        chkQueryEx("query q(x: integer?) = _typeOf(x + 1);", 0, "ct_err:binop_operand_type:+:integer?:integer")
        chkQueryEx("query q(x: integer?) = _typeOf(if (x == null) 0 else x + 1);", 0, "text[integer]")
        chkQueryEx("query q(x: integer?) = _typeOf(if (x == null) null else x + 1);", 0, "text[integer?]")

        var q = "query q(x: integer?) = if (x == null) 0 else x + 1;"
        chkQueryEx(q, null as Long?, "int[0]")
        chkQueryEx(q, 123, "int[124]")

        q = "query q(x: integer?) = if (x != null) x + 1 else 0;"
        chkQueryEx(q, null as Long?, "int[0]")
        chkQueryEx(q, 123, "int[124]")

        chkQueryEx("query q(x: integer?, y: integer?) = x + y;", 0, "ct_err:binop_operand_type:+:integer?:integer?")
        chkQueryEx("query q(x: integer?, y: integer?) = if (x != null) x + y else 0;", 0,
                "ct_err:binop_operand_type:+:integer:integer?")
        chkQueryEx("query q(x: integer?, y: integer?) = if (y != null) x + y else 0;", 0,
                "ct_err:binop_operand_type:+:integer?:integer")

        q = "query q(x: integer?, y: integer?) = if (x != null) if (y != null) x + y else 1 else 0;"
        chkQueryEx(q, null as Long?, null as Long?, "int[0]")
        chkQueryEx(q, null as Long?, 456, "int[0]")
        chkQueryEx(q, 123, null as Long?, "int[1]")
        chkQueryEx(q, 123, 456, "int[579]")

        q = "query q(x: integer?, y: integer?) = if (x != null and y != null) x + y else 0;"
        chkQueryEx(q, null as Long?, null as Long?, "int[0]")
        chkQueryEx(q, null as Long?, 456, "int[0]")
        chkQueryEx(q, 123, null as Long?, "int[0]")
        chkQueryEx(q, 123, 456, "int[579]")

        q = "query q(x: integer?, y: integer?) = if (x == null) 0 else if (y == null) 1 else x + y;"
        chkQueryEx(q, null as Long?, null as Long?, "int[0]")
        chkQueryEx(q, null as Long?, 456, "int[0]")
        chkQueryEx(q, 123, null as Long?, "int[1]")
        chkQueryEx(q, 123, 456, "int[579]")
    }

    @Test fun testWhen() {
        chkEx("{ val x = _nullable(123); return x + 1; }", "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ val x = _nullable(123); when { x != null -> return x + 1; } return -1; }", "int[124]")
        chkEx("{ val x = _nullable(123); when { x == null -> return -1; } return x + 1; }", "int[124]")

        chkEx("{ val x = _nullable(123); return abs(x); }", "ct_err:expr_call_argtypes:abs:integer?")
        chkEx("{ val x = _nullable(123); when { x == null -> return 0; } return abs(x); }", "int[123]")
        chkEx("{ val x = _nullable(123); when { x != null -> return 0; } return abs(x); }", "ct_err:expr_call_argtypes:abs:integer?")
        chkEx("{ val x = _nullable(123); when { x != null -> return abs(x); } return 0; }", "int[123]")
        chkEx("{ val x = _nullable(123); when { x == null -> return abs(x); } return 0; }", "ct_err:expr_call_argtypes:abs:integer?")
    }

    @Test fun testWhenMultipleConditions() {
        val err = "ct_err:binop_operand_type:+:integer?:integer"

        chkEx("{ val x = _nullable(123); return x + 1; }", err)
        chkEx("{ val x = _nullable(123); when { x != null -> return x + 1; } return -1; }", "int[124]")

        chkEx("{ val x = _nullable(123); when { x != null, abs(5)>0 -> return x + 1; } return -1; }", err)
        chkEx("{ val x = _nullable(123); when { abs(5)>0, x != null -> return x + 1; } return -1; }", err)

        chkEx("{ val x = _nullable(123); when { x == null -> {} else -> return x + 1; } return -1; }", "int[124]")
        chkEx("{ val x = _nullable(123); when { x == null, abs(5)<0 -> {} else -> return x + 1; } return -1; }", "int[124]")
        chkEx("{ val x = _nullable(123); when { abs(5)<0, x == null -> {} else -> return x + 1; } return -1; }", "int[124]")

        chkEx("{ val x = _nullable(123); when { x == null -> return 0; } return x + 1; }", "int[124]")
        chkEx("{ val x = _nullable(123); when { x == null, abs(5)<0 -> return 0; } return x + 1; }", "int[124]")
        chkEx("{ val x = _nullable(123); when { abs(5)<0, x == null -> return 0; } return x + 1; }", "int[124]")
    }

    @Test fun testWhenExpr() {
        chkQueryEx("query q(x: integer?) = x + 1;", 0, "ct_err:binop_operand_type:+:integer?:integer")

        chkQueryEx("query q(x: integer?) = _typeOf(x + 1);", 0, "ct_err:binop_operand_type:+:integer?:integer")
        chkQueryEx("query q(x: integer?) = _typeOf(when { x == null -> 0; else -> x + 1 });", 0, "text[integer]")
        chkQueryEx("query q(x: integer?) = _typeOf(when { x == null -> null; else -> x + 1 });", 0, "text[integer?]")
        chkQueryEx("query q(x: integer?) = _typeOf(when(x) { null -> 0; else -> x + 1 });", 0, "text[integer]")
        chkQueryEx("query q(x: integer?) = _typeOf(when(x) { null -> null; else -> x + 1 });", 0, "text[integer?]")

        chkQueryEx("query q(x: integer?) = when { x == null -> 0; else -> x + 1 };", 123, "int[124]")
        chkQueryEx("query q(x: integer?) = when { x != null -> x + 1; else -> 0 };", 123, "int[124]")

        chkQueryEx("query q(x: integer?, y: integer?) = x + y;", 0, "ct_err:binop_operand_type:+:integer?:integer?")
        chkQueryEx("query q(x: integer?, y: integer?) = when { x != null -> x + y; else -> 0 };", 0,
                "ct_err:binop_operand_type:+:integer:integer?")
        chkQueryEx("query q(x: integer?, y: integer?) = when { y != null -> x + y; else -> 0 };", 0,
                "ct_err:binop_operand_type:+:integer?:integer")

        chkQueryEx("query q(x: integer?, y: integer?) = when { x != null -> when { y != null -> x + y; else -> 1 }; else -> 0 };",
                123, 456, "int[579]")

        chkQueryEx("query q(x: integer?, y: integer?) = when { x != null and y != null -> x + y; else -> 0 };",
                123, 456, "int[579]")

        chkQueryEx("query q(x: integer?, y: integer?) = when { x == null -> 0; else -> when { y == null -> 1; else -> x + y }};",
                123, 456, "int[579]")
    }

    @Test fun testIfReturn() {
        tst.strictToString = false
        chkEx("{ val x = _nullable_int(123); return _typeOf(x); }", "integer?")
        chkEx("{ val x = _nullable_int(123); if (x == null) return ''; return _typeOf(x); }", "integer")
        chkEx("{ val x = _nullable_int(null); if (x != null) return ''; return _typeOf(x); }", "integer?")
        chkEx("{ val x = _nullable_int(null); if (x == null) {} else return ''; return _typeOf(x); }", "integer?")
        chkEx("{ val x = _nullable_int(123); if (x != null) {} else return ''; return _typeOf(x); }", "integer")
    }

    @Test fun testWhenReturn() {
        tst.strictToString = false

        chkEx("{ val x = _nullable_int(123); return _typeOf(x); }", "integer?")
        chkEx("{ val x = _nullable_int(123); when { x == null -> return ''; } return _typeOf(x); }", "integer")
        chkEx("{ val x = _nullable_int(null); when { x != null -> return ''; } return _typeOf(x); }", "integer?")
        chkEx("{ val x = _nullable_int(null); when { x == null -> {} else -> return ''; } return _typeOf(x); }", "integer?")
        chkEx("{ val x = _nullable_int(123); when { x != null -> {} else -> return ''; } return _typeOf(x); }", "integer")

        chkEx("{ val x = _nullable_int(123); when(x) { null -> return ''; } return _typeOf(x); }", "integer")
        chkEx("{ val x = _nullable_int(123); when(x) { 456 -> return ''; } return _typeOf(x); }", "integer?")
        chkEx("{ val x = _nullable_int(123); when(x) { 456 -> return ''; null -> return ''; } return _typeOf(x); }", "integer")
        chkEx("{ val x = _nullable_int(123); when(x) { 456, null -> return ''; } return _typeOf(x); }", "integer")
    }

    @Test fun testExists() {
        tst.strictToString = false
        chkEx("{ val x = _nullable_int(123); return _typeOf(x); }", "integer?")
        chkEx("{ val x = _nullable_int(123); if (exists(x)) return _typeOf(x); return ''; }", "integer")
        chkEx("{ val x = _nullable_int(123); if (not exists(x)) return ''; return _typeOf(x); }", "integer")
        chkEx("{ val x = _nullable_int(123); if (exists(x)) return _typeOf(x); return ''; }", "integer")
        chkEx("{ val x = _nullable_int(null); if (not exists(x)) return _typeOf(x); return ''; }", "integer?")
    }

    @Test fun testList() {
        tst.strictToString = false
        chkEx("{ val x: integer? = _nullable_int(123); return _typeOf([x]); }", "list<integer?>")
        chkEx("{ val x: integer? = 123; return _typeOf([x]); }", "list<integer>")
        chkEx("{ val x: integer? = null; return _typeOf([x]); }", "list<integer?>")
        chkEx("{ val x: integer? = _nullable_int(123); if (x != null) return _typeOf([x]); return ''; }", "list<integer>")
    }

    @Test fun testWhileNull() {
        tst.defs = listOf(
                "record node { next: node?; value: integer; }",
                "function make_nodes(): node? = node(123, node(456, node(789, null)));"
        )

        chkEx("{ var p = make_nodes(); while (p != null) { p = p.next; } return p; }", "null")

        chkEx("{ var p = make_nodes(); var s = 0; while (p != null) { s += p.value; p = p.next; } return s; }", "int[1368]")

        chkEx("{ var p = make_nodes(); var s = 0; while (p != null) { p = p.next; s += p.value; } return s; }",
                "ct_err:expr_mem_null:value")

        chkEx("{ var p = make_nodes(); var s = 0; while (p == null) { s += p.value; p = p.next; } return s; }",
                "ct_err:expr_mem_null:value")

        chkEx("{ var p = make_nodes(); var s = 0; while (s == 0) { s += p.value; p = p.next; } return s; }",
                "ct_err:expr_mem_null:value")

        chkEx("{ var p = make_nodes(); while (p != null) { p = p.next; } return _typeOf(p); }", "text[node?]")
        chkEx("{ var p = make_nodes(); p!!; return _typeOf(p); }", "text[node]")
        chkEx("{ var p = make_nodes(); p!!; while (p != null) { p = p.next; } return _typeOf(p); }", "text[node?]")
    }

    @Test fun testLoopVarModification() {
        chkEx("{ var x: integer? = 123; var b = true; while (x > 0 and b) { b = false; } return 0; }", "int[0]")
        chkEx("{ var x: integer? = 123; var b = true; while (x > 0 and b) { b = false; x = null; } return 0; }",
                "ct_err:binop_operand_type:>:integer?:integer")
        chkEx("{ var x: integer? = 123; var b = true; while (x > 0 and b) { b = false; x = 123; } return 0; }",
                "ct_err:binop_operand_type:>:integer?:integer")

        chkEx("{ var x: integer? = 123; var b = true; while (b) { print(x+1); b = false; } return 0; }", "int[0]")
        chkEx("{ var x: integer? = 123; var b = true; while (b) { print(x+1); x = 123; b = false; } return 0; }",
                "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ var x: integer? = 123; var b = true; while (b) { x = 123; print(x+1); b = false; } return 0; }", "int[0]")

        chkEx("{ var x: integer? = 123; for (k in [0]) { print(x+1); } return 0; }", "int[0]")
        chkEx("{ var x: integer? = 123; for (k in [0]) { print(x+1); x = 123; } return 0; }",
                "ct_err:binop_operand_type:+:integer?:integer")
        chkEx("{ var x: integer? = 123; for (k in [0]) { x = 123; print(x+1); } return 0; }", "int[0]")
    }

    @Test fun testDefiniteFactNullEquality() {
        tst.defs = listOf("record rec { a: integer; }", "function f(r: rec?): rec? = r;")
        chkDefiniteFactNullEquality("==", true)
        chkDefiniteFactNullEquality("!=", false)
        chkDefiniteFactNullEquality("===", true)
        chkDefiniteFactNullEquality("!==", false)
    }

    private fun chkDefiniteFactNullEquality(op: String, eq: Boolean) {
        val resNull = "boolean[$eq]"
        val resNotNull = "boolean[${!eq}]"
        chkDefiniteFactExpr("x $op null", resNull, resNotNull, "ct_err:binop_operand_type:$op:rec:null")
        chkDefiniteFactExpr("null $op x", resNull, resNotNull, "ct_err:binop_operand_type:$op:null:rec")
    }

    @Test fun testDefiniteFactElvis() {
        tst.defs = listOf("record rec { a: integer; }", "function f(r: rec?): rec? = r;")
        chkDefiniteFactExpr("x ?: rec(-1)", "rec[a=int[-1]]", "rec[a=int[123]]", "ct_err:binop_operand_type:?::rec:rec")
    }

    @Test fun testDefiniteFactSafeMember() {
        tst.defs = listOf("record rec { a: integer; }", "function f(r: rec?): rec? = r;")
        chkDefiniteFactExpr("x?.a", "null", "int[123]", "ct_err:expr_safemem_type:rec")
    }

    @Test fun testDefiniteFactExists() {
        tst.defs = listOf("record rec { a: integer; }", "function f(r: rec?): rec? = r;")
        chkDefiniteFactExpr("exists(x)", "boolean[false]", "boolean[true]", "ct_err:expr_call_argtypes:exists:rec")
        chkDefiniteFactExpr("not exists(x)", "boolean[true]", "boolean[false]", "ct_err:expr_call_argtypes:exists:rec")
    }

    private fun chkDefiniteFactExpr(expr: String, resNull: String, resNotNull: String, ctErr: String) {
        chkEx("{ val x = rec(123); return $expr; }", ctErr)
        chkWarn()

        chkEx("{ val x: rec? = rec(123); return $expr; }", resNotNull)
        chkWarn("expr_var_null:never:x")

        chkEx("{ val x: rec? = null; return $expr; }", resNull)
        chkWarn("expr_var_null:always:x")

        chkEx("{ val x = f(rec(123)); if (x == null) return null; return $expr; }", resNotNull)
        chkWarn("expr_var_null:never:x")

        chkEx("{ val x = f(null); if (x != null) return null; return $expr; }", resNull)
        chkWarn("expr_var_null:always:x")
    }

    @Test fun testDefiniteFactOperatorNotNull() {
        chkDefiniteFactNullCast("x!!", "ct_err:unop_operand_type:!!:integer", "rt_err:null_value")
    }

    @Test fun testDefiniteFactRequire() {
        chkDefiniteFactNullCast("require(x)", "ct_err:expr_call_argtypes:require:integer", "req_err:null")
        chkDefiniteFactNullCast("requireNotEmpty(x)", "ct_err:expr_call_argtypes:requireNotEmpty:integer", "req_err:null")
    }

    private fun chkDefiniteFactNullCast(expr: String, ctErr: String, rtErr: String) {
        chkEx("{ val x = 123; return $expr; }", ctErr)
        chkWarn()

        chkEx("{ val x: integer? = 123; return $expr; }", "int[123]")
        chkWarn("expr_var_null:never:x")

        chkEx("{ val x: integer? = null; return $expr; }", rtErr)
        chkWarn("expr_var_null:always:x")

        chkEx("{ val x = _nullable(123); if (x == null) return 0; return $expr; }", "int[123]")
        chkWarn("expr_var_null:never:x")

        chkEx("{ val x = _nullable_int(null); if (x != null) return 0; return $expr; }", rtErr)
        chkWarn("expr_var_null:always:x")
    }

    // null-dependent: when (x) { null -> }

    // contradictory operations:
    // if (x != null) if (x == null)
    // if (x!! > 0) if (x == null)
    // if (x!! > 0 and x == null)

    // record fields: if (x.y.z == null) return; // x.y.z is not nullable from now
    // if (f?.x?.y?.z != null) print(f.x.y.z)
}
