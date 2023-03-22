/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lang.expr.expr

import net.postchain.rell.lib.type.Lib_BigIntegerMath
import net.postchain.rell.lib.type.Lib_DecimalMath
import net.postchain.rell.test.RellCodeTester
import net.postchain.rell.test.RellTestContext
import net.postchain.rell.test.SqlTestUtils
import net.postchain.rell.utils.CommonUtils
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals

class OperatorsDbTest: OperatorsBaseTest() {
    private val dataAttrs = listOf(
            Triple("b", "boolean", "false"),
            Triple("i", "integer", "0"),
            Triple("l", "big_integer", "0"),
            Triple("d", "decimal", "0"),
            Triple("t", "text", "''"),
            Triple("ba", "byte_array", "''"),
            Triple("r", "rowid", "0"),
            Triple("j", "json", "'{}'"),
            Triple("user", "user", "1000"),
            Triple("company", "company", "100")
    )

    private val dataAttrCount = 4

    private val defaultValues = dataAttrs
            .flatMap { attr -> ( 1 .. dataAttrCount ).map { Pair(attr, it) } }
            .map { (attr, i) ->
                val (field, _, value) = attr
                Pair(field + i, value)
            }
            .toMap()

    private val typeToField = dataAttrs
            .map { attr ->
                val (field, type, _) = attr
                Pair(type, field)
            }
            .toMap()

    private val dataAttrDefs = dataAttrs
            .flatMap { attr -> ( 1 .. dataAttrCount ).map { Pair(attr, it) } }
            .map { (attr, i) ->
                val (field, type, _) = attr
                "$field$i: $type;"
            }
            .joinToString("")

    private val entityDefs = listOf(
            "entity company { name: text; }",
            "entity user { name: text; company; }",
            "entity optest { $dataAttrDefs }"
    )

    private val inserts = listOf(
            Ins.company(100, "Microsoft"),
            Ins.company(200, "Apple"),

            Ins.user(1000, "Bill Gates", 100),
            Ins.user(2000, "Steve Jobs", 200)
    )

    private val tstCtx = resource(RellTestContext())
    private val tst = RellCodeTester(tstCtx, entityDefs = entityDefs, inserts = inserts)

    @Test fun testComplexWhat() {
        // Make sure that complex what is not enabled in this test class, so all expressions are evaluated via SQL.
        chkExpr("[#0 : #1]", "ct_err:expr_sqlnotallowed", vInt(123), vText("Hello"))
    }

    override fun chkExpr(expr: String, args: List<TstVal>, expected: Boolean) {
        val (expr2, values) = transformExpr(expr, args)

        val expectedWhereStr = if (expected) "list<optest>[optest[5]]" else "list<optest>[]"
        val actualWhereStr = calcExprWhere(expr2, values)
        assertEquals(expectedWhereStr, actualWhereStr)

        val expectedWhatStr = "boolean[$expected]"
        val actualWhatStr = calcExprWhat(expr2, values)
        assertEquals(expectedWhatStr, actualWhatStr)
    }

    override fun calcExpr(expr: String, args: List<TstVal>): String {
        val (expr2, values) = transformExpr(expr, args)
        return calcExprWhat(expr2, values)
    }

    private fun transformExpr(expr: String, args: List<TstVal>): Pair<String, List<Pair<String, String>>> {
        val args2 = args.map { it as AtTstVal }
        val fields = args2.withIndex().map { (idx, arg) -> arg.field(idx) }
        val expr2 = replaceParams(expr, fields)
        val values = args2.withIndex().map { (idx, arg) -> Pair(arg.field(idx), arg.sql()) }
        return Pair(expr2, values)
    }

    override fun compileExpr(expr: String, types: List<String>): String {
        val params = types.withIndex().map { (idx, type) ->
            val field = typeToField.getValue(type)
            "$field${idx+1}"
        }
        val expr2 = replaceParams(expr, params)
        return tst.compileModule("query q() = optest @ {} ( $expr2 );")
    }

