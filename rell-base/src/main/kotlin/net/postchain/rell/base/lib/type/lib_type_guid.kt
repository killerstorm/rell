/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_GUIDType

object Lib_Type_Guid {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("guid", rType = R_GUIDType)
    }
}
