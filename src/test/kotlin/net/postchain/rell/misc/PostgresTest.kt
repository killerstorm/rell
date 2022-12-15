/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import mu.KLogging
import net.postchain.rell.test.SqlTestUtils
import org.junit.Test
import org.postgresql.util.PSQLException
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for problems with Postgres driver 42.5.1,
 * worked around by adding JDBC connection property binaryTransfer=false (or prepareThreshold=0).
 */
class PostgresTest {
    companion object: KLogging()

    @Test fun testSelectDecimalBug() {
        val table = "DecimalSumTest"
        val limit = BigDecimal(BigInteger.TEN.pow(131072).subtract(BigInteger.ONE))

        SqlTestUtils.createSqlConnection().use { con ->
            con.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS $table;")
                stmt.execute("CREATE TABLE $table(v DECIMAL NOT NULL);")
            }

            con.prepareStatement("INSERT INTO $table (v) VALUES (?);").use { stmt ->
                stmt.setBigDecimal(1, limit)
                stmt.execute()
            }

            for (i in 1 .. 10) {
                logger.info("i = $i")
                con.prepareStatement("SELECT v FROM $table;").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(limit, rs.getBigDecimal(1))
                        assertFalse(rs.next())
                    }
                }
            }
        }
    }

    @Test fun testSelectIntegerOverflow() {
        val table = "Test"

        SqlTestUtils.createSqlConnection().use { con ->
            con.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS $table;")
                stmt.execute("CREATE TABLE $table (a BIGINT NOT NULL);")
                stmt.execute("INSERT INTO $table (a) VALUES (9223372036854775807);")
                stmt.execute("INSERT INTO $table (a) VALUES (1);")
            }

            for (i in 1 .. 10) {
                logger.info("i = $i")
                con.prepareStatement("SELECT SUM(a) FROM $table;").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertFailsWith<PSQLException> {
                            rs.getLong(1)
                        }
                        assertFalse(rs.next())
                    }
                }
            }
        }
    }
}