    private fun replaceParams(expr: String, params: List<String>): String {
        var s = expr
        for ((idx, param) in params.withIndex()) {
            s = s.replace("#$idx", "." + param)
        }
        return s
    }

    private fun calcExprWhere(expr: String, args: List<Pair<String, String>>): String {
        val values = makeValues(args)
        return calc(values, "= optest @* { $expr };")
    }

    private fun calcExprWhat(expr: String, args: List<Pair<String, String>>): String {
        val values = makeValues(args)
        return calc(values, "= optest @ {} ( $expr );")
    }

    private fun calc(values: Map<String, String>, code: String): String {
        tst.inserts = makeInserts(values)
        tst.complexWhatEnabled = false
        return tst.callQuery("query q() $code", "q", listOf())
    }

    private fun makeValues(args: List<Pair<String, String>>): Map<String, String> {
        val values = mutableMapOf<String, String>()
        values.putAll(defaultValues)
        for ((name, value) in args) {
            values.put(name, value)
        }
        return values
    }

    private fun makeInserts(values: Map<String, String>): List<String> {
        val columns = values.keys.toList()
        val insColumns = columns.joinToString(",")
        val insValues = "5," + columns.map{ values[it] }.joinToString(",")
        val insert = SqlTestUtils.mkins("c0.optest", insColumns, insValues)
        return this.inserts + insert
    }

    override fun errRt(code: String) = "rt_err:sqlerr:0"

    override fun vBool(v: Boolean): TstVal = AtTstVal.Bool(v)
    override fun vInt(v: Long): TstVal = AtTstVal.Integer(v)
    override fun vBigInt(v: BigInteger): TstVal = AtTstVal.BigInteger(v)
    override fun vDec(v: BigDecimal): TstVal = AtTstVal.Decimal(v)
    override fun vText(v: String): TstVal = AtTstVal.Text(v)
    override fun vBytes(v: String): TstVal = AtTstVal.Bytes(v)
    override fun vRowid(v: Long): TstVal = AtTstVal.Rowid(v)
    override fun vJson(v: String): TstVal = AtTstVal.Json(v)
    override fun vObj(ent: String, id: Long): TstVal = AtTstVal.Obj(ent, id)

    private sealed class AtTstVal(val field: String): TstVal() {
        abstract fun sql(): String

        fun field(idx: Int): String = field + (idx + 1)

        class Bool(val v: Boolean): AtTstVal("b") {
            override fun sql(): String = "$v"
        }

        class Integer(val v: Long): AtTstVal("i") {
            override fun sql(): String = "$v"
        }

        class BigInteger(val v: java.math.BigInteger): AtTstVal("l") {
            override fun sql(): String = "('$v' :: ${Lib_BigIntegerMath.SQL_TYPE_STR})"
        }

        class Decimal(val v: BigDecimal): AtTstVal("d") {
            override fun sql(): String = "('${v.toPlainString()}' :: ${Lib_DecimalMath.DECIMAL_SQL_TYPE_STR})"
        }

        class Text(val v: String): AtTstVal("t") {
            override fun sql(): String = "'$v'"
        }

        class Bytes(str: String): AtTstVal("ba") {
            private val v = CommonUtils.hexToBytes(str)
            override fun sql(): String = "'\\x${CommonUtils.bytesToHex(v)}'"
        }

        class Rowid(val v: Long): AtTstVal("r") {
            override fun sql(): String = "$v"
        }

        class Json(val v: String): AtTstVal("j") {
            override fun sql(): String = "'$v'"
        }

        class Obj(ent: String, val id: Long): AtTstVal(ent) {
            override fun sql(): String = "$id"
        }
    }

    private object Ins {
        fun company(id: Int, name: String): String = mkins("c0.company", "name", "$id, '$name'")
        fun user(id: Int, name: String, company: Int): String = mkins("c0.user", "name,company", "$id, '$name', $company")
        val mkins = SqlTestUtils::mkins
    }
}
