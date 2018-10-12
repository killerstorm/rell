package net.postchain.rell

import org.junit.Test

abstract class AbstractOpTest {
    @Test fun testCmpBoolean() {
        checkOpBool("==", vBool(false), vBool(false), true)
        checkOpBool("==", vBool(false), vBool(true), false)
        checkOpBool("==", vBool(true), vBool(false), false)
        checkOpBool("==", vBool(true), vBool(true), true)

        checkOpBool("!=", vBool(false), vBool(false), false)
        checkOpBool("!=", vBool(false), vBool(true), true)
        checkOpBool("!=", vBool(true), vBool(false), true)
        checkOpBool("!=", vBool(true), vBool(true), false)

        checkOpErr("boolean < boolean")
        checkOpErr("boolean > boolean")
        checkOpErr("boolean <= boolean")
        checkOpErr("boolean >= boolean")
    }

    @Test fun testCmpInteger() {
        checkOpBool("==", vInt(123), vInt(123), true)
        checkOpBool("==", vInt(123), vInt(456), false)
        checkOpBool("==", vInt(123), vInt(122), false)
        checkOpBool("==", vInt(123), vInt(124), false)

        checkOpBool("!=", vInt(123), vInt(123), false)
        checkOpBool("!=", vInt(123), vInt(456), true)
        checkOpBool("!=", vInt(123), vInt(122), true)
        checkOpBool("!=", vInt(123), vInt(124), true)

        checkOpBool("<", vInt(123), vInt(55), false)
        checkOpBool("<", vInt(123), vInt(123), false)
        checkOpBool("<", vInt(123), vInt(124), true)
        checkOpBool("<", vInt(123), vInt(456), true)

        checkOpBool("<=", vInt(123), vInt(55), false)
        checkOpBool("<=", vInt(123), vInt(123), true)
        checkOpBool("<=", vInt(123), vInt(124), true)
        checkOpBool("<=", vInt(123), vInt(456), true)

        checkOpBool(">", vInt(123), vInt(55), true)
        checkOpBool(">", vInt(123), vInt(122), true)
        checkOpBool(">", vInt(123), vInt(123), false)
        checkOpBool(">", vInt(123), vInt(456), false)

        checkOpBool(">=", vInt(123), vInt(55), true)
        checkOpBool(">=", vInt(123), vInt(122), true)
        checkOpBool(">=", vInt(123), vInt(123), true)
        checkOpBool(">=", vInt(123), vInt(456), false)
    }

    @Test fun testCmpText() {
        checkOpBool("==", vText("Hello"), vText("Hello"), true)
        checkOpBool("==", vText("Hello"), vText("HELLO"), false)
        checkOpBool("==", vText("Hello"), vText("hello"), false)
        checkOpBool("==", vText("Hello"), vText("World"), false)

        checkOpBool("!=", vText("Hello"), vText("Hello"), false)
        checkOpBool("!=", vText("Hello"), vText("HELLO"), true)
        checkOpBool("!=", vText("Hello"), vText("hello"), true)
        checkOpBool("!=", vText("Hello"), vText("World"), true)

        checkOpBool("<", vText("Hello"), vText("hello"), true)
        checkOpBool("<", vText("Hello"), vText("HELLO"), false)
        checkOpBool("<", vText("Hello"), vText("Apple"), false)
        checkOpBool("<", vText("Hello"), vText("Hello"), false)
        checkOpBool("<", vText("Hello"), vText("Hellp"), true)
        checkOpBool("<", vText("Hello"), vText("World"), true)

        checkOpBool("<=", vText("Hello"), vText("hello"), true)
        checkOpBool("<=", vText("Hello"), vText("HELLO"), false)
        checkOpBool("<=", vText("Hello"), vText("Apple"), false)
        checkOpBool("<=", vText("Hello"), vText("Hello"), true)
        checkOpBool("<=", vText("Hello"), vText("Hellp"), true)
        checkOpBool("<=", vText("Hello"), vText("World"), true)

        checkOpBool(">", vText("Hello"), vText("hello"), false)
        checkOpBool(">", vText("Hello"), vText("HELLO"), true)
        checkOpBool(">", vText("Hello"), vText("Apple"), true)
        checkOpBool(">", vText("Hello"), vText("Helln"), true)
        checkOpBool(">", vText("Hello"), vText("Hello"), false)
        checkOpBool(">", vText("Hello"), vText("Hellp"), false)
        checkOpBool(">", vText("Hello"), vText("World"), false)

        checkOpBool(">=", vText("Hello"), vText("hello"), false)
        checkOpBool(">=", vText("Hello"), vText("HELLO"), true)
        checkOpBool(">=", vText("Hello"), vText("Apple"), true)
        checkOpBool(">=", vText("Hello"), vText("Helln"), true)
        checkOpBool(">=", vText("Hello"), vText("Hello"), true)
        checkOpBool(">=", vText("Hello"), vText("Hellp"), false)
        checkOpBool(">=", vText("Hello"), vText("World"), false)
    }

