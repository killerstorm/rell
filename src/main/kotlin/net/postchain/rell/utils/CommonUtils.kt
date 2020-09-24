/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils

import java.io.File
import javax.xml.bind.DatatypeConverter

object CommonUtils {
    fun bytesToHex(bytes: ByteArray): String = DatatypeConverter.printHexBinary(bytes).toLowerCase()
    fun hexToBytes(hex: String): ByteArray = DatatypeConverter.parseHexBinary(hex)

    fun <T> split(lst: MutableList<T>, partSize: Int): List<MutableList<T>> {
        val s = lst.size
        if (s <= partSize) {
            return listOf(lst)
        }

        val parts = (s + partSize - 1) / partSize
        val res = (0 until parts).map { lst.subList(it * partSize, Math.min((it + 1) * partSize, s)) }
        return res
    }

    fun <T: Comparable<T>> compareLists(l1: List<T>, l2: List<T>): Int {
        val n1 = l1.size
        val n2 = l2.size
        for (i in 0 until Math.min(n1, n2)) {
            val c = l1[i].compareTo(l2[i])
            if (c != 0) {
                return c
            }
        }
        return n1.compareTo(n2)
    }

    /** Invokes the key getter strictly one time for each item (builds list of pairs (item, key), then sorts). */
    fun <T, K: Comparable<K>> sortedByCopy(data: Collection<T>, keyGetter: (T) -> K): List<T> {
        val pairs = data.map { Pair(it, keyGetter(it)) }.sortedBy { it.second }
        return pairs.map { it.first }
    }

    fun readFileContent(filename: String): String {
        /*
        * FYI: We use Spring convention here when files under resources are labeled with prefix 'classpath:'.
        * */
        val resourcePrefix = "classpath:"
        return if (filename.startsWith(resourcePrefix)) {
            javaClass.getResource(filename.substringAfter(resourcePrefix))
                    .readText()
        } else {
            File(filename).readText()
        }
    }

    fun <T> calcOpt(f: () -> T): T? {
        try {
            return f()
        } catch (e: Throwable) {
            return null
        }
    }

    fun <T> foldSimple(items: Iterable<T>, op: (T, T) -> T): T {
        val iter = items.iterator()
        check(iter.hasNext())

        var res = iter.next()
        while (iter.hasNext()) {
            var item = iter.next()
            res = op(res, item)
        }

        return res
    }

    fun getHomeDir(): File? {
        val homePath = System.getProperty("user.home")
        if (homePath == null) return null
        val homeDir = File(homePath)
        return if (homeDir.isDirectory) homeDir else null
    }

    fun <T> chainToList(first: T?, nextGetter: (T) -> T?): List<T> {
        if (first == null) return immListOf()

        val res = mutableListOf<T>()
        var cur = first
        while (cur != null) {
            res.add(cur)
            cur = nextGetter(cur)
        }

        return res.toImmList()
    }

    fun tableToStrings(table: List<List<String>>): List<String> {
        val widths = mutableListOf<Int>()

        for (row in table) {
            for ((i, cell) in row.withIndex()) {
                if (widths.size <= i) widths.add(0)
                widths[i] = Math.max(widths[i], cell.length)
            }
        }

        return table.map { row ->
            row
                    .mapIndexed { i, cell -> if (i == row.size - 1) cell else cell.padEnd(widths[i]) }
                    .joinToString("   ")
        }
    }
}
