package net.postchain.rell

import net.postchain.rell.test.BaseResourcefulTest
import org.junit.Test
import kotlin.test.assertEquals

abstract class AbstractOpTest: BaseResourcefulTest() {
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
        chkCmpCommon(vInt(55), vInt(122), vInt(123), vInt(124), vInt(456))
    }

    @Test fun testCmpRowid() {
        chkCmpCommon(vRowid(55), vRowid(122), vRowid(123), vRowid(124), vRowid(456))
    }

    @Test fun testCmpText() {
        chkCmpCommon(vText("Apple"), vText("Helln"), vText("Hello"), vText("Hellp"), vText("World"))
        chkCmpCommon(vText("HELLO"), vText("Hello"))
        chkCmpCommon(vText("Hello"), vText("hello"))
    }

    @Test fun testCmpByteArray() {
        chkCmpCommon(vBytes("0000"), vBytes("0122"), vBytes("0123"), vBytes("0124"), vBytes("abcd"))
        chkCmpCommon(vBytes("beef"), vBytes("cafd"), vBytes("cafe"), vBytes("caff"), vBytes("dead"))
        chkCmpCommon(vBytes("0123"), vBytes("abcd"))
        chkCmpCommon(vBytes("0123"), vBytes("0123abcd"))
        chkCmpCommon(vBytes("0123"), vBytes("02"))
        chkCmpCommon(vBytes("01"), vBytes("0123"))
        chkCmpCommon(vBytes("00"), vBytes("ff"))
        chkCmpCommon(vBytes(""), vBytes("0123abcd"))
    }

    @Test fun testCmpObject() {
        chkCmpCommon(vObj("user", 1000), vObj("user", 2000))
    }

    private fun chkCmpCommon(vLow: TstVal, vMinusOne: TstVal, v: TstVal, vPlusOne: TstVal, vHigh: TstVal) {
        chkOpBool("==", v, v, true)
        chkOpBool("==", v, vLow, false)
        chkOpBool("==", v, vHigh, false)
        chkOpBool("==", v, vMinusOne, false)
        chkOpBool("==", v, vPlusOne, false)

        chkOpBool("!=", v, v, false)
        chkOpBool("!=", v, vLow, true)
        chkOpBool("!=", v, vHigh, true)
        chkOpBool("!=", v, vMinusOne, true)
        chkOpBool("!=", v, vPlusOne, true)

        chkOpBool("<", v, vLow, false)
        chkOpBool("<", v, vMinusOne, false)
        chkOpBool("<", v, v, false)
        chkOpBool("<", v, vPlusOne, true)
        chkOpBool("<", v, vHigh, true)

        chkOpBool("<=", v, vLow, false)
        chkOpBool("<=", v, vMinusOne, false)
        chkOpBool("<=", v, v, true)
        chkOpBool("<=", v, vPlusOne, true)
        chkOpBool("<=", v, vHigh, true)

        chkOpBool(">", v, vLow, true)
        chkOpBool(">", v, vMinusOne, true)
        chkOpBool(">", v, v, false)
        chkOpBool(">", v, vPlusOne, false)
        chkOpBool(">", v, vHigh, false)

        chkOpBool(">=", v, vLow, true)
        chkOpBool(">=", v, vMinusOne, true)
        chkOpBool(">=", v, v, true)
        chkOpBool(">=", v, vPlusOne, false)
        chkOpBool(">=", v, vHigh, false)
    }

    private fun chkCmpCommon(v1: TstVal, v2: TstVal) {
        chkOpBool("==", v1, v1, true)
        chkOpBool("==", v2, v2, true)
        chkOpBool("==", v1, v2, false)
        chkOpBool("==", v2, v1, false)

        chkOpBool("!=", v1, v1, false)
        chkOpBool("!=", v2, v2, false)
        chkOpBool("!=", v1, v2, true)
        chkOpBool("!=", v2, v1, true)

        chkOpBool("<", v1, v1, false)
        chkOpBool("<", v2, v2, false)
        chkOpBool("<", v1, v2, true)
        chkOpBool("<", v2, v1, false)

        chkOpBool("<=", v1, v1, true)
        chkOpBool("<=", v2, v2, true)
        chkOpBool("<=", v1, v2, true)
        chkOpBool("<=", v2, v1, false)

        chkOpBool(">", v1, v1, false)
        chkOpBool(">", v2, v2, false)
        chkOpBool(">", v1, v2, false)
        chkOpBool(">", v2, v1, true)

        chkOpBool(">=", v1, v1, true)
        chkOpBool(">=", v2, v2, true)
        chkOpBool(">=", v1, v2, false)
        chkOpBool(">=", v2, v1, true)
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

        chkOp("+", vInt(9223372036854775806), vInt(1), "int[9223372036854775807]")
        chkIntOverflow("+", 9223372036854775807, 1)
    }

    @Test fun testMinus() {
        chkOp("-", vInt(123), vInt(456), "int[-333]")
        chkOp("-", vInt(456), vInt(123), "int[333]")
        chkOp("-", vInt(12345), vInt(67890), "int[-55545]")
        chkOp("-", vInt(67890), vInt(12345), "int[55545]")

        chkOp("-", vInt(123), "int[-123]")
        chkOp("-", vInt(-123), "int[123]")

        chkOp("-", vInt(-9223372036854775807), vInt(1), "int[-9223372036854775808]")
        chkIntOverflow("-", -9223372036854775807-1, 1)
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
        chkOp("/", vInt(1), vInt(0), integerDivZeroMsg(1))
        chkOp("/", vInt(123456789), vInt(0), integerDivZeroMsg(123456789))
    }

    @Test fun testMul() {
        chkOp("*", vInt(123), vInt(456), "int[56088]")
        chkOp("*", vInt(123), vInt(0), "int[0]")
        chkOp("*", vInt(0), vInt(456), "int[0]")
        chkOp("*", vInt(123), vInt(1), "int[123]")
        chkOp("*", vInt(1), vInt(456), "int[456]")
        chkOp("*", vInt(-1), vInt(456), "int[-456]")

        chkOp("*", vInt(4294967296-1), vInt(2147483648), "int[9223372034707292160]")
        chkIntOverflow("*", 4294967296, 2147483648)
    }

    @Test fun testMod() {
        chkOp("%", vInt(123), vInt(456), "int[123]")
        chkOp("%", vInt(456), vInt(123), "int[87]")
        chkOp("%", vInt(1000000), vInt(2), "int[0]")
        chkOp("%", vInt(1000000), vInt(3), "int[1]")
        chkOp("%", vInt(1000000), vInt(9999), "int[100]")
    }

    private fun chkIntOverflow(op: String, left: Long, right: Long) {
        chkOp(op, vInt(left), vInt(right), integerOverflowMsg(op, left, right))
        chkOp(op, vInt(right), vInt(left), integerOverflowMsg(op, right, left))
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

        chkErrSubBin("+", "integer", "boolean", "user")
        chkErrSubBin("-", "integer", "boolean", "text", "user")
        chkErrSubBin("*", "integer", "boolean", "text", "user")
        chkErrSubBin("/", "integer", "boolean", "text", "user")
        chkErrSubBin("%", "integer", "boolean", "text", "user")
        chkErrSubBin("and", "boolean", "integer", "text", "user")
        chkErrSubBin("or", "boolean", "integer", "text", "user")

        chkErrSubUn("+", "boolean", "text", "user", "rowid")
        chkErrSubUn("-", "boolean", "text", "user", "rowid")
        chkErrSubUn("not", "integer", "text", "user", "rowid")
    }

    private fun chkErrSub(op: String) {
        chkOpErr("boolean $op integer")
        chkOpErr("boolean $op text")
        chkOpErr("boolean $op rowid")
        chkOpErr("boolean $op user")

        chkOpErr("integer $op boolean")
        chkOpErr("integer $op text")
        chkOpErr("integer $op rowid")
        chkOpErr("integer $op user")

        chkOpErr("text $op boolean")
        chkOpErr("text $op integer")
        chkOpErr("text $op rowid")
        chkOpErr("text $op user")

        chkOpErr("user $op boolean")
        chkOpErr("user $op integer")
        chkOpErr("user $op rowid")
        chkOpErr("user $op text")
        chkOpErr("user $op company")

        chkOpErr("rowid $op boolean")
        chkOpErr("rowid $op integer")
        chkOpErr("rowid $op text")
        chkOpErr("rowid $op user")
    }

    private fun chkErrSubBin(op: String, goodType: String, vararg badTypes: String) {
        for (badType in badTypes) {
            chkOpErr(op, goodType, badType)
            chkOpErr(op, badType, goodType)
            chkOpErr(op, badType, badType)
        }
    }

    private fun chkErrSubUn(op: String, vararg types: String) {
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
        chkExpr("#0.size()", "int[0]", vText(""))
        chkExpr("#0.size()", "int[5]", vText("Hello"))

        chkExpr("#0.size()", "int[0]", vBytes(""))
        chkExpr("#0.size()", "int[5]", vBytes("123456789A"))
    }

    @Test fun testIf() {
        chkExpr("if (#0) 1 else 2", "int[1]", vBool(true))
        chkExpr("if (#0) 1 else 2", "int[2]", vBool(false))
        chkExpr("if (#0) 'Yes' else 'No'", "text[Yes]", vBool(true))
        chkExpr("if (#0) 'Yes' else 'No'", "text[No]", vBool(false))

        chkExpr("if (#0) #1 else #2", "int[123]", vBool(true), vInt(123), vInt(456))
        chkExpr("if (#0) #1 else #2", "int[456]", vBool(false), vInt(123), vInt(456))
        chkExpr("if (#0) #1 else #2", "text[Yes]", vBool(true), vText("Yes"), vText("No"))
        chkExpr("if (#0) #1 else #2", "text[No]", vBool(false), vText("Yes"), vText("No"))

        chkExprErr("if (true) 'Hello' else 123", listOf(), "ct_err:expr_if_restype:text:integer")
        chkExprErr("if (123) 'A' else 'B'", listOf(), "ct_err:expr_if_cond_type:boolean:integer")
        chkExprErr("if ('Hello') 'A' else 'B'", listOf(), "ct_err:expr_if_cond_type:boolean:text")
        chkExprErr("if (null) 'A' else 'B'", listOf(), "ct_err:expr_if_cond_type:boolean:null")
        chkExprErr("if (unit()) 'A' else 'B'", listOf(), "ct_err:expr_if_cond_type:boolean:unit")
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

    abstract fun integerOverflowMsg(op: String, left: Long, right: Long): String
    abstract fun integerDivZeroMsg(left: Long): String

    abstract fun vBool(v: Boolean): TstVal
    abstract fun vInt(v: Long): TstVal
    abstract fun vText(v: String): TstVal
    abstract fun vBytes(v: String): TstVal
    abstract fun vRowid(v: Long): TstVal
    abstract fun vJson(v: String): TstVal
    abstract fun vObj(cls: String, id: Long): TstVal

    abstract class TstVal
}
