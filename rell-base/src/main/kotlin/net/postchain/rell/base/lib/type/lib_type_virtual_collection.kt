/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.runtime.Rt_BooleanValue
import net.postchain.rell.base.runtime.Rt_IntValue

object Lib_Type_VirtualCollection {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("virtual_collection", abstract = true, hidden = true) {
            generic("T")
            parent("iterable<T>")

            function("to_text", "text") {
                alias("str")
                bodyFunction(Lib_Type_Any.ToText_NoDb)
            }

            function("empty", "boolean", pure = true) {
                body { a ->
                    val col = a.asVirtualCollection()
                    Rt_BooleanValue(col.size() == 0)
                }
            }

            function("size", "integer", pure = true) {
                body { a ->
                    val col = a.asVirtualCollection()
                    Rt_IntValue(col.size().toLong())
                }
            }
        }
    }
}
