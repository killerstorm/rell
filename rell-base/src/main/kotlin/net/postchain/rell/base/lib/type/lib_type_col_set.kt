/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_SetType
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.runtime.Rt_SetValue
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.immListOf

object Lib_Type_Set {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("set") {
            generic("T", subOf = "immutable")
            parent("collection<T>")

            rType { t -> R_SetType(t) }

            constructor(pure = true) {
                bodyMeta {
                    val elementType = fnBodyMeta.typeArg("T")
                    val rKind = R_CollectionKind_Set(R_SetType(elementType))
                    body { ->
                        rKind.makeRtValue(immListOf())
                    }
                }
            }

            constructor(pure = true) {
                param(type = "iterable<-T>")
                bodyMeta {
                    val elementType = fnBodyMeta.typeArg("T")
                    val rKind = R_CollectionKind_Set(R_SetType(elementType))
                    body { arg ->
                        val iterable = arg.asIterable()
                        rKind.makeRtValue(iterable)
                    }
                }
            }
        }
    }
}

private class R_CollectionKind_Set(type: R_Type): R_CollectionKind(type) {
    override fun makeRtValue(col: Iterable<Rt_Value>): Rt_Value {
        val set = mutableSetOf<Rt_Value>()
        set.addAll(col)
        return Rt_SetValue(type, set)
    }
}
