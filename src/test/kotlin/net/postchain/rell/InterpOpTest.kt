package net.postchain.rell

import net.postchain.gtv.GtvNull
import net.postchain.rell.model.R_ClassType
import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.NoConnSqlExecutor
import net.postchain.rell.test.RellTestUtils
import org.junit.Test
import kotlin.test.assertEquals

class InterpOpTest: AbstractOpTest() {
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

        val chainCtx = Rt_ChainContext(GtvNull, Rt_NullValue, ByteArray(32))

        val globalCtx = Rt_GlobalContext(
                Rt_FailingPrinter,
                Rt_FailingPrinter,
                NoConnSqlExecutor,
                null,
                chainCtx,
                typeCheck = true
        )

        val res = processExpr0(expr2, types) { module ->
            val rtArgs = args2.map { it.rt(module) }
            val sqlCtx = Rt_SqlContext.createNoExternalChains(module, Rt_ChainSqlMapping(0))
            val modCtx = Rt_ModuleContext(globalCtx, module, sqlCtx)
            RellTestUtils.callQuery(modCtx, "q", rtArgs, RellTestUtils.ENCODER_STRICT)
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

    private fun processExpr0(expr: String, types: List<String>, block: (R_Module) -> String): String {
        val params = types.withIndex().joinToString(", ") { (idx, type) -> "${paramName(idx)}: $type" }
        val code = """
            class company { name: text; }
            class user { name: text; company; }
            query q($params) = $expr;
        """.trimIndent()

        val res = RellTestUtils.processModule(code, processor = { block(it.rModule) })
        return res
    }

    private fun paramName(idx: Int) = "" + ('a' + idx)

    override fun vBool(v: Boolean): TstVal = InterpTstVal.Bool(v)
    override fun vInt(v: Long): TstVal = InterpTstVal.Integer(v)
    override fun vText(v: String): TstVal = InterpTstVal.Text(v)
    override fun vBytes(v: String): TstVal = InterpTstVal.Bytes(v)
    override fun vJson(v: String): TstVal = InterpTstVal.Json(v)
    override fun vObj(cls: String, id: Long): TstVal = InterpTstVal.Obj(cls, id)

    private sealed class InterpTstVal(val type: String): TstVal() {
        abstract fun rt(m: R_Module): Rt_Value

        class Bool(val v: Boolean): InterpTstVal("boolean") {
            override fun rt(m: R_Module): Rt_Value = Rt_BooleanValue(v)
        }

        class Integer(val v: Long): InterpTstVal("integer") {
            override fun rt(m: R_Module): Rt_Value = Rt_IntValue(v)
        }

        class Text(val v: String): InterpTstVal("text") {
            override fun rt(m: R_Module): Rt_Value = Rt_TextValue(v)
        }

        class Bytes(str: String): InterpTstVal("byte_array") {
            private val v = CommonUtils.hexToBytes(str)
            override fun rt(m: R_Module): Rt_Value = Rt_ByteArrayValue(v)
        }

        class Json(val v: String): InterpTstVal("json") {
            override fun rt(m: R_Module): Rt_Value = Rt_JsonValue.parse(v)
        }

        class Obj(val cls: String, val id: Long): InterpTstVal(cls) {
            override fun rt(m: R_Module): Rt_Value {
                val c = m.classes[cls]
                val t = R_ClassType(c!!)
                return Rt_ClassValue(t, id)
            }
        }
    }
}
