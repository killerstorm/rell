/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import net.postchain.rell.utils.cli.RellCliEnv

class TestRellCliEnv: RellCliEnv() {
    override fun print(msg: String) {
        println(msg)
    }

    override fun error(msg: String) {
        println(msg)
    }
}
