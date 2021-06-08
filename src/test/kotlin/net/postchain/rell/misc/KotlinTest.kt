/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import org.junit.Test
import kotlin.test.assertEquals

class KotlinTest {
    @Test fun testListMutability() {
        val mut = mutableListOf(1, 2, 3)
        val imm = mut.toList()
        mut.add(4)
        assertEquals(listOf(1, 2, 3), imm)
        assertEquals(listOf(1, 2, 3, 4), mut)

        val imm2: List<Int> = mut
        assertEquals(listOf(1, 2, 3, 4), imm2)

        mut.add(5)
        assertEquals(listOf(1, 2, 3, 4, 5), imm2)

        val mut2 = imm as MutableList<Int>
        assertEquals(listOf(1, 2, 3), mut2)

        mut2.add(7)
        assertEquals(listOf(1, 2, 3, 7), mut2)
        assertEquals(listOf(1, 2, 3, 7), imm)
        assertEquals(listOf(1, 2, 3, 4, 5), mut)
    }

    @Test fun testListPredicateSideEffects() {
        chkListSideEffects { l, p -> l.firstOrNull(p) }
        chkListSideEffects { l, p -> l.any(p) }
        chkListSideEffects { l, p -> l.all { !p(it) } }
    }

    private fun chkListSideEffects(f: (List<Int>, (Int) -> Boolean) -> Unit) {
        val t = mutableListOf<Int>()
        val l = listOf(1, 2, 3)
        f(l) { t.add(it) }
        assertEquals(t, listOf(1))
    }
}
