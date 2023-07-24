/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_SignerType

object Lib_Type_Signer {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("signer", rType = R_SignerType)
    }
}
