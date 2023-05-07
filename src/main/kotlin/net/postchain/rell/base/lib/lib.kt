/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.compiler.base.core.C_DefinitionPath
import net.postchain.rell.base.compiler.base.namespace.C_SysNsProto
import net.postchain.rell.base.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.base.lib.test.C_Lib_Test
import net.postchain.rell.base.lib.type.C_Lib_Types

private data class C_SysLibConfig(val testLib: Boolean, val hiddenLib: Boolean)

object C_SystemLibraryProvider {
    private val CACHE = mutableMapOf<C_SysLibConfig, C_SysNsProto>()

    fun getNsProto(testLib: Boolean, hiddenLib: Boolean): C_SysNsProto {
        val cfg = C_SysLibConfig(testLib = testLib, hiddenLib = hiddenLib)
        val res = CACHE.computeIfAbsent(cfg) {
            C_SystemLibrary.createNsProto(cfg)
        }
        return res
    }
}

private object C_SystemLibrary {
    fun createNsProto(cfg: C_SysLibConfig): C_SysNsProto {
        val b = C_SysNsProtoBuilder(C_DefinitionPath.ROOT)

        C_Lib_Types.bind(b)
        C_Lib_Math.bind(b)
        C_Lib_Exists.bind(b)
        C_Lib_Require.bind(b)
        C_Lib_Crypto.bind(b)
        C_Lib_Print.bind(b)
        C_Lib_ChainContext.bind(b)
        C_Lib_OpContext.bind(b, cfg.testLib)
        C_Lib_TryCall.bind(b)

        if (cfg.hiddenLib) {
            C_Lib_Hidden.bind(b)
        }

        if (cfg.testLib) {
            C_Lib_Test.bind(b)
        }

        bindRellNamespace(b, cfg)

        return b.build()
    }

    private fun bindRellNamespace(b: C_SysNsProtoBuilder, cfg: C_SysLibConfig) {
        val b2 = C_SysNsProtoBuilder(b.basePath.subPath("rell"))
        if (cfg.testLib) {
            C_Lib_Test.bindRell(b2)
        }
        b.addNamespace("rell", b2.build().toNamespace())
    }
}
