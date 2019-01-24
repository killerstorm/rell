package net.postchain.rell.test

import org.junit.After

abstract class BaseGtxTest {
    val tst = RellGtxTester()

    @After fun after() = tst.destroy()
}
