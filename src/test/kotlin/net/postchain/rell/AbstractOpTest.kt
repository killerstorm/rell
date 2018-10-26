package net.postchain.rell

import org.junit.Test
import kotlin.test.assertEquals

abstract class AbstractOpTest {
    @Test fun testCmpBoolean() {
        chkOpBool("==", vBool(false), vBool(false), true)
        chkOpBool("==", vBool(false), vBool(true), false)
        chkOpBool("==", vBool(true), vBool(false), false)
        chkOpBool("==", vBool(true), vBool(true), true)

        chkOpBool("!=", vBool(false), vBool(false), false)
        chkOpBool("!=", vBool(false), vBool(true), true)
        chkOpBool("!=", vBool(true), vBool(false), true)
        chkOpBool("!=", vBool(true), vBool(true), false)

        chkOpErr("boolean < boolean")
        chkOpErr("boolean > boolean")
        chkOpErr("boolean <= boolean")
        chkOpErr("boolean >= boolean")
    }

    @Test fun testCmpInteger() {
        chkOpBool("==", vInt(123), vInt(123), true)
        chkOpBool("==", vInt(123), vInt(456), false)
        chkOpBool("==", vInt(123), vInt(122), false)
        chkOpBool("==", vInt(123), vInt(124), false)

        chkOpBool("!=", vInt(123), vInt(123), false)
        chkOpBool("!=", vInt(123), vInt(456), true)
        chkOpBool("!=", vInt(123), vInt(122), true)
        chkOpBool("!=", vInt(123), vInt(124), true)

        chkOpBool("<", vInt(123), vInt(55), false)
        chkOpBool("<", vInt(123), vInt(123), false)
        chkOpBool("<", vInt(123), vInt(124), true)
        chkOpBool("<", vInt(123), vInt(456), true)

        chkOpBool("<=", vInt(123), vInt(55), false)
        chkOpBool("<=", vInt(123), vInt(123), true)
        chkOpBool("<=", vInt(123), vInt(124), true)
        chkOpBool("<=", vInt(123), vInt(456), true)

        chkOpBool(">", vInt(123), vInt(55), true)
        chkOpBool(">", vInt(123), vInt(122), true)
        chkOpBool(">", vInt(123), vInt(123), false)
        chkOpBool(">", vInt(123), vInt(456), false)

        chkOpBool(">=", vInt(123), vInt(55), true)
        chkOpBool(">=", vInt(123), vInt(122), true)
        chkOpBool(">=", vInt(123), vInt(123), true)
        chkOpBool(">=", vInt(123), vInt(456), false)
    }

    @Test fun testCmpText() {
        chkOpBool("==", vText("Hello"), vText("Hello"), true)
        chkOpBool("==", vText("Hello"), vText("HELLO"), false)
        chkOpBool("==", vText("Hello"), vText("hello"), false)
        chkOpBool("==", vText("Hello"), vText("World"), false)

        chkOpBool("!=", vText("Hello"), vText("Hello"), false)
        chkOpBool("!=", vText("Hello"), vText("HELLO"), true)
        chkOpBool("!=", vText("Hello"), vText("hello"), true)
        chkOpBool("!=", vText("Hello"), vText("World"), true)

        chkOpBool("<", vText("Hello"), vText("hello"), true)
        chkOpBool("<", vText("Hello"), vText("HELLO"), false)
        chkOpBool("<", vText("Hello"), vText("Apple"), false)
        chkOpBool("<", vText("Hello"), vText("Hello"), false)
        chkOpBool("<", vText("Hello"), vText("Hellp"), true)
        chkOpBool("<", vText("Hello"), vText("World"), true)

        chkOpBool("<=", vText("Hello"), vText("hello"), true)
        chkOpBool("<=", vText("Hello"), vText("HELLO"), false)
        chkOpBool("<=", vText("Hello"), vText("Apple"), false)
        chkOpBool("<=", vText("Hello"), vText("Hello"), true)
        chkOpBool("<=", vText("Hello"), vText("Hellp"), true)
        chkOpBool("<=", vText("Hello"), vText("World"), true)

        chkOpBool(">", vText("Hello"), vText("hello"), false)
        chkOpBool(">", vText("Hello"), vText("HELLO"), true)
        chkOpBool(">", vText("Hello"), vText("Apple"), true)
        chkOpBool(">", vText("Hello"), vText("Helln"), true)
        chkOpBool(">", vText("Hello"), vText("Hello"), false)
        chkOpBool(">", vText("Hello"), vText("Hellp"), false)
        chkOpBool(">", vText("Hello"), vText("World"), false)

        chkOpBool(">=", vText("Hello"), vText("hello"), false)
        chkOpBool(">=", vText("Hello"), vText("HELLO"), true)
        chkOpBool(">=", vText("Hello"), vText("Apple"), true)
        chkOpBool(">=", vText("Hello"), vText("Helln"), true)
        chkOpBool(">=", vText("Hello"), vText("Hello"), true)
        chkOpBool(">=", vText("Hello"), vText("Hellp"), false)
        chkOpBool(">=", vText("Hello"), vText("World"), false)
    }

