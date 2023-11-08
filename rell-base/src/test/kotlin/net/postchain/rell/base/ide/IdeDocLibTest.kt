/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.ide

import net.postchain.rell.base.compiler.base.lib.C_SysFunctionBody
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty_RtValue
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.lib.Lib_Rell
import net.postchain.rell.base.lmodel.dsl.BaseLTest
import net.postchain.rell.base.lmodel.dsl.Ld_ModuleDsl
import net.postchain.rell.base.model.R_IntegerType
import net.postchain.rell.base.runtime.Rt_IntValue
import net.postchain.rell.base.runtime.Rt_UnitValue
import net.postchain.rell.base.testutils.LibModuleTester
import org.junit.Test

class IdeDocLibTest: BaseIdeSymbolTest() {
    private val modTst = LibModuleTester(tst, Lib_Rell.MODULE)

    private fun extraModule(block: Ld_ModuleDsl.() -> Unit) = modTst.extraModule(block)

    @Test fun testNamespaceNamespace() {
        extraModule {
            namespace("ns") {
                type("data", rType = R_IntegerType)
            }
        }
        chkSyms("struct _s { _x: ns.data; }", "ns=DEF_NAMESPACE;-;-", "?head=NAMESPACE|mod:ns")
    }

    @Test fun testNamespaceTypeSimple() {
        extraModule {
            namespace("ns") {
                type("data", rType = R_IntegerType)
            }
        }
        chkSyms("struct _s { _x: ns.data; }", "data=DEF_TYPE;-;-", "?head=TYPE|mod:ns.data")
    }

    @Test fun testNamespaceTypeGeneric() {
        extraModule {
            namespace("ns") {
                type("data") {
                    generic("T")
                    rType(R_IntegerType)
                }
            }
        }
        chkSyms("struct _s { _x: ns.data<integer>; }", "data=DEF_TYPE;-;-", "?head=TYPE|mod:ns.data")
    }

    @Test fun testNamespaceTypeAlias() {
        extraModule {
            type("data", rType = R_IntegerType) {
                alias("data_1")
                alias("data_2", deprecated = C_MessageType.WARNING)
                alias("data_3", deprecated = C_MessageType.ERROR)
            }
        }

        val msg = "deprecated:TYPE"
        chkSyms("struct _s { _x: data_1; }", "data_1=DEF_TYPE;-;-", "?head=TYPE|mod:data")
        chkSyms("struct _s { _x: data_2; }", "data_2=DEF_TYPE;-;-", "?head=TYPE|mod:data", warn = "$msg:data_2:data")
        chkSyms("struct _s { _x: data_3; }", "data_3=DEF_TYPE;-;-", "?head=TYPE|mod:data", err = "$msg:data_3:data")
    }

    @Test fun testNamespaceStruct() {
        extraModule {
            struct("data") {
                attribute("value", "integer")
            }
        }
        chkSyms("struct _s { _x: data; }", "data=DEF_STRUCT;-;-", "?head=STRUCT|mod:data")
    }

    @Test fun testNamespaceConstant() {
        extraModule {
            constant("MAGIC", 123L)
        }
        chkSyms("query q() = MAGIC;", "MAGIC=DEF_CONSTANT;-;-", "?head=CONSTANT|mod:MAGIC")
    }

    @Test fun testNamespaceProperty() {
        extraModule {
            property("prop", type = "integer") { bodyContext { Rt_UnitValue } }
            property("pure_prop", pure = true, type = "integer") { bodyContext { Rt_UnitValue } }
            property("spec_prop", C_NamespaceProperty_RtValue(Rt_IntValue(0)))
        }
        chkSyms("query q() = prop;", "prop=MEM_SYS_PROPERTY;-;-", "?head=PROPERTY|mod:prop")
        chkSyms("query q() = pure_prop;", "pure_prop=MEM_SYS_PROPERTY_PURE;-;-", "?head=PROPERTY|mod:pure_prop")
        chkSyms("query q() = spec_prop;", "spec_prop=MEM_SYS_PROPERTY;-;-", "?head=PROPERTY|mod:spec_prop")
    }

    @Test fun testNamespaceFunction() {
        extraModule {
            function("foo", result = "text") {
                param(type = "integer")
                body { -> Rt_UnitValue }
            }
        }
        chkSyms("query q() = foo(123);", "foo=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:foo")
    }

