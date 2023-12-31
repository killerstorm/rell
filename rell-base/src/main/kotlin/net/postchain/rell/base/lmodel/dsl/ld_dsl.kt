/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.lmodel.L_Module

@RellLibDsl
interface Ld_ModuleDsl: Ld_NamespaceDsl {
    fun imports(module: L_Module)

    companion object {
        fun make(name: String, block: Ld_ModuleDsl.() -> Unit): L_Module {
            return Ld_ModuleDslImpl.make(name, block)
        }
    }
}
