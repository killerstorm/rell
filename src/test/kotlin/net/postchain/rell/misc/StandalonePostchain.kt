/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import net.postchain.config.node.NodeConfigurationProviderFactory
import net.postchain.devtools.IntegrationTest
import net.postchain.devtools.PostchainTestNode
import net.postchain.gtv.Gtv
import net.postchain.rell.utils.PostchainUtils
import java.io.File

fun main(args: Array<String>) {
    val configFile = args[0]
    val queryName = args[1]

    val requestJson = """{ type : "$queryName" }"""

    val access = PostchainAccess()
    try {
        val node = access.createNode(configFile)
        val blockQueries = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
        val result = blockQueries.query(requestJson).get()
        println(result)
    } finally {
        access.tearDown()
    }
}

private class PostchainAccess: IntegrationTest() {
    fun createNode(configFile: String): PostchainTestNode {
        val nodeIndex = 0
        val totalNodesCount = 1
        val preWipeDatabase = true

        val appConfig = createAppConfig(nodeIndex, totalNodesCount, DEFAULT_CONFIG_FILE)
        val nodeConfigProvider = NodeConfigurationProviderFactory.createProvider(appConfig)
        val nodeConfig = nodeConfigProvider.getConfiguration()
        nodesNames[nodeConfig.pubKey] = "$nodeIndex"
        val blockchainConfig = readBlockchainConfigStub(configFile)
        val chainId = nodeConfig.activeChainIds.first().toLong()

        return PostchainTestNode(nodeConfigProvider, preWipeDatabase)
                .apply {
                    addBlockchain(chainId, blockchainConfig)
                    startBlockchain()
                }
                .also {
                    nodes.add(it)
                }

    }

    private fun readBlockchainConfigStub(configFile: String): Gtv {
        val file = File(configFile)
        return PostchainUtils.xmlToGtv(file.readText())
    }
}
