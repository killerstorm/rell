/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.base.BlockchainRid
import net.postchain.gtv.GtvNull
import net.postchain.rell.compiler.C_MapSourceDir
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_EntityDefinition
import net.postchain.rell.model.R_EntityType
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.NoConnSqlExecutor
import net.postchain.rell.test.RellTestUtils
import net.postchain.rell.utils.CommonUtils
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class OperatorsInterpretedTest: OperatorsBaseTest() {
    @Test fun testPlus2() {
        chkOp("+", vBytes("0123ABCD"), vText("Hello"), "text[0x0123abcdHello]")
        chkOp("+", vText("Hello"), vBytes("0123ABCD"), "text[Hello0x0123abcd]")
        chkOp("+", vObj("user", 1000), vText("Hello"), "text[user[1000]Hello]")
        chkOp("+", vText("Hello"), vObj("user", 2000), "text[Hellouser[2000]]")
    }

    override fun chkExpr(expr: String, args: List<TstVal>, expected: Boolean) {
        val actualStr = calcExpr(expr, args)
        assertEquals("boolean[$expected]", actualStr)
    }

    override fun calcExpr(expr: String, args: List<TstVal>): String {
        val expr2 = replaceParams(expr, args.size)
        val args2 = args.map { it as InterpTstVal }
        val types = args2.map { it.type }

        val chainCtx = Rt_ChainContext(GtvNull, mapOf(), BlockchainRid.ZERO_RID)

        val globalCtx = Rt_GlobalContext(
                Rt_FailingPrinter,
                Rt_FailingPrinter,
                null,
                chainCtx,
                typeCheck = true,
                pcModuleEnv = RellPostchainModuleEnvironment.DEFAULT
        )

        val res = processExpr0(expr2, types) { app ->
            val ctx = ValCtx(app)
            val rtArgs = args2.map { it.rt(ctx) }
            val sqlCtx = Rt_SqlContext.createNoExternalChains(app, Rt_ChainSqlMapping(0))
            val appCtx = Rt_AppContext(globalCtx, sqlCtx, app, false, false, null, C_MapSourceDir.EMPTY, setOf())
            val exeCtx = Rt_ExecutionContext(appCtx, NoConnSqlExecutor)
            RellTestUtils.callQuery(exeCtx, "q", rtArgs, RellTestUtils.ENCODER_STRICT)
        }
        return res
    }

    override fun compileExpr(expr: String, types: List<String>): String {
        val expr2 = replaceParams(expr, types.size)
        return compileExpr0(expr2, types)
    }

    private fun replaceParams(expr: String, count: Int): String {
        var s = expr
        for (idx in 0 until count) {
            s = s.replace("#$idx", paramName(idx))
        }
        return s
    }

    private fun compileExpr0(expr: String, types: List<String>): String {
        return processExpr0(expr, types) { "OK" }
    }

    private fun processExpr0(expr: String, types: List<String>, block: (R_App) -> String): String {
        val params = types.withIndex().joinToString(", ") { (idx, type) -> "${paramName(idx)}: $type" }
        val code = """
            entity company { name: text; }
            entity user { name: text; company; }
            query q($params) = $expr;
        """.trimIndent()

        val res = RellTestUtils.processApp(code, processor = { block(it.rApp) })
        return res
    }

    private fun paramName(idx: Int) = "" + ('a' + idx)

    override fun errRt(code: String) = "rt_err:$code"

    override fun vBool(v: Boolean): TstVal = InterpTstVal.Bool(v)
    override fun vInt(v: Long): TstVal = InterpTstVal.Integer(v)
    override fun vDec(v: BigDecimal): TstVal = InterpTstVal.Decimal(v)
    override fun vText(v: String): TstVal = InterpTstVal.Text(v)
    override fun vBytes(v: String): TstVal = InterpTstVal.Bytes(v)
    override fun vRowid(v: Long): TstVal = InterpTstVal.Rowid(v)
    override fun vJson(v: String): TstVal = InterpTstVal.Json(v)
    override fun vObj(ent: String, id: Long): TstVal = InterpTstVal.Obj(ent, id)

    private class ValCtx(val app: R_App)

    private sealed class InterpTstVal(val type: String): TstVal() {
        abstract fun rt(c: ValCtx): Rt_Value

        class Bool(val v: Boolean): InterpTstVal("boolean") {
            override fun rt(c: ValCtx): Rt_Value = Rt_BooleanValue(v)
        }

        class Integer(val v: Long): InterpTstVal("integer") {
            override fun rt(c: ValCtx): Rt_Value = Rt_IntValue(v)
        }

        class Decimal(val v: BigDecimal): InterpTstVal("decimal") {
            override fun rt(c: ValCtx): Rt_Value = Rt_DecimalValue.of(v)
        }

        class Text(val v: String): InterpTstVal("text") {
            override fun rt(c: ValCtx): Rt_Value = Rt_TextValue(v)
        }

        class Bytes(str: String): InterpTstVal("byte_array") {
            private val v = CommonUtils.hexToBytes(str)
            override fun rt(c: ValCtx): Rt_Value = Rt_ByteArrayValue(v)
        }

        class Rowid(val v: Long): InterpTstVal("rowid") {
            override fun rt(c: ValCtx): Rt_Value = Rt_RowidValue(v)
        }

        class Json(val v: String): InterpTstVal("json") {
            override fun rt(c: ValCtx): Rt_Value = Rt_JsonValue.parse(v)
        }

        class Obj(val ent: String, val id: Long): InterpTstVal(ent) {
            override fun rt(c: ValCtx): Rt_Value {
                val entity = findEntity(c.app, ent)
                val t = R_EntityType(entity)
                return Rt_EntityValue(t, id)
            }

            private fun findEntity(app: R_App, name: String): R_EntityDefinition {
                for (module in app.modules) {
                    val c = module.entities[name]
                    if (c != null) return c
                }
                throw IllegalStateException("Entity not found: '$name'")
            }
        }
    }
}