    @Test fun testCmpObject() {
        checkOpBool("==", vObj("user", 1000), vObj("user", 1000), true)
        checkOpBool("==", vObj("user", 1000), vObj("user", 2000), false)
        checkOpBool("==", vObj("user", 2000), vObj("user", 1000), false)

        checkOpBool("!=", vObj("user", 1000), vObj("user", 1000), false)
        checkOpBool("!=", vObj("user", 1000), vObj("user", 2000), true)
        checkOpBool("!=", vObj("user", 2000), vObj("user", 1000), true)

        checkOpBool("<", vObj("user", 1000), vObj("user", 1000), false)
        checkOpBool("<", vObj("user", 1000), vObj("user", 2000), true)
        checkOpBool("<", vObj("user", 2000), vObj("user", 1000), false)

        checkOpBool("<=", vObj("user", 1000), vObj("user", 1000), true)
        checkOpBool("<=", vObj("user", 1000), vObj("user", 2000), true)
        checkOpBool("<=", vObj("user", 2000), vObj("user", 1000), false)

        checkOpBool(">", vObj("user", 1000), vObj("user", 1000), false)
        checkOpBool(">", vObj("user", 1000), vObj("user", 2000), false)
        checkOpBool(">", vObj("user", 2000), vObj("user", 1000), true)

        checkOpBool(">=", vObj("user", 1000), vObj("user", 1000), true)
        checkOpBool(">=", vObj("user", 1000), vObj("user", 2000), false)
        checkOpBool(">=", vObj("user", 2000), vObj("user", 1000), true)
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
        checkOpErr("boolean $op integer")
        checkOpErr("boolean $op text")
        checkOpErr("boolean $op user")
        checkOpErr("integer $op boolean")
        checkOpErr("integer $op text")
        checkOpErr("integer $op user")
        checkOpErr("text $op boolean")
        checkOpErr("text $op integer")
        checkOpErr("text $op user")
        checkOpErr("user $op boolean")
        checkOpErr("user $op integer")
        checkOpErr("user $op text")
        checkOpErr("user $op company")
    }

    @Test fun testAnd() {
        checkOpBool("and", vBool(false), vBool(false), false)
        checkOpBool("and", vBool(false), vBool(true), false)
        checkOpBool("and", vBool(true), vBool(false), false)
        checkOpBool("and", vBool(true), vBool(true), true)
    }

    @Test fun testOr() {
        checkOpBool("or", vBool(false), vBool(false), false)
        checkOpBool("or", vBool(false), vBool(true), true)
        checkOpBool("or", vBool(true), vBool(false), true)
        checkOpBool("or", vBool(true), vBool(true), true)
    }

    @Test fun testNot() {
        checkOpBool("not", vBool(false), true)
        checkOpBool("not", vBool(true), false)
    }

    @Test fun testPlus() {
        checkOp("+", vInt(123), vInt(456), "int[579]")
        checkOp("+", vInt(12345), vInt(67890), "int[80235]")

        checkOp("+", vText("Hello"), vText("World"), "text[HelloWorld]")

        checkOp("+", vInt(123), "int[123]")
        checkOp("+", vInt(-123), "int[-123]")
    }

