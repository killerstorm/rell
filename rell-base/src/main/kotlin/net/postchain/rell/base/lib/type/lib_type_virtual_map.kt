/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_ListType
import net.postchain.rell.base.model.R_MapType
import net.postchain.rell.base.model.R_SetType
import net.postchain.rell.base.runtime.Rt_ListValue
import net.postchain.rell.base.runtime.Rt_SetValue
import net.postchain.rell.base.utils.doc.DocCode

object Lib_Type_VirtualMap {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("virtual_map", hidden = true) {
            generic("K", subOf = "immutable")
            generic("V0")
            generic("V")
            parent("iterable<(K,V)>")

            rType { k, v0, _ ->
                R_MapType(k, v0).virtualType
            }

            docCode { k, v, _ ->
                DocCode.builder()
                    .keyword("virtual").raw("<")
                    .link("map").raw("<").append(k).sep(", ").append(v).raw(">")
                    .raw(">")
                    .build()
            }

            Lib_Type_Map.defCommonFunctions(this)

            function("keys", result = "set<K>", pure = true) {
                bodyMeta {
                    val keyType = fnBodyMeta.typeArg("K")
                    val keySetType = R_SetType(keyType)
                    body { a ->
                        val map = a.asMap()
                        val r = map.keys.toMutableSet()
                        Rt_SetValue(keySetType, r)
                    }
                }
            }

            function("values", result = "list<V>", pure = true) {
                bodyMeta {
                    val valueType = fnBodyMeta.typeArg("V")
                    val valueListType = R_ListType(valueType)
                    body { a ->
                        val map = a.asMap()
                        val r = map.values.toMutableList()
                        Rt_ListValue(valueListType, r)
                    }
                }
            }

            function("to_full", result = "map<K,V0>") {
                bodyRaw(Lib_Type_Virtual.ToFull)
            }
        }
    }
}
