/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl

object Lib_Type_Null {
    val NAMESPACE = Ld_NamespaceDsl.make {
        // Not a real extension, used directly when calling a method on a "null" literal.
        type("null_extension", abstract = true, hidden = true) {
            function("to_gtv", result = "gtv", pure = true) {
                Lib_Type_Gtv.makeToGtvBody(this, pretty = false)
            }
        }
    }
}
