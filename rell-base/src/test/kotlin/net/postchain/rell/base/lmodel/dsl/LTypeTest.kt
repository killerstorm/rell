/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.runtime.Rt_UnitValue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LTypeTest: BaseLTest() {
    @Test fun testSimple() {
        val mod = makeModule("test") {
            type("integer") {}
            type("text") {}
        }

        val integerDef = mod.getTypeDef("integer")
        val textDef = mod.getTypeDef("text")
        assertEquals(R_Name.of("integer"), integerDef.simpleName)
        assertEquals(R_Name.of("text"), textDef.simpleName)

        val integerType = integerDef.getMType()
        val textType = textDef.getMType()
        assertEquals("integer", integerType.strCode())
        assertEquals("text", textType.strCode())
    }

    @Test fun testParent() {
        val mod = makeModule("test") {
            type("parent", abstract = true) {}
            type("child") {
                parent("parent")
            }
        }

        chkDefs(mod,
            "@abstract type parent",
            "type child: parent",
        )

        val parentDef = mod.getTypeDef("parent")
        val childDef = mod.getTypeDef("child")
        assertTrue(parentDef.abstract)
        assertFalse(childDef.abstract)
        assertEquals("child", childDef.getMType().strCode())
        assertEquals("parent", childDef.getMType().getParentType()?.strCode())
    }

    @Test fun testParentNonAbstract() {
        chkModuleErr("LDE:type_parent_not_abstract:parent") {
            type("parent") {}
            type("child") {
                parent("parent")
            }
        }
    }

    @Test fun testGeneric() {
        val mod = makeModule("test") {
            type("map") {
                generic("K")
                generic("V")
            }
        }
        val mapDef = mod.getTypeDef("map")
        assertEquals("map<K,V>", mapDef.mGenericType.strCode())
    }

    @Test fun testGenericBounds() {
        val mod = makeModule("test") {
            type("immutable") {}
            type("set") {
                generic("T", subOf = "immutable")
            }
        }
        val setDef = mod.getTypeDef("set")
        assertEquals("set<T:-immutable>", setDef.mGenericType.strCode())
    }

    @Test fun testRecursion() {
        chkModuleErr("LDE:type_cycle:test:foo,test:bar,test:foo") {
            type("foo") {
                parent("bar")
            }
            type("bar") {
                parent("foo")
            }
        }
    }

    @Test fun testRecursion2() {
        chkModuleErr("LDE:type_cycle:test:b,test:c,test:b") {
            type("a") {
                parent("b")
            }
            type("b") {
                parent("c")
            }
            type("c") {
                parent("b")
            }
        }
    }

    @Test fun testConstant() {
        val mod = makeModule("test") {
            type("integer") {}
            type("big_integer") {}
            type("decimal") {}
            type("data") {
                constant("INT", 123)
                constant("BIG_INT", BigInteger.valueOf(456))
                constant("DECIMAL", BigDecimal("789.987"))
            }
        }
        chkTypeMems(mod, "data",
            "constant INT: integer = int[123]",
            "constant BIG_INT: big_integer = bigint[456]",
            "constant DECIMAL: decimal = dec[789.987]",
        )
    }

    @Test fun testConstructor() {
        val mod = makeModule("test") {
            imports(BASIC_TYPES)
            type("json") {
                constructor {
                    param(type = "text")
                    body { -> Rt_UnitValue }
                }
            }
        }

        chkDefs(mod, "type json")
        chkTypeMems(mod, "json", "constructor (text)")
    }

    @Test fun testConstructorHeader() {
        val mod = makeModule("test") {
            imports(BASIC_TYPES)
            type("data") {
                constructor(listOf("text")) {
                    chkErr("LDE:common_fun:params_already_defined:text") { param(type = "text") }
                    chkErr("LDE:common_fun:params_already_defined:integer") { param(type = "integer") }
                    body { -> Rt_UnitValue }
                }
            }
        }
        chkTypeMems(mod, "data", "constructor (text)")
    }

    @Test fun testConstructorPure() {
        val mod = makeModule("test") {
            type("data") {
                constructor(pure = true) { body { -> Rt_UnitValue } }
            }
        }
        chkTypeMems(mod, "data", "pure constructor ()")
    }

    @Test fun testConstructorSpecial() {
        val mod = makeModule("test") {
            type("data") {
                constructor(makeGlobalFun())
            }
        }
        chkTypeMems(mod, "data", "special constructor (...)")
    }

    @Test fun testGenericTypeDef() {
        val mod = makeModule("test") {
            type("list") {
                generic("T")
            }
        }
        chkDefs(mod, "type list<T>")
    }

    @Test fun testGenericTypeUsage() {
        val mod = makeModule("test") {
            type("char") {}
            type("list") {
                generic("T")
            }
            type("text") {
                function("to_chars") {
                    result(type = "list<char>")
                    body { -> Rt_UnitValue }
                }
            }
        }
        chkTypeMems(mod, "text", "function to_chars(): list<char>")
    }

    @Test fun testTypeParamsOfType() {
        val mod = makeModule("test") {
            type("unit") {}
            type("data") {
                generic("T")
                constructor {
                    param(type = "T")
                    body { -> Rt_UnitValue }
                }
                function("get", result = "T") {
                    body { -> Rt_UnitValue }
                }
                function("set", result = "unit") {
                    param(type = "T")
                    body { -> Rt_UnitValue }
                }
            }
        }
        chkTypeMems(mod, "data", "constructor (T)", "function get(): T", "function set(T): unit")
    }

    @Test fun testTypeParamOfConstructor() {
        val mod = makeModule("test") {
            type("data") {
                constructor {
                    generic("T")
                    param(type = "T")
                    body { -> Rt_UnitValue }
                }
            }
        }
        chkTypeMems(mod, "data", "constructor <T> (T)")
    }

    @Test fun testTypeParamOuterInner() {
        val mod = makeModule("test") {
            type("data") {
                generic("T")
                constructor {
                    generic("U")
                    param(type = "T")
                    param(type = "U")
                    body { -> Rt_UnitValue }
                }
                function("f") {
                    generic("V")
                    result(type = "(T,V)")
                    param(type = "V")
                    param(type = "T")
                    body { -> Rt_UnitValue }
                }
            }
        }
        chkTypeMems(mod, "data", "constructor <U> (T, U)", "function <V> f(V, T): (T,V)")
    }

    @Test fun testTypeParamUsesTypeParamType() {
        val mod = makeModule("test") {
            type("data") {
                generic("T")
                generic("U", subOf = "T")
                function("f") {
                    result(type = "U")
                    param(type = "T")
                    body { -> Rt_UnitValue }
                }
            }
        }
        chkDefs(mod, "type data<T,U:-T>")
        chkTypeMems(mod, "data", "function f(T): U")
    }

    @Test fun testTypeParamInParentType() {
        val mod = makeModule("test") {
            type("iterable", abstract = true) {
                generic("T")
            }
            type("map") {
                generic("K")
                generic("V")
                parent("iterable<(K,V)>")
            }
        }
        chkDefs(mod, "@abstract type iterable<T>", "type map<K,V>: iterable<(K,V)>")
    }

    @Test fun testTypeParamVariance() {
        val mod = makeModule("test") {
            type("data") {
                generic("-A")
                generic("+B")
                generic("C")
            }
        }
        chkDefs(mod, "type data<-A,+B,C>")
    }

    @Test fun testTypeParamConflict() {
        val mod = makeModule("test") {
            type("data") {
                generic("A")
                chkErr("LDE:type:type_param_conflict:A") { generic("A") }
                generic("B")
            }
        }
        chkDefs(mod, "type data<A,B>")
    }

    @Test fun testGenericInheritanceConstants() {
        val mod = makeModule("test") {
            type("integer") {}
            type("parent", abstract = true) {
                generic("A")
                constant("MAGIC", 123)
            }
            type("child") {
                generic("B")
                parent("parent<B>")
            }
        }

        chkTypeAllMems(mod, "parent", "constant MAGIC: integer = int[123]")
        chkTypeAllMems(mod, "child", "constant MAGIC: integer = int[123]")
    }

    @Test fun testGenericInheritanceValueFunctions() {
        val mod = makeModule("test") {
            type("boolean") {}
            type("iterator") {
                generic("X")
            }
            type("iterable", abstract = true) {
                generic("T")
                function("iterator", result = "iterator<T>") {
                    body { -> Rt_UnitValue }
                }
            }
            type("list") {
                generic("U")
                parent("iterable<U>")
                function("add", result = "boolean") {
                    param(type = "U")
                    body { -> Rt_UnitValue }
                }
            }
            type("map", abstract = true) {
                generic("K")
                generic("V")
                parent("iterable<(K,V)>")
                function("get", result = "V?") {
                    param(type = "K")
                    body { -> Rt_UnitValue }
                }
            }
            type("multi_map") {
                generic("A")
                generic("B")
                parent("map<A,list<B>>")
                function("put", result = "boolean") {
                    param(type = "A")
                    param(type = "B")
                    body { -> Rt_UnitValue }
                }
            }
        }

        chkTypeAllMems(mod, "iterable", "function iterator(): iterator<T>")
        chkTypeAllMems(mod, "list", "function add(U): boolean", "function iterator(): iterator<U>")

        chkTypeAllMems(mod, "map",
            "function get(K): V?",
            "function iterator(): iterator<(K,V)>",
        )

        chkTypeAllMems(mod, "multi_map",
            "function put(A, B): boolean",
            "function get(A): list<B>?",
            "function iterator(): iterator<(A,list<B>)>",
        )
    }

    @Test fun testGenericInheritanceStaticFunctions() {
        val mod = makeModule("test") {
            type("iterable", abstract = true) {
                generic("T")
                staticFunction("empty", result = "iterable<T>") {
                    body { -> Rt_UnitValue }
                }
                staticFunction("concat", result = "iterable<T>") {
                    param(type = "iterable<iterable<T>>")
                    body { -> Rt_UnitValue }
                }
            }
            type("list") {
                generic("U")
                parent("iterable<U>")
            }
        }

        chkTypeAllMems(mod, "iterable",
            "static function empty(): iterable<T>",
            "static function concat(iterable<iterable<T>>): iterable<T>",
        )

        chkTypeAllMems(mod, "list",
            "static function empty(): iterable<U>",
            "static function concat(iterable<iterable<U>>): iterable<U>",
        )
    }

    @Test fun testAbstractTypeConstructor() {
        chkModuleErr("LDE:type:abstract_constructor:data") {
            type("data", abstract = true) {
                constructor {
                    body { -> Rt_UnitValue }
                }
            }
        }
    }

    @Test fun testFunctionAlias() {
        val mod = makeModule("test") {
            type("data") {
                function("f", result = "anything") {
                    alias("g")
                    alias("h", C_MessageType.WARNING)
                    alias("i", C_MessageType.ERROR)
                    body { -> Rt_UnitValue }
                }
            }
        }

        chkTypeMems(mod, "data",
            "function f(): anything",
            "alias g = f",
            "@deprecated(WARNING) alias h = f",
            "@deprecated(ERROR) alias i = f",
        )
    }

    @Test fun testAliasConflict() {
        val mod = makeModule("test") {
            type("data")
            chkErr("LDE:name_conflict:data") { alias("data", "data") }
            alias("data2", "data")
            chkErr("LDE:name_conflict:data2") { alias("data2", "data") }
            alias("data3", "data")
        }
        chkDefs(mod,
            "type data",
            "alias data2 = data",
            "alias data3 = data",
        )
    }

    @Test fun testStaticFunctionSpecial() {
        val mod = makeModule("test") {
            type("data") {
                staticFunction("f", makeGlobalFun())
            }
        }
        chkTypeMems(mod, "data", "static special function f(...)")
    }

    private companion object {
        val BASIC_TYPES = Ld_ModuleDsl.make("test.types") {
            type("boolean") {}
            type("integer") {}
            type("text") {}
        }
    }
}
