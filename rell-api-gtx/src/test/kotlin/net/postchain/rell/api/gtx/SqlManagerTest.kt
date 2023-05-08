package net.postchain.rell.api.gtx

import net.postchain.rell.base.testutils.SqlTestUtils
import net.postchain.StorageBuilder
import net.postchain.config.app.AppConfig
import net.postchain.rell.base.sql.ConnectionSqlManager
import net.postchain.rell.base.sql.SqlUtils
import org.apache.commons.configuration2.PropertiesConfiguration
import org.junit.Test

/** Test instantiation of various implementation of `SqlManager`. */
class SqlManagerTest {
    @Test fun testSqlManager() {
        SqlTestUtils.createSqlConnection().use {
            ConnectionSqlManager(it, false).execute(true) { executor ->
                SqlUtils.dropAll(executor, true)
            }
        }

        val configuration = PropertiesConfiguration().apply {
            val dbConnProps = SqlTestUtils.readDbProperties()
            addProperty("database.url", dbConnProps.url)
            addProperty("database.username", dbConnProps.user)
            addProperty("database.password", dbConnProps.password)
        }
        StorageBuilder.buildStorage(AppConfig(configuration)).use {
            PostchainStorageSqlManager(it, false)
        }
    }
}