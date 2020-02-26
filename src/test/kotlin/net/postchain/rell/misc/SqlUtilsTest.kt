/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import net.postchain.rell.sql.SqlUtils
import net.postchain.rell.test.SqlTestUtils
import org.junit.Test
import kotlin.test.assertEquals

class SqlUtilsTest {
    @Test fun testSchemaDoesNotExist() {
        val schema = "sql_test_schema"

        SqlTestUtils.createSqlConnection().use { con ->
            val s = con.createStatement()
            s.execute("DROP SCHEMA IF EXISTS $schema CASCADE;")
        }

        SqlTestUtils.createSqlConnection().use { con ->
            con.schema = schema
            assertEquals(null, con.schema)

            val s = con.createStatement()
            s.execute("CREATE SCHEMA IF NOT EXISTS $schema;")
            assertEquals(schema, con.schema)

            s.execute("CREATE TABLE foo(id INT NOT NULL);") // Must not fail.
        }
    }

    @Test fun testSchemaDoesNotExist2() {
        val schema = "sql_test_schema"

        SqlTestUtils.createSqlConnection().use { con ->
            val s = con.createStatement()
            s.execute("DROP SCHEMA IF EXISTS $schema CASCADE;")
        }

        SqlTestUtils.createSqlConnection(schema).use { con ->
            assertEquals(null, con.schema)

            val s = con.createStatement()
            s.execute("CREATE SCHEMA IF NOT EXISTS $schema;")
            assertEquals(schema, con.schema)

            s.execute("CREATE TABLE foo(id INT NOT NULL);") // Must not fail.
        }
    }

    @Test fun testExtractDatabaseSchema() {
        chkExtractDatabaseSchema("jdbc:postgresql://localhost/postchain", null)
        chkExtractDatabaseSchema("jdbc:postgresql://localhost/postchain?currentSchema=foobar", "foobar")
        chkExtractDatabaseSchema("jdbc:postgresql://localhost/postchain?currentSchema=foobar&x=y", "foobar")
        chkExtractDatabaseSchema("jdbc:postgresql://localhost/postchain?x=y&currentSchema=foobar", "foobar")

        chkExtractDatabaseSchema("jdbc:postgresql://localhost", null)
        chkExtractDatabaseSchema("jdbc:postgresql://localhost?currentSchema=foobar", "foobar")
        chkExtractDatabaseSchema("jdbc:postgresql://localhost?currentSchema=foobar&x=y", "foobar")
        chkExtractDatabaseSchema("jdbc:postgresql://localhost?x=y&currentSchema=foobar", "foobar")
    }

    private fun chkExtractDatabaseSchema(url: String, expected: String?) {
        val actual = SqlUtils.extractDatabaseSchema(url)
        assertEquals(expected, actual)
    }
}
