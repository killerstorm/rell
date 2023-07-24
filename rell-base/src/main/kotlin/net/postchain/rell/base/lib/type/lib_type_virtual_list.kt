/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_ListType

object Lib_Type_VirtualList {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("virtual_list", hidden = true) {
            generic("T")
            generic("T2")
            parent("virtual_collection<T2>")

            rType { t, _ ->
                R_ListType(t).virtualType
            }

            strCode { t, _ ->
                "virtual<list<${t.strCode()}>>"
            }

            function("get", result = "T2", pure = true) {
                param(type = "integer")
                body { a, b ->
                    val list = a.asVirtualList()
                    val index = b.asInteger()
                    val res = list.get(index)
                    res
                }
            }

            function("to_full", result = "list<T>") {
                bodyFunction(Lib_Type_Virtual.ToFull)
            }
        }
    }
}