    @Test fun testCmpByteArray() {
        chkOpBool("==", vBytes("0123abcd"), vBytes("0123abcd"), true)
        chkOpBool("==", vBytes("0123"), vBytes("abcd"), false)

        chkOpBool("!=", vBytes("0123abcd"), vBytes("0123abcd"), false)
        chkOpBool("!=", vBytes("0123"), vBytes("abcd"), true)

        chkOpBool("<", vBytes("0123"), vBytes("abcd"), true)
        chkOpBool("<", vBytes("0123"), vBytes("0124"), true)
        chkOpBool("<", vBytes("0123"), vBytes("0123"), false)
        chkOpBool("<", vBytes("0123"), vBytes("01"), false)
        chkOpBool("<", vBytes("0123"), vBytes("02"), true)
        chkOpBool("<", vBytes("00"), vBytes("ff"), true)
        chkOpBool("<", vBytes("ff"), vBytes("00"), false)
        chkOpBool("<", vBytes(""), vBytes("0123abcd"), true)
        chkOpBool("<", vBytes("0123abcd"), vBytes(""), false)

        chkOpBool("<=", vBytes("0123"), vBytes("abcd"), true)
        chkOpBool("<=", vBytes("0123"), vBytes("0124"), true)
        chkOpBool("<=", vBytes("0123"), vBytes("0123"), true)
        chkOpBool("<=", vBytes("0123"), vBytes("01"), false)
        chkOpBool("<=", vBytes("0123"), vBytes("02"), true)

        chkOpBool(">", vBytes("0123"), vBytes("abcd"), false)
        chkOpBool(">", vBytes("0123"), vBytes("0122"), true)
        chkOpBool(">", vBytes("0123"), vBytes("0123"), false)
        chkOpBool(">", vBytes("0123"), vBytes("00ff"), true)
        chkOpBool(">", vBytes("0123"), vBytes("01"), true)
        chkOpBool(">", vBytes("0123"), vBytes("02"), false)
        chkOpBool(">", vBytes("00"), vBytes("ff"), false)
        chkOpBool(">", vBytes("ff"), vBytes("00"), true)
        chkOpBool(">", vBytes(""), vBytes("0123abcd"), false)
        chkOpBool(">", vBytes("0123abcd"), vBytes(""), true)

        chkOpBool(">=", vBytes("0123"), vBytes("abcd"), false)
        chkOpBool(">=", vBytes("0123"), vBytes("0122"), true)
        chkOpBool(">=", vBytes("0123"), vBytes("0123"), true)
        chkOpBool(">=", vBytes("0123"), vBytes("00ff"), true)
        chkOpBool(">=", vBytes("0123"), vBytes("01"), true)
        chkOpBool(">=", vBytes("0123"), vBytes("02"), false)
    }

