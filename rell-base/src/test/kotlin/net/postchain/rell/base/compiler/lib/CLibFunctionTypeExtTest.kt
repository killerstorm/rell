/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.lib

import net.postchain.common.toHex
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.runtime.Rt_TextValue
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.testutils.VirtualTestUtils
import net.postchain.rell.base.utils.PostchainGtvUtils
import org.junit.Test

class CLibFunctionTypeExtTest: BaseCLibTest() {
    @Test fun testTypeExtensionMeta() {
        //tst.wrapFunctionCallErrors = false
        tst.strictToString = false
        tst.typeCheck = false
        tst.extraMod = makeModule {
            fun argsToStr(m: Map<String, R_Type>) = m.entries.joinToString(", ", "{", "}") { "${it.key}=${it.value.strCode()}" }
            type("test_ext", extension = true, hidden = true) {
                generic("A", subOf = "any")
                staticFunction("static_self_type", result = "text") {
                    bodyMeta { body { -> Rt_TextValue(fnBodyMeta.rSelfType.strCode()) } }
                }
                staticFunction("static_result_type", result = "A") {
                    bodyMeta { body { -> Rt_TextValue(fnBodyMeta.rResultType.strCode()) } }
                }
                staticFunction("static_type_args", result = "text") {
                    bodyMeta { body { -> Rt_TextValue(argsToStr(fnBodyMeta.rTypeArgs)) } }
                }
                function("value_self_type", result = "text") {
                    bodyMeta { body { _ -> Rt_TextValue(fnBodyMeta.rSelfType.strCode()) } }
                }
                function("value_result_type", result = "A") {
                    bodyMeta { body { _ -> Rt_TextValue(fnBodyMeta.rResultType.strCode()) } }
                }
                function("value_type_args", result = "text") {
                    bodyMeta { body { _ -> Rt_TextValue(argsToStr(fnBodyMeta.rTypeArgs)) } }
                }
            }
        }

        chkTypeExtensionMeta("integer", "(0)")
        chkTypeExtensionMeta("list<integer>", "[0]")
        chkTypeExtensionMeta("map<integer,text>", "[0:'']")
        chkTypeExtensionMeta("list<(integer,text)>", "[(0,'')]")
    }

    private fun chkTypeExtensionMeta(type: String, expr: String) {
        //chk("$type.static_self_type()", type) //TODO fix
        chk("$type.static_result_type()", type)
        //chk("$type.static_type_args()", "{A=$type}") //TODO fix
        chk("_type_of($type.static_result_type())", type)

        chk("$expr.value_self_type()", type)
        chk("$expr.value_result_type()", type)
        //chk("$expr.value_type_args()", "{A=$type}") //TODO fix
        chk("_type_of($expr.value_result_type())", type)
    }

    @Test fun testTypeExtensionNamingSimple() {
        tst.extraMod = makeTypeExtensionMod()
        tst.typeCheck = false

        chkTypeExtensionNaming("boolean", "true")
        chkTypeExtensionNaming("integer", "(0)")
        chkTypeExtensionNaming("list<integer>", "[0]")

        chk("integer.test_decode(123)", "int[123]")
        chk("(0).test_encode(123)", "text[123]")
    }

    @Test fun testTypeExtensionNamingMirrorStruct() {
        tst.extraMod = makeTypeExtensionMod()
        def("entity data { x: integer; }")
        def("operation op(x: integer) {}")

        chkTypeExtensionNaming("struct<data>", "struct<data>(0)")
        chkTypeExtensionNaming("struct<mutable data>", "struct<mutable data>(0)")
        chkTypeExtensionNaming("struct<op>", "struct<op>(0)")
        chkTypeExtensionNaming("struct<mutable op>", "struct<mutable op>(0)")
    }

    @Test fun testTypeExtensionNamingVirtual() {
        tst.extraMod = makeTypeExtensionMod()
        def("struct rec { x: integer; y: text; }")

        chkTypeExtensionNamingVirtual("virtual<list<integer>>", "[123,456]", "[[0],[1]]")
        chkTypeExtensionNamingVirtual("virtual<set<integer>>", "[123,456]", "[[0],[1]]")
        chkTypeExtensionNamingVirtual("virtual<map<text,integer>>", "{'A':123,'B':456}", "[['A'],['B']]")
        chkTypeExtensionNamingVirtual("virtual<(integer,text)>", "[123,'A']", "[[0],[1]]")
        chkTypeExtensionNamingVirtual("virtual<rec>", "[123,'A']", "[[0],[1]]")
    }

    private fun chkTypeExtensionNamingVirtual(type: String, fullGtv: String, pathsGtv: String) {
        val gtv = VirtualTestUtils.argToGtv(fullGtv, pathsGtv)
        val hex = PostchainGtvUtils.gtvToBytes(gtv).toHex()
        chkTypeExtensionNaming(type, "$type.from_gtv(gtv.from_bytes(x'$hex'))")
    }

    private fun chkTypeExtensionNaming(type: String, expr: String) {
        chk("_type_of($type.test_decode(0))", "text[$type]")
        chk("_type_of($expr.test_encode(0))", "text[$type]")

        chk("$type.test_decode()", "ct_err:expr_call_argtypes:[$type.test_decode]:")
        chk("$type.test_decode('')", "ct_err:expr_call_argtypes:[$type.test_decode]:text")
        chk("$type.test_decode(0)", "rt_err:x=int[0]")
        chk("$type.test_decode(-1)", "rt_err:fn:error:$type.test_decode:java.lang.IllegalStateException")
        chk("$type.test_decode(*)", "fn[$type.test_decode(*)]")
        chk("$type.test_decode(*)(0)", "rt_err:x=int[0]")
        chk("$type.test_decode(*)(-1)", "rt_err:fn:error:$type.test_decode:java.lang.IllegalStateException")

        chk("$expr.test_encode()", "ct_err:expr_call_argtypes:[$type.test_encode]:")
        chk("$expr.test_encode('')", "ct_err:expr_call_argtypes:[$type.test_encode]:text")
        chk("$expr.test_encode(*)", "fn[$type.test_encode(*)]")
        chk("$expr.test_encode(0)", "rt_err:x=int[0]")
        chk("$expr.test_encode(-1)", "rt_err:fn:error:$type.test_encode:java.lang.IllegalStateException")
        chk("$expr.test_encode(*)(0)", "rt_err:x=int[0]")
        chk("$expr.test_encode(*)(-1)", "rt_err:fn:error:$type.test_encode:java.lang.IllegalStateException")
    }

    private fun makeTypeExtensionMod() = makeModule {
        type("test_ext", extension = true, hidden = true) {
            generic("T", subOf = "any")
            staticFunction("test_decode", result = "T") {
                param(type = "integer")
                body { a ->
                    Rt_Utils.check(a.asInteger() != 0L) { "x=${a.strCode()}" toCodeMsg "x = ${a.str()}" }
                    check(a.asInteger() >= 0)
                    a
                }
            }
            function("test_encode", result = "T") {
                param(type = "integer")
                body { _, a ->
                    val v = a.asInteger()
                    Rt_Utils.check(v != 0L) { "x=${a.strCode()}" toCodeMsg "x = ${a.str()}" }
                    check(v >= 0)
                    Rt_TextValue(a.str())
                }
            }
        }
    }
}
