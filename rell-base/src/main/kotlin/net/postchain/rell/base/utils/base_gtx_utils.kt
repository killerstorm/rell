/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.utils

import net.postchain.rell.base.compiler.base.core.C_CompilerOptions
import net.postchain.rell.base.model.R_App

object RellGtxConfigConstants {
    const val RELL_VERSION_KEY = "version"
    const val RELL_SOURCES_KEY = "sources"
    const val RELL_FILES_KEY = "files"
}

class RellGtxModuleApp(val app: R_App, val compilerOptions: C_CompilerOptions)