    @Test fun testCmpObject() {
        chkOpBool("==", vObj("user", 1000), vObj("user", 1000), true)
        chkOpBool("==", vObj("user", 1000), vObj("user", 2000), false)
        chkOpBool("==", vObj("user", 2000), vObj("user", 1000), false)

        chkOpBool("!=", vObj("user", 1000), vObj("user", 1000), false)
        chkOpBool("!=", vObj("user", 1000), vObj("user", 2000), true)
        chkOpBool("!=", vObj("user", 2000), vObj("user", 1000), true)

        chkOpBool("<", vObj("user", 1000), vObj("user", 1000), false)
        chkOpBool("<", vObj("user", 1000), vObj("user", 2000), true)
        chkOpBool("<", vObj("user", 2000), vObj("user", 1000), false)

        chkOpBool("<=", vObj("user", 1000), vObj("user", 1000), true)
        chkOpBool("<=", vObj("user", 1000), vObj("user", 2000), true)
        chkOpBool("<=", vObj("user", 2000), vObj("user", 1000), false)

        chkOpBool(">", vObj("user", 1000), vObj("user", 1000), false)
        chkOpBool(">", vObj("user", 1000), vObj("user", 2000), false)
        chkOpBool(">", vObj("user", 2000), vObj("user", 1000), true)

        chkOpBool(">=", vObj("user", 1000), vObj("user", 1000), true)
        chkOpBool(">=", vObj("user", 1000), vObj("user", 2000), false)
        chkOpBool(">=", vObj("user", 2000), vObj("user", 1000), true)
    }

    @Test fun testCmpErr() {
        chkCmpErr("==")
        chkCmpErr("!=")
        chkCmpErr("<")
        chkCmpErr(">")
        chkCmpErr("<=")
        chkCmpErr(">=")
    }

    private fun chkCmpErr(op: String) {
        chkOpErr("boolean $op integer")
        chkOpErr("boolean $op text")
        chkOpErr("boolean $op user")
        chkOpErr("integer $op boolean")
        chkOpErr("integer $op text")
        chkOpErr("integer $op user")
        chkOpErr("text $op boolean")
        chkOpErr("text $op integer")
        chkOpErr("text $op user")
        chkOpErr("user $op boolean")
        chkOpErr("user $op integer")
        chkOpErr("user $op text")
        chkOpErr("user $op company")
    }

    @Test fun testAnd() {
        chkOpBool("and", vBool(false), vBool(false), false)
        chkOpBool("and", vBool(false), vBool(true), false)
        chkOpBool("and", vBool(true), vBool(false), false)
        chkOpBool("and", vBool(true), vBool(true), true)
    }

    @Test fun testOr() {
        chkOpBool("or", vBool(false), vBool(false), false)
        chkOpBool("or", vBool(false), vBool(true), true)
        chkOpBool("or", vBool(true), vBool(false), true)
        chkOpBool("or", vBool(true), vBool(true), true)
    }

    @Test fun testNot() {
        chkOpBool("not", vBool(false), true)
        chkOpBool("not", vBool(true), false)
    }

    @Test fun testPlus() {
        chkOp("+", vInt(123), vInt(456), "int[579]")
        chkOp("+", vInt(12345), vInt(67890), "int[80235]")

        chkOp("+", vText("Hello"), vText("World"), "text[HelloWorld]")
        chkOp("+", vBytes("0123456789"), vBytes("abcdef"), "byte_array[0123456789abcdef]")

        chkOp("+", vInt(123), "int[123]")
        chkOp("+", vInt(-123), "int[-123]")

        chkOp("+", vBool(false), vText("Hello"), "text[falseHello]")
        chkOp("+", vText("Hello"), vBool(true), "text[Hellotrue]")
        chkOp("+", vInt(123), vText("Hello"), "text[123Hello]")
        chkOp("+", vText("Hello"), vInt(123), "text[Hello123]")
        chkOp("+", vJson("[{}]"), vText("Hello"), "text[[{}]Hello]")
        chkOp("+", vText("Hello"), vJson("[{}]"), "text[Hello[{}]]")
    }

