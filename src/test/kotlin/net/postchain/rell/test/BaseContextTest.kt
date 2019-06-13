package net.postchain.rell.test

abstract class BaseContextTest(useSql: Boolean): BaseResourcefulTest() {
    protected val tstCtx = resource(RellTestContext(useSql))
}
