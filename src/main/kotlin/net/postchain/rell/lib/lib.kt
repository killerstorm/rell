/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.fn.C_SysMemberFunction
import net.postchain.rell.compiler.base.fn.C_SysMemberProperty
import net.postchain.rell.compiler.base.namespace.C_SysNsProto
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.lib.test.C_Lib_Test
import net.postchain.rell.lib.type.C_Lib_Type_Enum
import net.postchain.rell.lib.type.C_Lib_Types
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_Type

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
        val b = C_SysNsProtoBuilder()

        C_Lib_Types.bind(b)
        C_Lib_Math.bind(b)
        C_Lib_Exists.bind(b)
        C_Lib_Require.bind(b)
        C_Lib_Crypto.bind(b)
        C_Lib_Print.bind(b)
        C_Lib_ChainContext.bind(b)
        C_Lib_OpContext.bind(b, cfg.testLib)

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
        val b2 = C_SysNsProtoBuilder()
        if (cfg.testLib) {
            C_Lib_Test.bindRell(b2)
        }
        b.addNamespace("rell", b2.build().toNamespace())
    }
}

object C_LibMemberFunctions {
    fun getTypeMemberFunction(type: R_Type, name: R_Name): C_SysMemberFunction? {
        val fns = type.getMemberFunctions()
        val fn = fns.get(name)
        return fn
    }

    fun getTypeStaticFunction(type: R_Type, name: R_Name): C_GlobalFunction? {
        val fns = type.getStaticFunctions()
        val fn = fns.get(name)
        return fn
    }

    fun getEnumPropertyOpt(name: R_Name): C_SysMemberProperty? {
        return C_Lib_Type_Enum.PROPERTIES[name]
    }

    fun getEnumProperties() = C_Lib_Type_Enum.PROPERTIES
}
