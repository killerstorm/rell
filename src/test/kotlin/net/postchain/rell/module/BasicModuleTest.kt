package net.postchain.rell.module

import net.postchain.base.SECP256K1CryptoSystem
import net.postchain.common.hexStringToByteArray
import net.postchain.devtools.IntegrationTest
import net.postchain.devtools.KeyPairHelper
import net.postchain.devtools.SingleChainTestNode
import net.postchain.gtx.GTXDataBuilder
import net.postchain.gtx.GTXValue
import net.postchain.gtx.gtx
import org.junit.Test
import kotlin.test.assertEquals

class BasicModuleTest : IntegrationTest() {
    private val testBlockchainRID = "78967baa4768cbcef11c508326ffb13a956689fcb6dc3ba17f4b895cbb1577a3".hexStringToByteArray()
    private val myCS = SECP256K1CryptoSystem()

    @Test fun testBuildBlock() {
        val node = setupNode()

        enqueueTx(node, makeTx_insertCity(0, "Hello"), 0)
        buildBlockAndCommit(node)
        enqueueTx(node, makeTx_insertCity(0, "Hello"), -1)
        buildBlockAndCommit(node)

        verifyBlockchainTransactions(node)
    }

    @Test fun testOpErrWrongArgCount() {
        val node = setupNode()
        enqueueTx(node, makeTx(0, "insert_city", gtx("New York"), gtx("Foo")), 0)
        buildBlockAndCommit(node) //TODO check the error somehow
    }

    @Test fun testOpErrWrongArgType() {
        val node = setupNode()
        enqueueTx(node, makeTx(0, "insert_city", gtx(12345)), 0)
        buildBlockAndCommit(node) //TODO check the error somehow
    }

    @Test fun testOpErrRuntimeError() {
        val node = setupNode()
        enqueueTx(node, makeTx(0, "op_integer_division", gtx(123), gtx(0)), 0)
        buildBlockAndCommit(node) //TODO check the error somehow
    }

    @Test fun testOpErrNonexistentObjectId() {
        val node = setupNodeAndObjects()
        enqueueTx(node, makeTx_insertPerson(0, "James", 999, "Foo St", 1, 1000), 0)
        buildBlockAndCommit(node)
    }

    @Test fun testOpErrObjectIdOfWrongClass() {
        val node = setupNodeAndObjects()
        enqueueTx(node, makeTx_insertPerson(0, "James", 5, "Foo St", 1, 1000), 0)
        buildBlockAndCommit(node)
    }

    @Test fun testQueryGetAllCities() {
        val node = setupNodeAndObjects()
        chkQuery(node, """{ type : "get_all_cities" }""", "[1,2,3]")
    }

    @Test fun testQueryGetAllCityNames() {
        val node = setupNodeAndObjects()
        chkQuery(node, """{ type : "get_all_city_names" }""", """["New York","Los Angeles","Seattle"]""")
    }

    @Test fun testQueryGetPersonsByCity() {
        val node = setupNodeAndObjects()
        chkQuery(node, """{ type : "get_persons_by_city", city : 1 }""", "[5]")
        chkQuery(node, """{ type : "get_persons_by_city", city : 2 }""", "[4,6]")
        chkQuery(node, """{ type : "get_persons_by_city", city : 3 }""", "[]")
    }

    @Test fun testObject() {
        val node = setupNodeAndObjects()
        chkQuery(node, """{ type : "get_state" }""", "5")

        enqueueTx(node, makeTx_setState(0, 33), 0)
        buildBlockAndCommit(node)

        chkQuery(node, """{ type : "get_state" }""", "33")
    }

    private fun makeTx(ownerIdx: Int, opName: String, vararg opArgs: GTXValue): ByteArray {
        val owner = KeyPairHelper.pubKey(ownerIdx)
        return GTXDataBuilder(testBlockchainRID, arrayOf(owner), myCS).run {
            addOperation(opName, opArgs.toList().toTypedArray())
            finish()
            sign(myCS.makeSigner(owner, KeyPairHelper.privKey(ownerIdx)))
            serialize()
        }
    }

    private fun makeTx_insertCity(ownerIdx: Int, name: String): ByteArray {
        return makeTx(ownerIdx, "insert_city", gtx(name))
    }

    private fun makeTx_insertPerson(ownerIdx: Int, name: String, city: Long, street: String, house: Long, score: Long): ByteArray {
        return makeTx(ownerIdx, "insert_person", gtx(name), gtx(city), gtx(street), gtx(house), gtx(score))
    }

    private fun makeTx_setState(ownerIdx: Int, value: Long): ByteArray {
        return makeTx(ownerIdx, "set_state", gtx(value))
    }

    private fun setupNodeAndObjects(): SingleChainTestNode {
        val node = setupNode()
        insertObjects(node)
        return node
    }

    private fun setupNode(): SingleChainTestNode {
        return createNode(0, "/net/postchain/rell/basic/blockchain_config.xml")
    }

    private fun insertObjects(node: SingleChainTestNode) {
        enqueueTx(node, makeTx_insertCity(0, "New York"), 0)
        enqueueTx(node, makeTx_insertCity(0, "Los Angeles"), 0)
        enqueueTx(node, makeTx_insertCity(0, "Seattle"), 0)
        enqueueTx(node, makeTx_insertPerson(0, "Bob", 2, "Main St", 5, 100), 0)
        enqueueTx(node, makeTx_insertPerson(0, "Alice", 1, "Evergreen Ave", 11, 250), 0)
        enqueueTx(node, makeTx_insertPerson(0, "Trudy", 2, "Mulholland Dr", 3, 500), 0)
        buildBlockAndCommit(node)
    }

    private fun chkQuery(node: SingleChainTestNode, json: String, expected: String) {
        val actual = callQuery(node, json)
        assertEquals(expected, actual)
    }

    private fun callQuery(node: SingleChainTestNode, json: String): String {
        val blockQueries = node.getBlockchainInstance().getEngine().getBlockQueries()
        val actual = blockQueries.query(json).get()
        return actual
    }
}