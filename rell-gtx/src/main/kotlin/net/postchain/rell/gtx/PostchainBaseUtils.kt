/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.gtx

import net.postchain.StorageBuilder
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.data.DatabaseAccessFactory
import net.postchain.common.exception.UserMistake
import net.postchain.gtv.Gtv
import net.postchain.rell.base.model.R_App
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.runtime.Rt_GtvModuleArgsSource
import net.postchain.rell.base.runtime.Rt_ModuleArgsSource
import net.postchain.rell.base.utils.Bytes32
import net.postchain.rell.base.utils.PostchainGtvUtils
import net.postchain.rell.base.utils.toImmMap

object PostchainBaseUtils {
    val DATABASE_VERSION: Int = StorageBuilder.getCurrentDbVersion()

    fun calcBlockchainRid(config: Gtv): Bytes32 {
        val hash = PostchainGtvUtils.merkleHash(config)
        return Bytes32(hash)
    }

    fun createDatabaseAccess(): DatabaseAccess {
        return DatabaseAccessFactory.createDatabaseAccess(DatabaseAccessFactory.POSTGRES_DRIVER_CLASS)
    }

    fun createModuleArgsSource(app: R_App, configGtv: Gtv): Rt_ModuleArgsSource {
        val gtxNode = configGtv.asDict().getValue("gtx").asDict()
        val rellNode = gtxNode.getValue("rell").asDict()

        val gtvs = (rellNode["moduleArgs"]?.asDict() ?: mapOf())
            .mapKeys { R_ModuleName.of(it.key) }
            .toImmMap()

        for ((moduleName, argsStruct) in app.moduleArgs) {
            if (moduleName !in gtvs) {
                if (!argsStruct.hasDefaultConstructor) {
                    throw UserMistake("No moduleArgs for module '$moduleName' in blockchain configuration, " +
                            "but type ${argsStruct.moduleLevelName} defined in the code")
                }
            }
        }

        return Rt_GtvModuleArgsSource(gtvs)
    }
}
