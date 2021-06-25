/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import net.postchain.gtv.Gtv

abstract class BaseGtxTest(useSql: Boolean = true): BaseTesterTest(useSql) {
    final override val tst = RellGtxTester(tstCtx)

    fun chkFull(code: String, args: String, expected: String) = tst.chkQueryEx(code, args, expected)
    fun chkFull(code: String, args: Map<String, Gtv>, expected: String) = tst.chkQueryEx(code, args, expected)
    fun chkOpFull(code: String, args: List<Gtv>, expected: String = "OK") = tst.chkOpEx(code, args, expected)

    fun chkUserMistake(code: String, msg: String) = tst.chkUserMistake(code, msg)

    fun chkCallOperation(name: String, args: List<String>, expected: String = "OK") =
            tst.chkCallOperation(name, args, expected)

    fun chkCallQuery(name: String, args: String, expected: String) = tst.chkCallQuery(name, args, expected)
}