    @Test fun testMinus() {
        chkOp("-", vInt(123), vInt(456), "int[-333]")
        chkOp("-", vInt(456), vInt(123), "int[333]")
        chkOp("-", vInt(12345), vInt(67890), "int[-55545]")
        chkOp("-", vInt(67890), vInt(12345), "int[55545]")

        chkOp("-", vInt(123), "int[-123]")
        chkOp("-", vInt(-123), "int[123]")
    }

    @Test fun testDiv() {
        chkOp("/", vInt(123), vInt(456), "int[0]")
        chkOp("/", vInt(456), vInt(123), "int[3]")
        chkOp("/", vInt(1000000), vInt(1), "int[1000000]")
        chkOp("/", vInt(1000000), vInt(2), "int[500000]")
        chkOp("/", vInt(1000000), vInt(9), "int[111111]")
        chkOp("/", vInt(1000000), vInt(10), "int[100000]")
        chkOp("/", vInt(1000000), vInt(11), "int[90909]")
        chkOp("/", vInt(1000000), vInt(333333), "int[3]")
        chkOp("/", vInt(1000000), vInt(333334), "int[2]")
        chkOp("/", vInt(1000000), vInt(499999), "int[2]")
        chkOp("/", vInt(1000000), vInt(500000), "int[2]")
        chkOp("/", vInt(1000000), vInt(500001), "int[1]")
    }

    @Test fun testMul() {
        chkOp("*", vInt(123), vInt(456), "int[56088]")
        chkOp("*", vInt(123), vInt(0), "int[0]")
        chkOp("*", vInt(0), vInt(456), "int[0]")
        chkOp("*", vInt(123), vInt(1), "int[123]")
        chkOp("*", vInt(1), vInt(456), "int[456]")
        chkOp("*", vInt(-1), vInt(456), "int[-456]")
    }

    @Test fun testMod() {
        chkOp("%", vInt(123), vInt(456), "int[123]")
        chkOp("%", vInt(456), vInt(123), "int[87]")
        chkOp("%", vInt(1000000), vInt(2), "int[0]")
        chkOp("%", vInt(1000000), vInt(3), "int[1]")
        chkOp("%", vInt(1000000), vInt(9999), "int[100]")
    }

    @Test fun testErr() {
        chkOpErr("boolean + integer")
        chkOpErr("boolean + user")
        chkOpErr("integer + boolean")
        chkOpErr("integer + user")
        chkOpErr("user + boolean")
        chkOpErr("user + integer")
        chkOpErr("user + company")

        chkErrSub("-")
        chkErrSub("*")
        chkErrSub("/")
        chkErrSub("%")
        chkErrSub("and")
        chkErrSub("or")

        chkErrSub2("+", "boolean", "user")
        chkErrSub2("-", "boolean", "text", "user")
        chkErrSub2("*", "boolean", "text", "user")
        chkErrSub2("/", "boolean", "text", "user")
        chkErrSub2("%", "boolean", "text", "user")
        chkErrSub2("and", "integer", "text", "user")
        chkErrSub2("or", "integer", "text", "user")

        chkErrSub3("+", "boolean", "text", "user")
        chkErrSub3("-", "boolean", "text", "user")
        chkErrSub3("not", "integer", "text", "user")
    }

    private fun chkErrSub(op: String) {
        chkOpErr("boolean $op integer")
        chkOpErr("boolean $op text")
        chkOpErr("boolean $op user")
        chkOpErr("integer $op boolean")
        chkOpErr("integer $op text")
        chkOpErr("integer $op user")
        chkOpErr("text $op boolean")
        chkOpErr("text $op integer")
        chkOpErr("text $op user")
        chkOpErr("user $op boolean")
        chkOpErr("user $op integer")
        chkOpErr("user $op text")
        chkOpErr("user $op company")
    }

