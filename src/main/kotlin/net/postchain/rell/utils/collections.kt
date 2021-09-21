/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils

import com.google.common.collect.*
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.rell.runtime.utils.toGtv
import org.apache.commons.collections4.IterableUtils
import java.util.*

class ListVsMap<K> private constructor(private val entries: List<Map.Entry<K, *>>) {
    fun <W> listToMap(list: List<W>): Map<K, W> {
        val copy = list.toImmList()
        checkEquals(copy.size, entries.size)
        return entries.mapIndexed { i, e -> e.key to copy[i] }.toMap().toImmMap()
    }

    companion object {
        fun <K, V> mapToList(map: Map<K, V>): Pair<List<V>, ListVsMap<K>> {
            val entries = map.entries.toImmList()
            val list = entries.map { it.value }.toImmList()
            val listVsMap = ListVsMap(entries)
            return Pair(list, listVsMap)
        }
    }
}

fun <T> chainToIterable(head: T?, nextGetter: (T) -> T?): Iterable<T> {
    return if (head == null) IterableUtils.emptyIterable() else ChainIterable(head, nextGetter)
}

private class ChainIterable<T>(private val head: T, private val nextGetter: (T) -> T?): Iterable<T> {
    override fun iterator(): Iterator<T> = ChainIterator()

    private inner class ChainIterator: Iterator<T> {
        private var current: T? = head

        override fun hasNext() = current != null

        override fun next(): T {
            val cur = current
            cur ?: throw NoSuchElementException()
            current = nextGetter(cur)
            return cur
        }
    }
}

fun <T> immListOf(vararg values: T): List<T> = ImmutableList.copyOf(values)
fun <T> immSetOf(): Set<T> = ImmutableSet.of()
fun <T> immSetOf(vararg values: T): Set<T> = ImmutableSet.copyOf(values)
fun <K, V> immMapOf(vararg entries: Pair<K, V>): Map<K, V> = mapOf(*entries).toImmMap()

fun <T> Iterable<T>.toImmList(): List<T> = ImmutableList.copyOf(this)
fun <T> Array<T>.toImmList(): List<T> = ImmutableList.copyOf(this)
fun <T> Iterable<T>.toImmSet(): Set<T> = ImmutableSet.copyOf(this)
fun <T> Array<T>.toImmSet(): Set<T> = ImmutableSet.copyOf(this)
fun <K, V> Map<K, V>.toImmMap(): Map<K, V> = ImmutableMap.copyOf(this)

fun <K, V> immMultimapOf(): Multimap<K, V> = ImmutableMultimap.of()
fun <K, V> mutableMultimapOf(): Multimap<K, V> = LinkedListMultimap.create()
fun <K, V> Multimap<K, V>.toImmMultimap(): Multimap<K, V> = ImmutableMultimap.copyOf(this)

fun <K, V> Iterable<Pair<K, V>>.toImmMultimap(): Multimap<K, V> {
    val m = mutableMultimapOf<K, V>()
    for ((k, v) in this) {
        m.put(k, v)
    }
    return m.toImmMultimap()
}

fun <K, V> Map<K, Iterable<V>>.toImmMultimap(): Multimap<K, V> {
    val map = mutableMultimapOf<K, V>()
    for ((k, v) in this) {
        map.putAll(k, v)
    }
    return map.toImmMultimap()
}

fun <K, V> MutableMap<K, V>.putAllAbsent(map: Map<K, V>) {
    for ((key, value) in map) {
        if (key !in this) {
            put(key, value)
        }
    }
}

fun <T> queueOf(): Queue<T> = ArrayDeque()

// Needs to be in a different file than List<Gtv>.toGtv() because of a name conflict...
fun List<String>.toGtv(): Gtv = GtvFactory.gtv(this.map { it.toGtv() })
