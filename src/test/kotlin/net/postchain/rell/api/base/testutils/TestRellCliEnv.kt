/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.api.base.testutils

import net.postchain.rell.api.base.RellCliEnv

class TestRellCliEnv: RellCliEnv() {
    override fun print(msg: String) {
        println(msg)
    }

    override fun error(msg: String) {
        println(msg)
    }
}
