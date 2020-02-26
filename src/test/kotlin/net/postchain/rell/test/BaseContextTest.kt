/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

abstract class BaseContextTest(useSql: Boolean): BaseResourcefulTest() {
    protected val tstCtx = resource(RellTestContext(useSql))
}
