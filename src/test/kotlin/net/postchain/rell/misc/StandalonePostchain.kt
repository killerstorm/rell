package net.postchain.rell.misc

import net.postchain.devtools.IntegrationTest
import net.postchain.devtools.SingleChainTestNode
import net.postchain.gtx.gtxml.GTXMLValueParser
import org.apache.commons.configuration2.Configuration
import java.io.File

fun main(args: Array<String>) {
    val configFile = args[0]
    val queryName = args[1]

    val requestJson = """{ type : "$queryName" }"""

    val access = PostchainAccess()
    try {
        val node = access.createNode(configFile)
        val blockQueries = node.getBlockchainInstance().getEngine().getBlockQueries()
        val result = blockQueries.query(requestJson).get()
        println(result)
    } finally {
        access.tearDown()
    }
}

private class PostchainAccess: IntegrationTest() {
    fun createNode(configFile: String): SingleChainTestNode {
        val nodeConfig = createConfigStub(0, 1, DEFAULT_CONFIG_FILE)
        val blockchainConfig = GTXMLValueParser.parseGTXMLValue(File(configFile).readText())
        return SingleChainTestNode(nodeConfig, blockchainConfig)
                .apply { startBlockchain() }
                .also { nodes.add(it) }
    }

    private fun createConfigStub(nodeIndex: Int, nodeCount: Int, configFile: String): Configuration {
        val m = javaClass.superclass.getDeclaredMethod("createConfig", Integer.TYPE, Integer.TYPE, Class.forName("java.lang.String"))
        m.isAccessible = true
        val res = m.invoke(this, nodeIndex, nodeCount, configFile)
        return res as Configuration
    }
}
