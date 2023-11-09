/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.lmodel.dsl.Ld_TypeDefDsl
import net.postchain.rell.base.model.R_MapType
import net.postchain.rell.base.mtype.M_Type_Tuple
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.runtime.utils.Rt_Utils

object Lib_Type_Map {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("map_entry", hidden = true) {
            generic("-K")
            generic("-V")

            supertypeStrategyComposite { mType ->
                mType is M_Type_Tuple && mType.fieldTypes.size == 2
            }
        }

        type("map") {
            generic("K", subOf = "immutable")
            generic("V")
            parent("iterable<(K,V)>")

            rType { k, v -> R_MapType(k, v) }

            defCommonFunctions(this)

            constructor(pure = true) {
                bodyMeta {
                    val (keyType, valueType) = fnBodyMeta.typeArgs("K", "V")
                    val mapType = R_MapType(keyType, valueType)
                    body { ->
                        Rt_MapValue(mapType, mutableMapOf())
                    }
                }
            }

            constructor(pure = true) {
                param(type = "iterable<-map_entry<K,V>>")
                bodyMeta {
                    val (keyType, valueType) = fnBodyMeta.typeArgs("K", "V")
                    val mapType = R_MapType(keyType, valueType)
                    body { arg ->
                        val map = if (arg is Rt_MapValue) {
                            arg.map.toMutableMap()
                        } else {
                            val iterable = arg.asIterable()
                            val tmap = mutableMapOf<Rt_Value, Rt_Value>()
                            for (item in iterable) {
                                val tup = item.asTuple()
                                val k = tup[0]
                                val v = tup[1]
                                val v0 = tmap.put(k, v)
                                Rt_Utils.check(v0 == null) { "map:new:iterator:dupkey:${k.strCode()}" toCodeMsg
                                        "Duplicate key: ${k.str()}" }
                            }
                            tmap
                        }
                        Rt_MapValue(mapType, map)
                    }
                }
            }

            function("keys", result = "set<K>", pure = true) {
                body { a ->
                    val mapValue = a.asMapValue()
                    val r = mutableSetOf<Rt_Value>()
                    r.addAll(mapValue.map.keys)
                    Rt_SetValue(mapValue.type.keySetType, r)
                }
            }

            function("values", result = "list<V>", pure = true) {
                body { a ->
                    val mapValue = a.asMapValue()
                    val r = mutableListOf<Rt_Value>()
                    r.addAll(mapValue.map.values)
                    Rt_ListValue(mapValue.type.valueListType, r)
                }
            }

            function("clear", result = "unit") {
                body { a ->
                    val map = a.asMutableMap()
                    map.clear()
                    Rt_UnitValue
                }
            }

            function("put", result = "unit") {
                param(type = "K")
                param(type = "V")
                body { a, b, c ->
                    val map = a.asMutableMap()
                    map[b] = c
                    Rt_UnitValue
                }
            }

            function("put_all", result = "unit") {
                alias("putAll", C_MessageType.ERROR)
                param(type = "map<-K,-V>")
                body { a, b ->
                    val map1 = a.asMutableMap()
                    val map2 = b.asMap()
                    map1.putAll(map2)
                    Rt_UnitValue
                }
            }

            function("remove", result = "V") {
                param(type = "K")
                body { a, b ->
                    val map = a.asMutableMap()
                    val v = map.remove(b)
                    v ?: throw Rt_Exception.common("fn:map.remove:novalue:${b.strCode()}", "Key not in map: ${b.str()}")
                }
            }

            function("remove_or_null", result = "V?") {
                param(type = "K")
                body { a, b ->
                    val map = a.asMutableMap()
                    val v = map.remove(b)
                    v ?: Rt_NullValue
                }
            }
        }
    }

    fun defCommonFunctions(m: Ld_TypeDefDsl) = with(m) {
        function("to_text", result = "text") {
            alias("str")
            bodyRaw(Lib_Type_Any.ToText_NoDb)
        }

        function("empty", result = "boolean", pure = true) {
            body { a ->
                val map = a.asMap()
                Rt_BooleanValue.get(map.isEmpty())
            }
        }

        function("size", result = "integer", pure = true) {
            alias("len", C_MessageType.ERROR)
            body { a ->
                val map = a.asMap()
                Rt_IntValue.get(map.size.toLong())
            }
        }

        function("get", result = "V", pure = true) {
            param(type = "K")
            body { self, a ->
                val map = self.asMap()
                val v = map[a]
                v ?: throw Rt_Exception.common("fn:map.get:novalue:${a.strCode()}", "Key not in map: ${a.str()}")
            }
        }

        function("get_or_null", result = "V?", pure = true) {
            param(type = "K")
            body { self, a ->
                val map = self.asMap()
                val r = map[a]
                r ?: Rt_NullValue
            }
        }

        function("get_or_default", pure = true) {
            generic("R", superOf = "V")
            result(type = "R")
            param(type = "K")
            param(type = "R", lazy = true)
            body { self, a, b ->
                val map = self.asMap()
                map[a] ?: b.asLazyValue()
            }
        }

        function("contains", result = "boolean", pure = true) {
            param(type = "K")
            body { self, a ->
                val map = self.asMap()
                Rt_BooleanValue.get(a in map)
            }
        }
    }
}
