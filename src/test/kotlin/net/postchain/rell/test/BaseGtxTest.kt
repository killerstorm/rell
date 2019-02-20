package net.postchain.rell.test

abstract class BaseGtxTest: BaseResourcefulTest() {
    val tst = resource(RellGtxTester())
}