    @Test fun testMinus() {
        checkOp("-", vInt(123), vInt(456), "int[-333]")
        checkOp("-", vInt(456), vInt(123), "int[333]")
        checkOp("-", vInt(12345), vInt(67890), "int[-55545]")
        checkOp("-", vInt(67890), vInt(12345), "int[55545]")

        checkOp("-", vInt(123), "int[-123]")
        checkOp("-", vInt(-123), "int[123]")
    }

    @Test fun testDiv() {
        checkOp("/", vInt(123), vInt(456), "int[0]")
        checkOp("/", vInt(456), vInt(123), "int[3]")
        checkOp("/", vInt(1000000), vInt(1), "int[1000000]")
        checkOp("/", vInt(1000000), vInt(2), "int[500000]")
        checkOp("/", vInt(1000000), vInt(9), "int[111111]")
        checkOp("/", vInt(1000000), vInt(10), "int[100000]")
        checkOp("/", vInt(1000000), vInt(11), "int[90909]")
        checkOp("/", vInt(1000000), vInt(333333), "int[3]")
        checkOp("/", vInt(1000000), vInt(333334), "int[2]")
        checkOp("/", vInt(1000000), vInt(499999), "int[2]")
        checkOp("/", vInt(1000000), vInt(500000), "int[2]")
        checkOp("/", vInt(1000000), vInt(500001), "int[1]")
    }

    @Test fun testMul() {
        checkOp("*", vInt(123), vInt(456), "int[56088]")
        checkOp("*", vInt(123), vInt(0), "int[0]")
        checkOp("*", vInt(0), vInt(456), "int[0]")
        checkOp("*", vInt(123), vInt(1), "int[123]")
        checkOp("*", vInt(1), vInt(456), "int[456]")
        checkOp("*", vInt(-1), vInt(456), "int[-456]")
    }

    @Test fun testMod() {
        checkOp("%", vInt(123), vInt(456), "int[123]")
        checkOp("%", vInt(456), vInt(123), "int[87]")
        checkOp("%", vInt(1000000), vInt(2), "int[0]")
        checkOp("%", vInt(1000000), vInt(3), "int[1]")
        checkOp("%", vInt(1000000), vInt(9999), "int[100]")
    }

    @Test fun testErr() {
        chkErrSub("+")
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
        checkOpErr("boolean $op integer")
        checkOpErr("boolean $op text")
        checkOpErr("boolean $op user")
        checkOpErr("integer $op boolean")
        checkOpErr("integer $op text")
        checkOpErr("integer $op user")
        checkOpErr("text $op boolean")
        checkOpErr("text $op integer")
        checkOpErr("text $op user")
        checkOpErr("user $op boolean")
        checkOpErr("user $op integer")
        checkOpErr("user $op text")
        checkOpErr("user $op company")
    }

    private fun chkErrSub2(op: String, vararg types: String) {
        for (type in types) {
            checkOpErr(op, type, type)
        }
    }

    private fun chkErrSub3(op: String, vararg types: String) {
        for (type in types) {
            checkOpErr(op, type)
        }
    }

    private fun checkOpErr(expr: String) {
        val (left, op, right) = expr.split(" ")
        checkOpErr(op, left, right)
    }

    abstract fun checkOpBool(op: String, left: TstVal, right: TstVal, expected: Boolean)
    abstract fun checkOpBool(op: String, right: TstVal, expected: Boolean)
    abstract fun checkOp(op: String, left: TstVal, right: TstVal, expected: String)
    abstract fun checkOp(op: String, right: TstVal, expected: String)
    abstract fun checkOpErr(op: String, left: String, right: String)
    abstract fun checkOpErr(op: String, right: String)

    abstract fun vBool(v: Boolean): TstVal
    abstract fun vInt(v: Long): TstVal
    abstract fun vText(v: String): TstVal
    abstract fun vObj(cls: String, id: Long): TstVal

    abstract class TstVal
}
