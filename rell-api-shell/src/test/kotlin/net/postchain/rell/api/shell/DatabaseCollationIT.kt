package net.postchain.rell.api.shell

import net.postchain.common.exception.UserMistake
import net.postchain.rell.api.base.RellApiCompile
import net.postchain.rell.api.gtx.RellApiRunTests
import net.postchain.rell.base.testutils.RellReplTester
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseCollationIT {
    @Test fun testCollationTestPass() {
        PostgreSQLContainer(DockerImageName.parse("postgres:14.7-alpine3.17")).apply { start() }.use { postgres ->
            val databaseUrlWithUserAndPassword =
                buildDatabaseUrl(postgres.jdbcUrl, postgres.username, postgres.password)
            chkRunTests(databaseUrlWithUserAndPassword)
            chkRunShell(databaseUrlWithUserAndPassword)
        }
    }

    @Test fun testCollationTestFail() {
        PostgreSQLContainer(DockerImageName.parse("postgres:14.7")).apply { start() }.use { postgres ->
            val databaseUrlWithUserAndPassword =
                buildDatabaseUrl(postgres.jdbcUrl, postgres.username, postgres.password)

            assertTrue {
                (assertFailsWith<UserMistake> {
                    chkRunTests(databaseUrlWithUserAndPassword)
                }.message ?: "").contains("Database collation check failed")
            }

            assertTrue {
                (assertFailsWith<UserMistake> {
                    chkRunShell(databaseUrlWithUserAndPassword)
                }.message ?: "").contains("Database collation check failed")
            }
        }
    }

    private fun buildDatabaseUrl(databaseUrl: String, user: String, password: String) =
        databaseUrl + (if (databaseUrl.contains('?')) '&' else '?') + "user=$user&password=$password"

    private fun chkRunTests(databaseUrl: String) {
        val compileConfig = RellApiCompile.Config.Builder()
            .build()
        val testConfig = RellApiRunTests.Config.Builder()
            .compileConfig(compileConfig)
            .databaseUrl(databaseUrl)
            .build()
        val res = RellApiRunTests.runTests(testConfig, File("../work/testproj/src"), listOf(), listOf("tests.data_test"))
        assertTrue { res.getResults().all { it.res.isOk } }
    }

    private fun chkRunShell(databaseUrl: String) {
        val compileConfig = RellApiCompile.Config.Builder()
            .build()
        val shellConfig = RellApiRunShell.Config.Builder()
            .compileConfig(compileConfig)
            .databaseUrl(databaseUrl)
            .inputChannelFactory(RellReplTester.TestReplInputChannelFactory(listOf()))
            .build()
        RellApiRunShell.runShell(shellConfig, File("../work/testproj/src"), "repl.company")
    }
}
