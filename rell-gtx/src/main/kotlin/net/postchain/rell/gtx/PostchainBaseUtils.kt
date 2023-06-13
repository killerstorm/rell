/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.gtx

import net.postchain.common.exception.UserMistake
import net.postchain.gtv.Gtv
import net.postchain.rell.base.model.R_App
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.runtime.Rt_ChainContext
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.Bytes32
import net.postchain.rell.base.utils.PostchainGtvUtils

object PostchainBaseUtils {
    const val DATABASE_VERSION = 8

    fun calcBlockchainRid(config: Gtv): Bytes32 {
        val hash = PostchainGtvUtils.merkleHash(config)
        return Bytes32(hash)
    }

    fun createChainContext(rawConfig: Gtv, rApp: R_App, blockchainRid: Bytes32): Rt_ChainContext {
        val gtxNode = rawConfig.asDict().getValue("gtx")
        val rellNode = gtxNode.asDict().getValue("rell")
        val gtvArgsDict = rellNode.asDict()["moduleArgs"]?.asDict() ?: mapOf()

        val moduleArgs = mutableMapOf<R_ModuleName, Rt_Value>()

        for (rModule in rApp.modules) {
            val argsStruct = rModule.moduleArgs

            if (argsStruct != null) {
                val gtvArgs = gtvArgsDict[rModule.name.str()]
                if (gtvArgs == null) {
                    throw UserMistake("No moduleArgs in blockchain configuration for module '${rModule.name}', " +
                            "but type ${argsStruct.moduleLevelName} defined in the code")
                }

                val rtArgs = PostchainGtvUtils.moduleArgsGtvToRt(argsStruct, gtvArgs)
                moduleArgs[rModule.name] = rtArgs
            }
        }

        return Rt_ChainContext(rawConfig, moduleArgs, blockchainRid)
    }
}
