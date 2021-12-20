/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.gtx

import net.postchain.base.SECP256K1CryptoSystem
import net.postchain.core.BlockchainRid
import net.postchain.devtools.IntegrationTest
import net.postchain.devtools.KeyPairHelper
import net.postchain.devtools.PostchainTestNode
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GTXDataBuilder
import org.junit.Test
import kotlin.test.assertEquals

class BasicGtxModuleTest : IntegrationTest() {
    private var blockchainRid: BlockchainRid? = null
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
        enqueueTx(node, makeTx(0, "insert_city", gtv("New York"), gtv("Foo")), 0)
        buildBlockAndCommit(node) //TODO check the error somehow
    }

    @Test fun testOpErrWrongArgType() {
        val node = setupNode()
        enqueueTx(node, makeTx(0, "insert_city", gtv(12345)), 0)
        buildBlockAndCommit(node) //TODO check the error somehow
    }

    @Test fun testOpErrRuntimeError() {
        val node = setupNode()
        enqueueTx(node, makeTx(0, "op_integer_division", gtv(123), gtv(0)), 0)
        buildBlockAndCommit(node) //TODO check the error somehow
    }

    @Test fun testOpErrNonexistentObjectId() {
        val node = setupNodeAndObjects()
        enqueueTx(node, makeTx_insertPerson(0, "James", 999, "Foo St", 1, 1000), 0)
        buildBlockAndCommit(node)
    }

    @Test fun testOpErrObjectIdOfWrongEntity() {
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

    private fun makeTx(ownerIdx: Int, opName: String, vararg opArgs: Gtv): ByteArray {
        val owner = KeyPairHelper.pubKey(ownerIdx)
        return GTXDataBuilder(blockchainRid!!, arrayOf(owner), myCS).run {
            addOperation(opName, opArgs.toList().toTypedArray())
            finish()
            sign(myCS.buildSigMaker(owner, KeyPairHelper.privKey(ownerIdx)))
            serialize()
        }
    }

    private fun makeTx_insertCity(ownerIdx: Int, name: String): ByteArray {
        return makeTx(ownerIdx, "insert_city", gtv(name))
    }

    private fun makeTx_insertPerson(ownerIdx: Int, name: String, city: Long, street: String, house: Long, score: Long): ByteArray {
        return makeTx(ownerIdx, "insert_person", gtv(name), gtv(city), gtv(street), gtv(house), gtv(score))
    }

    private fun makeTx_setState(ownerIdx: Int, value: Long): ByteArray {
        return makeTx(ownerIdx, "set_state", gtv(value))
    }

    private fun setupNodeAndObjects(): PostchainTestNode {
        val node = setupNode()
        insertObjects(node)
        return node
    }

    private fun setupNode(): PostchainTestNode {
        val node = createNode(0, "/net/postchain/rell/basic/blockchain_config.xml")
        blockchainRid = node.getBlockchainRid(1L)
        return node
    }

    private fun insertObjects(node: PostchainTestNode) {
        enqueueTx(node, makeTx_insertCity(0, "New York"), 0)
        enqueueTx(node, makeTx_insertCity(0, "Los Angeles"), 0)
        enqueueTx(node, makeTx_insertCity(0, "Seattle"), 0)
        enqueueTx(node, makeTx_insertPerson(0, "Bob", 2, "Main St", 5, 100), 0)
        enqueueTx(node, makeTx_insertPerson(0, "Alice", 1, "Evergreen Ave", 11, 250), 0)
        enqueueTx(node, makeTx_insertPerson(0, "Trudy", 2, "Mulholland Dr", 3, 500), 0)
        buildBlockAndCommit(node)
    }

    private fun chkQuery(node: PostchainTestNode, json: String, expected: String) {
        val actual = callQuery(node, json)
        assertEquals(expected, actual)
    }

    private fun callQuery(node: PostchainTestNode, json: String): String {
        val blockQueries = node.getBlockchainInstance().getEngine().getBlockQueries()
        val actual = blockQueries.query(json).get()
        return actual
    }
}
