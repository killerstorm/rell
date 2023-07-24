/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_UnitType
import net.postchain.rell.base.runtime.Rt_UnitValue

object Lib_Type_Unit {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("unit", rType = R_UnitType) {
            constructor(pure = true) {
                body { ->
                    Rt_UnitValue
                }
            }
        }
    }
}
