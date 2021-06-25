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

    @Test fun testIntRangeStep() {
        assertEquals(listOf<Int>().indices.step(2).map { it }, listOf())
        assertEquals(listOf(1).indices.step(2).map { it }, listOf(0))
        assertEquals(listOf(1,2).indices.step(2).map { it }, listOf(0))
        assertEquals(listOf(1,2,3).indices.step(2).map { it }, listOf(0, 2))
        assertEquals(listOf(1,2,3,4).indices.step(2).map { it }, listOf(0, 2))
    }

    @Test fun testChildClass() {
        val o = Child(123, 456)
        assertEquals(o.v, 123)
        assertEquals(o.q, 0)
        assertEquals(o.x0, 456)
        assertEquals(o.x1, 456)
        assertEquals(o.x2, 456)
    }

    private abstract class Parent {
        abstract val v: Int
        abstract val x0: Int
        val q: Int = v
    }

    private class Child(x: Int, y: Int): Parent() {
        override val v: Int = x

        val x1 = y
        override val x0 = x1
        val x2 = x0
    }
}