    private fun chkErrSub2(op: String, vararg types: String) {
        for (type in types) {
            chkOpErr(op, type, type)
        }
    }

    private fun chkErrSub3(op: String, vararg types: String) {
        for (type in types) {
            chkOpErr(op, type)
        }
    }

    @Test fun testFnJson() {
        chkExpr("json(#0)", """json[{"a":10,"b":[1,2,3],"c":{"x":999}}]""",
                vText("""{ "a" : 10, "b" : [1, 2, 3], "c" : { "x" : 999 } }"""))

        chkExpr("#0.str()", "text[{}]", vJson("{}"))
        chkExpr("#0.str()", "text[[]]", vJson("[]"))
        chkExpr("#0.str()", "text[[12345]]", vJson("[12345]"))
        chkExpr("#0.str()", """text[["Hello"]]""", vJson("""["Hello"]"""))
    }

    @Test fun testFnSystem() {
        chkExpr("abs(#0)", "int[12345]", vInt(12345))
        chkExpr("abs(#0)", "int[12345]", vInt(-12345))

        chkExpr("min(#0, #1)", "int[12345]", vInt(12345), vInt(67890))
        chkExpr("min(#0, #1)", "int[12345]", vInt(67890), vInt(12345))
        chkExpr("max(#0, #1)", "int[67890]", vInt(12345), vInt(67890))
        chkExpr("max(#0, #1)", "int[67890]", vInt(67890), vInt(12345))
    }

    @Test fun testFnSystemMember() {
        chkExpr("#0.len()", "int[0]", vText(""))
        chkExpr("#0.len()", "int[5]", vText("Hello"))

        chkExpr("#0.len()", "int[0]", vBytes(""))
        chkExpr("#0.len()", "int[5]", vBytes("123456789A"))
    }

    private fun chkOpErr(expr: String) {
        val (left, op, right) = expr.split(" ")
        chkOpErr(op, left, right)
    }

    private fun chkOpErr(op: String, left: String, right: String) {
        chkExprErr("#0 $op #1", listOf(left, right), "ct_err:binop_operand_type:$op:$left:$right")
    }

    private fun chkOpErr(op: String, right: String) {
        chkExprErr("$op #0", listOf(right), "ct_err:unop_operand_type:$op:$right")
    }

    private fun chkExprErr(expr: String, types: List<String>, expected: String) {
        val actual = compileExpr(expr, types)
        assertEquals(expected, actual)
    }

    private fun chkOpBool(op: String, left: TstVal, right: TstVal, expected: Boolean) {
        chkExpr("#0 $op #1", listOf(left, right), expected)
    }

    private fun chkOpBool(op: String, right: TstVal, expected: Boolean) {
        chkExpr("$op #0", listOf(right), expected)
    }

    fun chkOp(op: String, left: TstVal, right: TstVal, expected: String) {
        chkExpr("#0 $op #1", expected, left, right)
    }

    fun chkOp(op: String, right: TstVal, expected: String) {
        chkExpr("$op #0", expected, right)
    }

    private fun chkExpr(expr: String, expected: String, vararg args: TstVal) {
        val actual = calcExpr(expr, args.toList())
        assertEquals(expected, actual)
    }

    abstract fun chkExpr(expr: String, args: List<TstVal>, expected: Boolean)
    abstract fun calcExpr(expr: String, args: List<TstVal>): String
    abstract fun compileExpr(expr: String, types: List<String>): String

    abstract fun vBool(v: Boolean): TstVal
    abstract fun vInt(v: Long): TstVal
    abstract fun vText(v: String): TstVal
    abstract fun vBytes(v: String): TstVal
    abstract fun vJson(v: String): TstVal
    abstract fun vObj(cls: String, id: Long): TstVal

    abstract class TstVal
}
