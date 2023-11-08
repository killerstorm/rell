/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.misc

import net.postchain.rell.base.utils.CfManager
import net.postchain.rell.base.utils.CfResult
import org.junit.Test
import kotlin.test.assertEquals

class CalcFlowTest {
    @Test fun test() {
        chkFibonacci(0, 0)
        chkFibonacci(1, 1)
        chkFibonacci(2, 1)
        chkFibonacci(3, 2)
        chkFibonacci(4, 3)
        chkFibonacci(5, 5)
        chkFibonacci(6, 8)
        chkFibonacci(7, 13)
        chkFibonacci(8, 21)
        chkFibonacci(9, 34)
        chkFibonacci(10, 55)
        chkFibonacci(11, 89)
        chkFibonacci(12, 144)
    }

    private fun chkFibonacci(input: Int, expected: Int) {
        val mgr = CfManager()

        fun fibonacci(n: Int): CfResult<Int> {
            return when (n) {
                0, 1 -> CfResult.direct(n)
                else -> {
                    val job1 = mgr.job { fibonacci(n - 2) }
                    val job2 = mgr.job { fibonacci(n - 1) }
                    CfResult.after(job1) { res1 ->
                        CfResult.after(job2) { res2 ->
                            CfResult.direct(res1 + res2)
                        }
                    }
                }
            }
        }

        val job = mgr.job { fibonacci(input) }
        mgr.execute()

        val actual = job.getResult()
        assertEquals(expected, actual)
    }
}
