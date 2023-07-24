/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl

object Lib_Type_Iterable {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("iterable", abstract = true, hidden = true) {
            generic("T")
        }
    }
}
