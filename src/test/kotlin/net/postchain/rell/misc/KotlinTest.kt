/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

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
        abstract class Parent {
            abstract val v: Int
            abstract val x0: Int
            val q: Int = v
        }

        class Child(x: Int, y: Int): Parent() {
            override val v: Int = x

            val x1 = y
            override val x0 = x1
            val x2 = x0
        }

        val o = Child(123, 456)
        assertEquals(o.v, 123)
        assertEquals(o.q, 0)
        assertEquals(o.x0, 456)
        assertEquals(o.x1, 456)
        assertEquals(o.x2, 456)
    }

    // Methods .associate and .associateBy don't fail on duplicate keys (for each key, the last value is used).
    @Test fun testAssociate() {
        val l = listOf(1 to "A", 2 to "B", 1 to "C")

        val m1 = l.associate { it }
        assertEquals(mapOf(1 to "C", 2 to "B"), m1)

        val m2 = l.associateBy { it.first }
        assertEquals(mapOf(1 to "C", 2 to "B"), m2.mapValues { it.value.second })
    }

    @Test fun testLet() {
        fun str(s: String, b: Boolean) = if (b) s else null
        val s1 = str("Hello", true)
        val s2 = str("Hello", false)

        assertEquals("let[Hello]", s1.let { "let[$it]" })
        assertEquals("let[null]", s2.let { "let[$it]" })
        assertEquals("let[Hello]", s1?.let { "let[$it]" })
        assertEquals(null, s2?.let { "let[$it]" })
    }

    @Test fun testLazyUnit() {
        class Obj {
            var x = 0
            val lazy by lazy { x += 1 }
        }

        val o = Obj()
        assertEquals(0, o.x)
        o.lazy
        assertEquals(1, o.x)
        o.lazy
        assertEquals(1, o.x)
    }

    @Test fun testLazyNullable() {
        class Obj {
            var x = 0
            val lazy: Int? by lazy { x += 1; null }
        }

        val o = Obj()
        assertEquals(0, o.x)
        assertEquals(null, o.lazy)
        assertEquals(1, o.x)
        assertEquals(null, o.lazy)
        assertEquals(1, o.x)
    }

    @Test fun testLazyThrows() {
        class Obj {
            var x = 0
            val lazy: Int by lazy {
                x += 1
                if (x > 0) throw IllegalStateException() else -1
            }
        }

        val o = Obj()
        assertEquals(0, o.x)
        assertFails { o.lazy }
        assertEquals(1, o.x)
        assertFails { o.lazy }
        assertEquals(2, o.x)
    }

    @Test fun testVarargAndDefaultValues() {
        fun f(a: Int, vararg b: String, c: Int = 987): String = "f(a=$a,b=${b.contentToString()},c=$c)"
        assertEquals("f(a=123,b=[],c=987)", f(123))
        assertEquals("f(a=123,b=[A],c=987)", f(123, "A"))
        assertEquals("f(a=123,b=[A, B],c=987)", f(123, "A", "B"))
        assertEquals("f(a=123,b=[A],c=456)", f(123, "A", c = 456))
        assertEquals("f(a=123,b=[A, B],c=456)", f(123, "A", "B", c = 456))

        // assertEquals("f(a=123,b=[],c=987)", f(123, 456)) // error
        // assertEquals("f(a=123,b=[A, B],c=987)", f(123, "A", 456)) // error
    }
}
