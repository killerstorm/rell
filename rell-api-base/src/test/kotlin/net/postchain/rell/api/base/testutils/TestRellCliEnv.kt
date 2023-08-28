/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.api.base.testutils

import net.postchain.rell.api.base.RellCliEnv
import net.postchain.rell.api.base.PrinterRellCliEnv

class TestRellCliEnv: RellCliEnv by PrinterRellCliEnv(::println)
