package net.postchain.rell.test

abstract class BaseGtxTest: BaseResourcefulTest() {
    val tstCtx = resource(RellTestContext())
    val tst = RellGtxTester(tstCtx)
}
