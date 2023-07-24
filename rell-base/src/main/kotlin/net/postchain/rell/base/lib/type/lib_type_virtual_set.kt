/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_SetType

object Lib_Type_VirtualSet {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("virtual_set", hidden = true) {
            generic("T", subOf = "immutable")
            generic("T2")
            parent("virtual_collection<T2>")

            rType { t, _ ->
                R_SetType(t).virtualType
            }

            strCode { t, _ ->
                "virtual<set<${t.strCode()}>>"
            }

            function("to_full", result = "set<T>") {
                bodyFunction(Lib_Type_Virtual.ToFull)
            }
        }
    }
}
