package net.postchain.rell

import org.junit.After
import kotlin.test.assertEquals

class AtExprOpTest: AbstractOpTest() {
    private val classDefs = arrayOf(
            "class company { name: text; }",
            "class user { name: text; company; }",
            """ class optest {
                    b1: boolean; b2: boolean;
                    i1: integer; i2: integer;
                    t1: text; t2: text;
                    user1: user; user2: user;
                    company1: company; company2: company;
                }
            """.trimMargin()
    )

    private val inserts = arrayOf(
            Ins.company(100, "Microsoft"),
            Ins.company(200, "Apple"),

            Ins.user(1000, "Bill Gates", 100),
            Ins.user(2000, "Steve Jobs", 200)
    )

    private val defaultValues = mapOf(
            "b1" to "false",
            "b2" to "false",
            "i1" to "0",
            "i2" to "0",
            "t1" to "''",
            "t2" to "''",
            "user1" to "1000",
            "user2" to "2000",
            "company1" to "100",
            "company2" to "200"
    )

    private val typeToField = mapOf(
            "boolean" to "b",
            "integer" to "i",
            "text" to "t",
            "user" to "user",
            "company" to "company"
    )

    private val sqlCtx by lazy { SqlTestCtx(classDefs.joinToString("\n")) }

    @After
    fun after() {
        sqlCtx.destroy()
    }

    override fun checkOpBool(op: String, left: TstVal, right: TstVal, expected: Boolean) {
        left as AtTstVal
        right as AtTstVal
        checkOpWhere(op, left.leftField, right.rightField, left.sql(), right.sql(), expected)
        checkOp(op, left, right, "boolean[$expected]")
    }

    override fun checkOpBool(op: String, right: TstVal, expected: Boolean) {
        right as AtTstVal
        checkOpWhere(op, right.rightField, right.sql(), expected)
        checkOp(op, right, "boolean[$expected]")
    }

    override fun checkOp(op: String, left: TstVal, right: TstVal, expected: String) {
        left as AtTstVal
        right as AtTstVal
        val actual = calcOpWhat(op, left.leftField, right.rightField, left.sql(), right.sql())
        assertEquals(expected, actual)
    }

    override fun checkOp(op: String, right: TstVal, expected: String) {
        right as AtTstVal
        val actual = calcOpWhat(op, right.rightField, right.sql())
        assertEquals(expected, actual)
    }

    override fun checkOpErr(op: String, left: String, right: String) {
        val expected = "ct_err:binop_operand_type:$op:$left:$right"
        val leftField = typeToField[left] + "1"
        val rightField = typeToField[right] + "2"
        val actual = calcOpWhere(op, leftField, rightField, null, null)
        assertEquals(expected, actual)
    }

    override fun checkOpErr(op: String, right: String) {
        val expected = "ct_err:unop_operand_type:$op:$right"
        val rightField = typeToField[right] + "2"
        val actual = calcOpWhere(op, rightField, null)
        assertEquals(expected, actual)
    }

    private fun checkOpWhere(op: String, left: String, right: String, leftValue: String, rightValue: String, expected: Boolean) {
        val expectedStr = if (expected) "list<optest>[optest[5]]" else "list<optest>[]"
        val actualStr = calcOpWhere(op, left, right, leftValue, rightValue)
        assertEquals(expectedStr, actualStr)
    }

    private fun checkOpWhere(op: String, right: String, rightValue: String, expected: Boolean) {
        val expectedStr = if (expected) "list<optest>[optest[5]]" else "list<optest>[]"
        val actualStr = calcOpWhere(op, right, rightValue)
        assertEquals(expectedStr, actualStr)
    }

    private fun calcOpWhere(op: String, left: String, right: String, leftValue: String?, rightValue: String?): String {
        val values = makeValues(left, right, leftValue, rightValue)
        val inserts = makeInserts(values)
        val code = "return all optest @ { $left $op $right };"
        return AtExprTest.calc(sqlCtx, classDefs, inserts, code)
    }

    private fun calcOpWhere(op: String, right: String, rightValue: String?): String {
        val values = makeValues("", right, null, rightValue)
        val inserts = makeInserts(values)
        val code = "return all optest @ { $op $right };"
        return AtExprTest.calc(sqlCtx, classDefs, inserts, code)
    }

    private fun calcOpWhat(op: String, left: String, right: String, leftValue: String?, rightValue: String?): String {
        val values = makeValues(left, right, leftValue, rightValue)
        val inserts = makeInserts(values)
        val code = "return optest @ {} ( $left $op $right );"
        return AtExprTest.calc(sqlCtx, classDefs, inserts, code)
    }

    private fun calcOpWhat(op: String, right: String, rightValue: String?): String {
        val values = makeValues("", right, null, rightValue)
        val inserts = makeInserts(values)
        val code = "return optest @ {} ( $op $right );"
        return AtExprTest.calc(sqlCtx, classDefs, inserts, code)
    }

    private fun makeValues(left: String, right: String, leftValue: String?, rightValue: String?): Map<String, String> {
        val values = mutableMapOf<String, String>()
        values.putAll(defaultValues)
        if (leftValue != null) values.put(left, leftValue)
        if (rightValue != null) values.put(right, rightValue)
        return values
    }

    private fun makeInserts(values: Map<String, String>): Array<String> {
        val columns = values.keys.toList()
        val insColumns = columns.joinToString(",")
        val insValues = "5," + columns.map{ values[it] }.joinToString(",")
        val insert = TestUtils.mkins("optest", insColumns, insValues)
        return this.inserts + insert
    }

    override fun vBool(v: Boolean): TstVal = AtTstVal.Bool(v)
    override fun vInt(v: Long): TstVal = AtTstVal.Integer(v)
    override fun vText(v: String): TstVal = AtTstVal.Text(v)
    override fun vObj(cls: String, id: Long): TstVal = AtTstVal.Obj(cls, id)

    private sealed class AtTstVal(field: String): TstVal() {
        val leftField = field + "1"
        val rightField = field + "2"

        abstract fun sql(): String

        class Bool(val v: Boolean): AtTstVal("b") {
            override fun sql(): String = "$v"
        }

        class Integer(val v: Long): AtTstVal("i") {
            override fun sql(): String = "$v"
        }

        class Text(val v: String): AtTstVal("t") {
            override fun sql(): String = "'$v'"
        }

        class Obj(cls: String, val id: Long): AtTstVal(cls) {
            override fun sql(): String = "$id"
        }
    }

    private object Ins {
        fun company(id: Int, name: String): String = mkins("company", "name", "$id, '$name'")
        fun user(id: Int, name: String, company: Int): String = mkins("user", "name,company", "$id, '$name', $company")
        val mkins = TestUtils::mkins
    }
}
