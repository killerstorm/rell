package net.postchain.rell.module

import net.postchain.base.SECP256K1CryptoSystem
import net.postchain.common.hexStringToByteArray
import net.postchain.core.ProgrammerMistake
import net.postchain.core.UserMistake
import net.postchain.gtx.GTXBlockchainConfigurationFactory
import net.postchain.gtx.GTXDataBuilder
import net.postchain.gtx.GTXValue
import net.postchain.gtx.gtx
import net.postchain.test.IntegrationTest
import net.postchain.test.KeyPairHelper
import net.postchain.test.SingleChainTestNode
import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test fun testQueryNoObjects() {
        val node = setupNode()
        chkQuery(node, """{ type : "get_all_cities" }""", "[]")
        chkQuery(node, """{ type : "get_all_city_names" }""", "[]")
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
        chkQuery(node, """{ type : "get_persons_by_city", q_city : 1 }""", "[5]")
        chkQuery(node, """{ type : "get_persons_by_city", q_city : 2 }""", "[4,6]")
        chkQuery(node, """{ type : "get_persons_by_city", q_city : 3 }""", "[]")
    }

    @Test fun testQueryGetCityByName() {
        val node = setupNodeAndObjects()
        chkQuery(node, """{ type : "get_city_by_name", q_name : "New York" }""", "1")
        chkQuery(node, """{ type : "get_city_by_name", q_name : "Los Angeles" }""", "2")
        chkQuery(node, """{ type : "get_city_by_name", q_name : "Seattle" }""", "3")
    }

    @Test fun testQueryGetPersonsNamesByCityName() {
        val node = setupNodeAndObjects()
        chkQuery(node, """{ type : "get_persons_names_by_city_name", q_cityName : "Los Angeles" }""", """["Bob","Trudy"]""")
        chkQuery(node, """{ type : "get_persons_names_by_city_name", q_cityName : "New York" }""", """["Alice"]""")
        chkQuery(node, """{ type : "get_persons_names_by_city_name", q_cityName : "Seattle" }""", "[]")
    }

    @Test fun testQueryGetPersonAddressByName() {
        val node = setupNodeAndObjects()
        chkQuery(node, """{ type : "get_person_address_by_name", q_name : "Bob" }""",
                """["Los Angeles","Main St",5]""")
        chkQuery(node, """{ type : "get_person_address_by_name", q_name : "Alice" }""",
                """["New York","Evergreen Ave",11]""")
        chkQuery(node, """{ type : "get_person_address_by_name", q_name : "Trudy" }""",
                """["Los Angeles","Mulholland Dr",3]""")
    }

    @Test fun testQueryGetPersonsByCitySet() {
        val node = setupNodeAndObjects()
        chkQuery(node, """{ type : "get_persons_by_city_set", q_cities : [] }""", """[]""")
        chkQuery(node, """{ type : "get_persons_by_city_set", q_cities : ["New York"] }""", """["Alice"]""")
        chkQuery(node, """{ type : "get_persons_by_city_set", q_cities : ["Los Angeles"] }""", """["Bob","Trudy"]""")
        chkQuery(node, """{ type : "get_persons_by_city_set", q_cities : ["New York","Los Angeles"] }""",
                """["Alice","Bob","Trudy"]""")
        chkQuery(node, """{ type : "get_persons_by_city_set", q_cities : ["Seattle","New York"] }""", """["Alice"]""")
    }

    @Test fun testQueryErrArgMissing() {
        val node = setupNodeAndObjects()
        assertFailsWith<UserMistake> {
            callQuery(node, """{ type : "get_city_by_name" }""")
        }
    }

    @Test fun testQueryErrArgExtra() {
        val node = setupNodeAndObjects()
        assertFailsWith<UserMistake> {
            callQuery(node, """{ type : "get_city_by_name", q_name : "New York", q_foo : 12345 }""")
        }
    }

    @Test fun testQueryErrArgWrongType() {
        val node = setupNodeAndObjects()
        assertFailsWith<UserMistake> {
            callQuery(node, """{ type : "get_city_by_name", q_name : 12345 }""")
        }
    }

    @Test fun testQueryErrArgSetDuplicate() {
        val node = setupNodeAndObjects()
        assertFailsWith<UserMistake> {
            callQuery(node, """{ type : "get_persons_by_city_set", q_cities : ["New York","New York"] }""")
        }
    }

    @Test fun testQueryErrArgNonexistentObjectId() {
        val node = setupNodeAndObjects()
        assertFailsWith<UserMistake> {
            callQuery(node, """{ type : "get_persons_by_city", q_city : 999 }""")
        }
    }

    @Test fun testQueryErrArgObjectIdOfWrongClass() {
        val node = setupNodeAndObjects()
        assertFailsWith<UserMistake> {
            callQuery(node, """{ type : "get_persons_by_city", q_city : 5 }""")
        }
    }

    @Test fun testQueryErrRuntimeErrorNoObjects() {
        val node = setupNodeAndObjects()
        assertFailsWith<ProgrammerMistake> {
            callQuery(node, """{ type : "get_city_by_name", q_name : "Den Helder" }""")
        }
    }

    @Test fun testQueryErrRuntimeErrorOther() {
        val node = setupNodeAndObjects()
        assertFailsWith<ProgrammerMistake> {
            callQuery(node, """{ type : "integer_division", q_a : 123, q_b: 0 }""")
        }
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

    private fun setupNodeAndObjects(): SingleChainTestNode {
        val node = setupNode()
        insertObjects(node)
        return node
    }

    private fun setupNode(): SingleChainTestNode {
        val rellModulePath = Paths.get(javaClass.getResource("mymodule.rell").toURI()).toString()
        /* FYI: [et]: Commons config has been used again instead of gtx-config
        gtxConfig = gtx(
                "configurationfactory" to gtx(GTXBlockchainConfigurationFactory::class.qualifiedName!!),
                "signers" to gtxConfigSigners(),
                "gtx" to gtx(
                        "modules" to gtx(listOf(gtx(SQLGTXModuleFactory::class.qualifiedName!!))),
                        "sqlmodules" to gtx(listOf(gtx(sqlModulePath)))
                )
        )
        */

        configOverrides.setProperty("blockchain.1.configurationfactory", GTXBlockchainConfigurationFactory::class.qualifiedName)
        configOverrides.setProperty("blockchain.1.gtx.modules", listOf(RellPostchainModuleFactory::class.qualifiedName))
        configOverrides.setProperty("blockchain.1.gtx.rellSrcModule", rellModulePath)

        return createNode(0)
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