    @Test fun testNamespaceFunctionSpecial() {
        extraModule {
            function("foo", BaseLTest.makeGlobalFun())
        }
        chkSyms("query q() = foo();", "foo=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:foo")
    }

    @Test fun testNamespaceFunctionDeprecated() {
        extraModule {
            function("f", result = "text") {
                deprecated("new_f")
                body { -> Rt_UnitValue }
            }
            function("g", result = "text") {
                deprecated("new_g", error = false)
                body { -> Rt_UnitValue }
            }
        }

        chkSyms("query q() = f();", "f=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:f",
            err = "deprecated:FUNCTION:f:new_f")
        chkSyms("query q() = g();", "g=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:g",
            warn = "deprecated:FUNCTION:g:new_g")
    }

    @Test fun testNamespaceFunctionAlias() {
        extraModule {
            function("f", result = "text") {
                alias("g")
                alias("h", deprecated = C_MessageType.WARNING)
                alias("k", deprecated = C_MessageType.ERROR)
                body { -> Rt_UnitValue }
            }
        }
        chkSyms("query q() = g();", "g=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:g")
        chkSyms("query q() = h();", "h=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:h",
            warn = "deprecated:FUNCTION:h:f")
        chkSyms("query q() = k();", "k=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:k",
            err = "deprecated:FUNCTION:k:f")
    }

    @Test fun testNamespaceLink() {
        extraModule {
            constant("VALUE", 123)
            struct("data") {
                attribute("x", "integer")
            }
            function("foo", result = "text") { body { -> Rt_UnitValue } }
            link("VALUE", "value_ref")
            link("data", "data_ref")
            link("foo", "foo_ref")
        }

        chkSyms("query q() = value_ref;", "value_ref=DEF_CONSTANT;-;-", "?head=CONSTANT|mod:VALUE")
        chkSyms("struct _s { _x: data_ref; }", "data_ref=DEF_STRUCT;-;-", "?head=STRUCT|mod:data")
        chkSyms("query q() = foo_ref();", "foo_ref=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:foo")
    }

    @Test fun testNamespaceLinkDeprecated() {
        extraModule {
            function("f", result = "text") {
                deprecated("new_f")
                body { -> Rt_UnitValue }
            }
            link("f", "link_f")
        }

        chkSyms("query q() = link_f();", "link_f=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:f",
            err = "deprecated:FUNCTION:link_f:new_f",
        )
    }

    @Test fun testTypeDefConstant() {
        extraModule {
            type("data") {
                modTst.setRTypeFactory(this)
                constant("VALUE", 123)
            }
        }
        chkSyms("query q() = data.VALUE;", "VALUE=DEF_CONSTANT;-;-", "?head=CONSTANT|mod:data.VALUE")
    }

    @Test fun testTypeDefProperty() {
        extraModule {
            type("data") {
                modTst.setRTypeFactory(this)
                property("prop", type = "integer") { _ -> Rt_UnitValue }
                property("pure_prop", type = "integer", pure = true) { _ -> Rt_UnitValue }
                property("spec_prop", type = "integer", C_SysFunctionBody.simple { _ -> Rt_UnitValue })
            }
        }
        chkSyms("function f(d: data) = d.prop;", "prop=MEM_SYS_PROPERTY;-;-", "?head=PROPERTY|mod:data.prop")
        chkSyms("function f(d: data) = d.pure_prop;", "pure_prop=MEM_SYS_PROPERTY_PURE;-;-",
            "?head=PROPERTY|mod:data.pure_prop")
        chkSyms("function f(d: data) = d.spec_prop;", "spec_prop=MEM_SYS_PROPERTY;-;-",
            "?head=PROPERTY|mod:data.spec_prop")
    }

    @Test fun testTypeDefConstructor() {
        extraModule {
            type("data") {
                modTst.setRTypeFactory(this)
                constructor {
                    param(type = "integer", name = "x")
                    body { -> Rt_UnitValue }
                }
                constructor {
                    deprecated("something_else", error = false)
                    param(type = "byte_array", name = "a")
                    body { -> Rt_UnitValue }
                }
            }
        }

        chkSyms("function f() = data(123);", "data=DEF_TYPE;-;-", "?head=CONSTRUCTOR|mod:data")
        chkSyms("function f() = data(x'1234');", "data=DEF_TYPE;-;-", "?head=CONSTRUCTOR|mod:data",
            warn = "deprecated:FUNCTION:data:something_else",
        )
    }

