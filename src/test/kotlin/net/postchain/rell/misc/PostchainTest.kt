/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import net.postchain.base.BaseEContext
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.rell.sql.ConnectionSqlExecutor
import net.postchain.rell.sql.SqlUtils
import net.postchain.rell.test.BaseResourcefulTest
import net.postchain.rell.test.RellTestUtils
import net.postchain.rell.test.SqlTestUtils
import net.postchain.rell.utils.PostchainUtils
import org.junit.Test
import java.sql.Connection
import kotlin.test.assertEquals

class PostchainTest: BaseResourcefulTest() {
    @Test fun testInitializeApp() {
        val con = resource(SqlTestUtils.createSqlConnection())
        SqlUtils.dropAll(ConnectionSqlExecutor(con), true)
        chkTables(con, "")

        sqlAccess().initializeApp(con, PostchainUtils.DATABASE_VERSION)

        chkTables(con,
                "blockchain_replicas(blockchain_rid:text,node:text)",
                "blockchains(blockchain_rid:bytea,chain_iid:int8)",
                "containers(container_iid:serial,name:text)",
                "meta(key:text,value:text)",
                "must_sync_until(block_height:int8,chain_iid:int8)",
                "peerinfos(host:text,port:int4,pub_key:text,timestamp:timestamp)"
        )

        sqlAccess().initializeApp(con, PostchainUtils.DATABASE_VERSION)

        chkTables(con,
                "blockchain_replicas(blockchain_rid:text,node:text)",
                "blockchains(blockchain_rid:bytea,chain_iid:int8)",
                "containers(container_iid:serial,name:text)",
                "meta(key:text,value:text)",
                "must_sync_until(block_height:int8,chain_iid:int8)",
                "peerinfos(host:text,port:int4,pub_key:text,timestamp:timestamp)"
        )
    }

    @Test fun testInitializeBlockchain() {
        val con = resource(SqlTestUtils.createSqlConnection())
        SqlUtils.dropAll(ConnectionSqlExecutor(con), true)
        val sa = sqlAccess()
        sa.initializeApp(con, PostchainUtils.DATABASE_VERSION)

        chkTables(con,
                "blockchain_replicas(blockchain_rid:text,node:text)",
                "blockchains(blockchain_rid:bytea,chain_iid:int8)",
                "containers(container_iid:serial,name:text)",
                "meta(key:text,value:text)",
                "must_sync_until(block_height:int8,chain_iid:int8)",
                "peerinfos(host:text,port:int4,pub_key:text,timestamp:timestamp)"
        )

        val bcRid1 = RellTestUtils.strToBlockchainRid("CEED")
        sa.initializeBlockchain(BaseEContext(con, 123L, sa), bcRid1)

        chkTables(con,
                "blockchain_replicas(blockchain_rid:text,node:text)",
                "blockchains(blockchain_rid:bytea,chain_iid:int8)",
                "c123.blocks(block_header_data:bytea,block_height:int8,block_iid:bigserial,block_rid:bytea,block_witness:bytea,timestamp:int8)",
                "c123.configurations(configuration_data:bytea,height:int8)",
                "c123.transactions(block_iid:int8,tx_data:bytea,tx_hash:bytea,tx_iid:bigserial,tx_rid:bytea)",
                "containers(container_iid:serial,name:text)",
                "meta(key:text,value:text)",
                "must_sync_until(block_height:int8,chain_iid:int8)",
                "peerinfos(host:text,port:int4,pub_key:text,timestamp:timestamp)"
        )

        sa.initializeApp(con, PostchainUtils.DATABASE_VERSION)

        chkTables(con,
                "blockchain_replicas(blockchain_rid:text,node:text)",
                "blockchains(blockchain_rid:bytea,chain_iid:int8)",
                "c123.blocks(block_header_data:bytea,block_height:int8,block_iid:bigserial,block_rid:bytea,block_witness:bytea,timestamp:int8)",
                "c123.configurations(configuration_data:bytea,height:int8)",
                "c123.transactions(block_iid:int8,tx_data:bytea,tx_hash:bytea,tx_iid:bigserial,tx_rid:bytea)",
                "containers(container_iid:serial,name:text)",
                "meta(key:text,value:text)",
                "must_sync_until(block_height:int8,chain_iid:int8)",
                "peerinfos(host:text,port:int4,pub_key:text,timestamp:timestamp)"
        )

        val bcRid2 = RellTestUtils.strToBlockchainRid("FEED")
        sa.initializeBlockchain(BaseEContext(con, 456L, sa), bcRid2)

        chkTables(con,
                "blockchain_replicas(blockchain_rid:text,node:text)",
                "blockchains(blockchain_rid:bytea,chain_iid:int8)",
                "c123.blocks(block_header_data:bytea,block_height:int8,block_iid:bigserial,block_rid:bytea,block_witness:bytea,timestamp:int8)",
                "c123.configurations(configuration_data:bytea,height:int8)",
                "c123.transactions(block_iid:int8,tx_data:bytea,tx_hash:bytea,tx_iid:bigserial,tx_rid:bytea)",
                "c456.blocks(block_header_data:bytea,block_height:int8,block_iid:bigserial,block_rid:bytea,block_witness:bytea,timestamp:int8)",
                "c456.configurations(configuration_data:bytea,height:int8)",
                "c456.transactions(block_iid:int8,tx_data:bytea,tx_hash:bytea,tx_iid:bigserial,tx_rid:bytea)",
                "containers(container_iid:serial,name:text)",
                "meta(key:text,value:text)",
                "must_sync_until(block_height:int8,chain_iid:int8)",
                "peerinfos(host:text,port:int4,pub_key:text,timestamp:timestamp)"
        )
    }

    private fun chkTables(con: Connection, vararg expectedTables: String) {
        val dump = SqlTestUtils.dumpTablesStructure(con, true)
        val actual = dump
                .mapValues { (_, v) -> v.entries.joinToString(",") { it.key + ":" + it.value } }
                .entries
                .map { (k, v) -> "$k($v)" }
                .joinToString(" ")
        val expected = expectedTables.joinToString(" ")
        assertEquals(expected, actual)
    }

    private fun sqlAccess(): SQLDatabaseAccess = PostgreSQLDatabaseAccess()
}
