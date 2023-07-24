/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.lib

import net.postchain.rell.base.compiler.base.core.C_DefinitionName
import net.postchain.rell.base.compiler.base.lib.C_LibType
import net.postchain.rell.base.compiler.base.lib.C_LibTypeDef
import net.postchain.rell.base.lmodel.L_ParamImplication
import net.postchain.rell.base.lmodel.dsl.Ld_FunctionBodyDsl
import net.postchain.rell.base.model.R_IntegerType
import net.postchain.rell.base.model.R_SimpleType
import net.postchain.rell.base.model.R_TextType
import net.postchain.rell.base.runtime.GtvRtConversion
import net.postchain.rell.base.runtime.GtvRtConversion_None
import net.postchain.rell.base.runtime.Rt_TextValue
import net.postchain.rell.base.runtime.Rt_UnitValue
import org.junit.Test

class CLibFunctionTest: BaseCLibTest() {
    @Test fun testTypeHintOverload() {
        tst.extraMod = makeTypeHintNs()
        chk("_type_hint([])", "text[A:list<integer>[]]")
        chk("_type_hint(set())", "text[B:set<text>[]]")
        chk("_type_hint([:])", "text[C:map<decimal,integer>[]]")
    }

    @Test fun testTypeHintOverloadPartCall() {
        tst.extraMod = makeTypeHintNs()
        chk("decimal(*)", "ct_err:expr:call:partial_ambiguous:decimal")
        chk("_type_hint(decimal(*))", "text[D:fn[decimal(*)]]")
        chk("_type_hint(big_integer(*))", "ct_err:expr:call:partial_ambiguous:big_integer")
        chk("_type_hint(integer(*))", "ct_err:expr:call:partial_ambiguous:integer")
    }

    private fun makeTypeHintNs() = makeModule {
        function("_type_hint", "text") {
            param("list<integer>")
            body { arg -> Rt_TextValue("A:${arg.strCode()}") }
        }
        function("_type_hint", "text") {
            param("set<text>")
            body { arg -> Rt_TextValue("B:${arg.strCode()}") }
        }
        function("_type_hint", "text") {
            param("map<decimal,integer>")
            body { arg -> Rt_TextValue("C:${arg.strCode()}") }
        }
        function("_type_hint", "text") {
            param("(big_integer) -> decimal")
            body { arg -> Rt_TextValue("D:${arg.strCode()}") }
        }
    }

    @Test fun testTypeHintMoreCollections() {
        tst.extraMod = makeModule {
            function("f_list", "text") {
                param("list<integer>")
                body { arg -> Rt_TextValue(arg.strCode()) }
            }
            function("f_set", "text") {
                param("set<integer>")
                body { arg -> Rt_TextValue(arg.strCode()) }
            }
            function("f_collection", "text") {
                param("collection<integer>")
                body { arg -> Rt_TextValue(arg.strCode()) }
            }
            function("f_iterable", "text") {
                param("iterable<(integer,text)>")
                body { arg -> Rt_TextValue(arg.strCode()) }
            }
            function("f_map", "text") {
                param("map<integer,text>")
                body { arg -> Rt_TextValue(arg.strCode()) }
            }
        }

        chk("f_list([])", "text[list<integer>[]]")
        chk("f_list(set())", "ct_err:fn:sys:unresolved_type_params:set:T")
        chk("f_set(set())", "text[set<integer>[]]")
        chk("f_set([])", "ct_err:expr_list_no_type")
        chk("f_collection([])", "text[list<integer>[]]")
        chk("f_collection(set())", "text[set<integer>[]]")
        chk("f_collection([:])", "ct_err:expr_map_notype")
        chk("f_iterable([])", "text[list<(integer,text)>[]]")
        chk("f_iterable(set())", "text[set<(integer,text)>[]]")
        chk("f_iterable([:])", "text[map<integer,text>[]]")
        chk("f_map([:])", "text[map<integer,text>[]]")
        chk("f_map([])", "ct_err:expr_list_no_type")
        chk("f_map(set())", "ct_err:fn:sys:unresolved_type_params:set:T")
    }

    @Test fun testLibAssertNull() {
        tst.testLib = true
        chk("assert_null(*)", "ct_err:expr:call:partial_bad_case:rell.test.assert_null(anything):unit")
        chkEx("{ val f: (integer?) -> unit = assert_null(*); return _type_of(f); }", "text[(integer?)->unit]")
        chkEx("{ val f: (integer) -> unit = assert_null(*); return _type_of(f); }", "text[(integer)->unit]")
        chkEx("{ val f: (integer) -> unit = assert_null(*); return f; }", "fn[rell.test.assert_null(*)]")
        chkEx("{ val f: (integer) -> unit = assert_null(*); f(123); return 0; }", "asrt_err:assert_null:int[123]")
        chkEx("{ val f: (integer?) -> unit = assert_null(*); f(123); return 0; }", "asrt_err:assert_null:int[123]")
        chkEx("{ val f: (integer?) -> unit = assert_null(*); f(null); return 0; }", "int[0]")
    }

    @Test fun testNullableMatching() {
        tst.extraMod = makeModule {
            function("f", "text") {
                param("text?", nullable = true)
                body { _ -> Rt_TextValue("f(text?)") }
            }
            function("f", "text") {
                param("integer?", nullable = true)
                body { _ -> Rt_TextValue("f(integer?)") }
            }
            function("f", "text") {
                param("boolean?", nullable = true)
                body { _ -> Rt_TextValue("f(boolean?)") }
            }
        }

        chkEx("{ val x: text? = 'hello'; return f(x); }", "text[f(text?)]")
        chkWarn("expr:smartnull:var:never:x")
        chkEx("{ val x: integer? = 123; return f(x); }", "text[f(integer?)]")
        chkWarn("expr:smartnull:var:never:x")
        chkEx("{ val x: boolean? = true; return f(x); }", "text[f(boolean?)]")
        chkWarn("expr:smartnull:var:never:x")
        chkEx("{ val x: text? = 'hello'; return f(x) + f(x); }", "text[f(text?)f(text?)]")
        chkWarn("expr:smartnull:var:never:x", "expr:smartnull:var:never:x")
    }