    @Test fun testTypeDefConstructorSpecial() {
        extraModule {
            type("data") {
                modTst.setRTypeFactory(this)
                constructor(BaseLTest.makeGlobalFun())
            }
        }
        chkSyms("function f() = data();", "data=DEF_TYPE;-;-", "?head=CONSTRUCTOR|mod:data")
    }

    @Test fun testTypeDefFunction() {
        extraModule {
            type("data") {
                modTst.setRTypeFactory(this)
                function("foo", result = "integer") {
                    body { -> Rt_UnitValue }
                }
                function("spec", BaseLTest.makeMemberFun())
                staticFunction("stat", result = "integer") {
                    body { -> Rt_UnitValue }
                }
                staticFunction("stat_spec", BaseLTest.makeGlobalFun())
            }
        }

        chkSyms("function f(d: data) = d.foo();", "foo=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:data.foo")
        chkSyms("function f(d: data) = d.spec();", "spec=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:data.spec")
        chkSyms("function f() = data.stat();", "stat=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:data.stat")
        chkSyms("function f() = data.stat_spec();", "stat_spec=DEF_FUNCTION_SYSTEM;-;-",
            "?head=FUNCTION|mod:data.stat_spec")
    }

    @Test fun testTypeDefFunctionDeprecated() {
        extraModule {
            type("data") {
                modTst.setRTypeFactory(this)
                function("f", result = "text") {
                    deprecated("new_f")
                    body { -> Rt_UnitValue }
                }
                function("g", result = "text") {
                    deprecated("new_g", error = false)
                    body { -> Rt_UnitValue }
                }
                staticFunction("h", result = "text") {
                    deprecated("new_h", error = false)
                    body { -> Rt_UnitValue }
                }
            }
        }

        chkSyms("function _f(d: data) = d.f();", "f=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:data.f",
            err = "deprecated:FUNCTION:data.f:new_f",
        )
        chkSyms("function _f(d: data) = d.g();", "g=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:data.g",
            warn = "deprecated:FUNCTION:data.g:new_g",
        )
        chkSyms("function _f() = data.h();", "h=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:data.h",
            warn = "deprecated:FUNCTION:data.h:new_h",
        )
    }

    @Test fun testTypeDefFunctionAlias() {
        extraModule {
            type("data") {
                modTst.setRTypeFactory(this)
                function("f", result = "text") {
                    alias("g")
                    alias("h", deprecated = C_MessageType.WARNING)
                    alias("k", deprecated = C_MessageType.ERROR)
                    body { -> Rt_UnitValue }
                }
            }
        }

        chkSyms("function _f(d: data) = d.f();", "f=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:data.f")
        chkSyms("function _f(d: data) = d.g();", "g=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:data.g")
        chkSyms("function _f(d: data) = d.h();", "h=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:data.h",
            warn = "deprecated:FUNCTION:data.h:f",
        )
        chkSyms("function _f(d: data) = d.k();", "k=DEF_FUNCTION_SYSTEM;-;-", "?head=FUNCTION|mod:data.k",
            err = "deprecated:FUNCTION:data.k:f",
        )
    }

    @Test fun testStructAttribute() {
        extraModule {
            struct("data") {
                attribute("foo", type = "integer")
                attribute("bar", type = "text", mutable = true)
            }
        }

        chkSyms("function _f(d: data) = d.foo;", "foo=MEM_STRUCT_ATTR;-;-", "?head=STRUCT_ATTR|mod:data.foo")
        chkSyms("function _f(d: data) = d.bar;", "bar=MEM_STRUCT_ATTR_VAR;-;-", "?head=STRUCT_ATTR|mod:data.bar")
        chkSyms("function _f() = data(foo = 123, bar = 'hello');",
            "foo=MEM_STRUCT_ATTR;-;-", "?head=STRUCT_ATTR|mod:data.foo",
            "bar=MEM_STRUCT_ATTR_VAR;-;-", "?head=STRUCT_ATTR|mod:data.bar",
        )
    }

    //TODO enable this test when named args for system functions are supported
    /*@Test*/ fun testFunctionParameter() {
        extraModule {
            function("f", result = "unit") {
                param(type = "integer", name = "a")
                param(type = "integer", name = "b", lazy = true)
                body { -> Rt_UnitValue }
            }
        }

        chkSyms("function _f() { f(a = 123, b = 456); }",
            "a=...", "?head=...",
            "b=...", "?head=...",
        )
    }
}