    @Test fun testInferTypeParamsFromReturnType() {
        tst.wrapFunctionCallErrors = false
        tst.typeCheck = false
        tst.extraMod = makeModule {
            function("f") {
                generic("T")
                result("T")
                bodyMeta {
                    val type = fnBodyMeta.typeArg("T")
                    bodyContext { ctx ->
                        ctx.globalCtx.outPrinter.print(type.strCode())
                        Rt_UnitValue
                    }
                }
            }
        }

        chkInferTypeParamsFromReturnType("integer")
        chkInferTypeParamsFromReturnType("integer?")
        chkInferTypeParamsFromReturnType("(integer,text)")
        chkInferTypeParamsFromReturnType("list<integer>")
        chkInferTypeParamsFromReturnType("map<integer,text>")
        chkInferTypeParamsFromReturnType("map<text,list<set<(big_integer,decimal)>>>")
    }

    private fun chkInferTypeParamsFromReturnType(type: String) {
        chkEx("{ val x: $type = f(); return 0; }", "int[0]")
        chkOut(type)
    }

    @Test fun testValidatingAndDelegatingBody() {
        tst.extraMod = makeModule {
            function("f", result = "T") {
                generic("T")
                param("T")
                validate { ctx ->
                    ctx.exprCtx.msgCtx.error(ctx.callPos, "test:validation_error_1", "Validation failed 1")
                }
                bodyMeta {
                    if (fnBodyMeta.rResultType == R_IntegerType) {
                        validationError("test:validation_error_2", "Validation failed 2")
                    }
                    body { a -> a }
                }
            }
        }

        chk("f('Hello')", "ct_err:test:validation_error_1")
        chk("f(123)", "ct_err:[test:validation_error_1][test:validation_error_2]")
    }

    @Test fun testTypeHidden() {
        tst.extraMod = makeModule {
            type("foo", rType = R_IntegerType)
            type("bar", rType = R_TextType, hidden = true)
        }
        chkCompile("function f(x: foo) {}", "OK")
        chkCompile("function f(x: bar) {}", "ct_err:unknown_name:bar")
    }

    @Test fun testFunctionErrorBody() {
        tst.extraMod = makeModule {
            function("f", result = "R") {
                generic("R")
                body { -> Rt_UnitValue }
            }
            type("test_ext", extension = true, hidden = true) {
                generic("T", subOf = "any")
                function("g", result = "R") {
                    generic("R")
                    body { -> Rt_UnitValue }
                }
            }
        }

        chk("f()", "ct_err:fn:sys:unresolved_type_params:f:R")
        chk("'Hello'.g()", "ct_err:fn:sys:unresolved_type_params:text.g:R")
        chk("f(*)", "ct_err:expr:call:partial_not_supported:f")
        chk("'Hello'.g(*)", "ct_err:expr:call:partial_not_supported:text.g")
    }

    @Test fun testFunctionParamImplies() {
        tst.extraMod = makeModule {
            function("f", result = "unit") {
                param(type = "integer?", implies = L_ParamImplication.NOT_NULL)
                body { _ -> Rt_UnitValue }
            }
            type("test_ext", extension = true, hidden = true) {
                generic("T", subOf = "any")
                function("g", result = "unit") {
                    param(type = "integer?", implies = L_ParamImplication.NOT_NULL)
                    body { _, _ -> Rt_UnitValue }
                }
            }
        }

        chkEx("{ val t = _nullable_int(-123); return _type_of(t); }", "text[integer?]")
        chkEx("{ val t = _nullable_int(-123); return abs(t); }", "ct_err:expr_call_argtypes:[abs]:integer?")
        chkEx("{ val t = _nullable_int(-123); f(t); return _type_of(t); }", "text[integer]")
        chkEx("{ val t = _nullable_int(-123); f(t); return abs(t); }", "int[123]")
        chkEx("{ val t = _nullable_int(-123); ''.g(t); return _type_of(t); }", "text[integer]")
        chkEx("{ val t = _nullable_int(-123); ''.g(t); return abs(t); }", "int[123]")
    }

    @Test fun testTypeFunctionMetaBody() {
        tst.typeCheck = false

        class R_TestType: R_SimpleType("data", C_DefinitionName("", "data")) {
            var libTypeDef: C_LibTypeDef? = null
            override fun isReference() = true
            override fun isDirectPure() = false
            override fun createGtvConversion(): GtvRtConversion = GtvRtConversion_None
            override fun getLibType0() = C_LibType.make(libTypeDef!!)
        }

        val testType = R_TestType()

        fun makeBody(d: Ld_FunctionBodyDsl) = with(d) {
            bodyMeta {
                val name = this.fnQualifiedName
                bodyN { Rt_TextValue(name) }
            }
        }

        val mod = makeModule {
            namespace("ns") {
                type("data", rType = testType) {
                    constructor { makeBody(this) }
                    function("f", result = "text") { makeBody(this) }
                    staticFunction("g", result = "text") { makeBody(this) }
                }
            }
        }

        testType.libTypeDef = mod.getTypeDef("ns.data")
        tst.extraMod = mod
        chk("ns.data()", "text[ns.data]")
        chk("ns.data().f()", "text[ns.data.f]")
        chk("ns.data.g()", "text[ns.data.g]")
    }
}
