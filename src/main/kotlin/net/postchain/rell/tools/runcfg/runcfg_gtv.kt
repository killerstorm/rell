/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.runcfg

import net.postchain.common.hexStringToByteArray
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.GtvNull
import net.postchain.gtv.GtvType
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.rell.runtime.utils.toGtv
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import java.math.BigInteger

enum class Rcfg_Gtv_ArrayMerge {
    REPLACE,
    APPEND,
    PREPEND,
    ;

    companion object {
        fun parse(s: String) = when (s) {
            "replace" -> REPLACE
            "append" -> APPEND
            "prepend" -> PREPEND
            else -> null
        }
    }
}

enum class Rcfg_Gtv_DictMerge {
    REPLACE,
    KEEP_OLD,
    KEEP_NEW,
    STRICT,
    ;

    companion object {
        fun parseDict(s: String) = when (s) {
            "replace" -> REPLACE
            "keep-old" -> KEEP_OLD
            "keep-new" -> KEEP_NEW
            "strict" -> STRICT
            else -> null
        }

        fun parseEntry(s: String) = when (s) {
            "keep-old" -> KEEP_OLD
            "keep-new" -> KEEP_NEW
            "strict" -> STRICT
            else -> null
        }
    }
}

sealed class Rcfg_Gtv {
    abstract fun type(): GtvType
    abstract fun toGtv(): Gtv

    open fun asArray(): Rcfg_Gtv_Array = errBadType(GtvType.ARRAY)
    open fun asDict(): Rcfg_Gtv_Dict = errBadType(GtvType.DICT)

    private fun errBadType(expected: GtvType): Nothing {
        throw IllegalStateException("expected $expected actual ${type()}")
    }

    abstract fun merge(old: Gtv, path: List<String>): Gtv

    companion object {
        fun decode(gtv: Gtv): Rcfg_Gtv {
            return when (gtv.type) {
                GtvType.ARRAY -> {
                    val elems = gtv.asArray().map { decode(it) }
                    Rcfg_Gtv_Array(elems, Rcfg_Gtv_ArrayMerge.APPEND)
                }
                GtvType.DICT -> {
                    val elems = gtv.asDict().mapValues {
                        Rcfg_Gtv_DictEntry(decode(it.value), Rcfg_Gtv_DictMerge.KEEP_NEW)
                    }
                    Rcfg_Gtv_Dict(elems, Rcfg_Gtv_DictMerge.KEEP_NEW)
                }
                else -> Rcfg_Gtv_Term(gtv)
            }
        }
    }
}

class Rcfg_Gtv_Term(private val gtv: Gtv): Rcfg_Gtv() {
    init {
        val type = gtv.type
        check(type != GtvType.DICT && type != GtvType.ARRAY) { type }
    }

    override fun type() = gtv.type
    override fun toGtv() = gtv

    override fun merge(old: Gtv, path: List<String>): Gtv {
        return gtv
    }
}

class Rcfg_Gtv_Array(values: List<Rcfg_Gtv>, val merge: Rcfg_Gtv_ArrayMerge): Rcfg_Gtv() {
    val values = values.toImmList()

    override fun type() = GtvType.ARRAY
    override fun toGtv() = values.map { it.toGtv() }.toGtv()
    override fun asArray() = this

    override fun merge(old: Gtv, path: List<String>): Gtv {
        checkUpdateType(type(), old.type, path)

        val oldValues = old.asArray()
        val newValues = values

        if (merge == Rcfg_Gtv_ArrayMerge.REPLACE) {
            return toGtv()
        }

        if (newValues.isEmpty()) {
            return old
        } else if (oldValues.isEmpty()) {
            return toGtv()
        }

        val updateElems = newValues.map { it.toGtv() }

        val resElems = when (merge) {
            Rcfg_Gtv_ArrayMerge.REPLACE -> updateElems
            Rcfg_Gtv_ArrayMerge.APPEND -> oldValues.toList() + updateElems
            Rcfg_Gtv_ArrayMerge.PREPEND -> updateElems + oldValues.toList()
        }

        return GtvFactory.gtv(resElems)
    }
}

class Rcfg_Gtv_DictEntry(val value: Rcfg_Gtv, val merge: Rcfg_Gtv_DictMerge)

class Rcfg_Gtv_Dict(values: Map<String, Rcfg_Gtv_DictEntry>, val merge: Rcfg_Gtv_DictMerge): Rcfg_Gtv() {
    val values = values.toImmMap()

    override fun type() = GtvType.DICT
    override fun toGtv() = values.mapValues { it.value.value.toGtv() }.toGtv()
    override fun asDict() = this

    override fun merge(old: Gtv, path: List<String>): Gtv {
        checkUpdateType(type(), old.type, path)

        val oldMap = old.asDict()
        val newMap = values

        if (merge == Rcfg_Gtv_DictMerge.REPLACE) {
            return toGtv()
        }

        if (newMap.isEmpty()) {
            return old
        } else if (oldMap.isEmpty()) {
            return toGtv()
        }

        val res = mutableMapOf<String, Gtv>()
        res.putAll(oldMap)

        for ((key, updEntry) in newMap) {
            val oldValue = res[key]
            val resValue = if (oldValue == null) updEntry.value.toGtv() else {
                when (updEntry.merge) {
                    Rcfg_Gtv_DictMerge.KEEP_OLD -> oldValue
                    Rcfg_Gtv_DictMerge.KEEP_NEW, Rcfg_Gtv_DictMerge.REPLACE -> {
                        updEntry.value.merge(oldValue, path + key)
                    }
                    Rcfg_Gtv_DictMerge.STRICT -> {
                        failUpdate(path, "Gtv dict key conflict: '$key'")
                    }
                }
            }
            res[key] = resValue
        }

        return GtvFactory.gtv(res)
    }
}

object RunConfigGtvParser {
    fun parseGtv(elem: RellXmlElement): Rcfg_Gtv {
        return parseTopGtv(elem, true)
    }

    fun parseGtvRaw(elem: RellXmlElement): Gtv {
        val rgtv = parseTopGtv(elem, false)
        return rgtv.toGtv()
    }

    private fun parseTopGtv(elem: RellXmlElement, mergeAllowed: Boolean): Rcfg_Gtv {
        elem.checkNoText()
        elem.check(elem.elems.size == 1) { "expected exactly one nested element, but found ${elem.elems.size}" }
        val res = parseGtvNode(elem.elems[0], mergeAllowed)
        return res
    }

    fun parseGtvNode(elem: RellXmlElement, mergeAllowed: Boolean): Rcfg_Gtv {
        return when (elem.tag) {
            "null" -> {
                elem.attrs().checkNoMore()
                elem.checkNoText()
                elem.checkNoElems()
                Rcfg_Gtv_Term(GtvNull)
            }
            "int" -> {
                elem.attrs().checkNoMore()
                elem.checkNoElems()
                val value = elem.parseText { it.toLong() }
                Rcfg_Gtv_Term(GtvFactory.gtv(value))
            }
            "string" -> {
                elem.attrs().checkNoMore()
                elem.checkNoElems()
                val value = elem.text ?: ""
                Rcfg_Gtv_Term(GtvFactory.gtv(value))
            }
            "bytea" -> {
                elem.attrs().checkNoMore()
                elem.checkNoElems()
                val value = elem.parseText { it.hexStringToByteArray() }
                Rcfg_Gtv_Term(GtvFactory.gtv(value))
            }
            "array" -> parseGtvArray(elem, mergeAllowed)
            "dict" -> parseGtvDict(elem, mergeAllowed)
            else -> throw elem.errorTag()
        }
    }

    private fun parseGtvArray(elem: RellXmlElement, mergeAllowed: Boolean): Rcfg_Gtv {
        val attrs = elem.attrs()
        val merge = if (!mergeAllowed) null else attrs.getTypeOpt("merge", null, parser = { Rcfg_Gtv_ArrayMerge.parse(it) })
        attrs.checkNoMore()

        elem.checkNoText()

        val values = elem.elems.map { parseGtvNode(it, mergeAllowed) }
        return Rcfg_Gtv_Array(values, merge ?: Rcfg_Gtv_ArrayMerge.APPEND)
    }

    private fun parseGtvDict(elem: RellXmlElement, mergeAllowed: Boolean): Rcfg_Gtv {
        val attrs = elem.attrs()
        val merge0 = if (!mergeAllowed) null else attrs.getTypeOpt("merge", null, parser = { Rcfg_Gtv_DictMerge.parseDict(it) })
        val merge = merge0 ?: Rcfg_Gtv_DictMerge.KEEP_NEW
        attrs.checkNoMore()
        elem.checkNoText()

        val map = mutableMapOf<String, Rcfg_Gtv_DictEntry>()
        for (sub in elem.elems) {
            val (key, entry) = parseGtvDictEntry(sub, merge, mergeAllowed)
            sub.check(key !in map) { "duplicate entry key: '$key'" }
            map[key] = entry
        }

        return Rcfg_Gtv_Dict(map, merge)
    }

    private fun parseGtvDictEntry(
            elem: RellXmlElement,
            dictMerge: Rcfg_Gtv_DictMerge,
            mergeAllowed: Boolean
    ): Pair<String, Rcfg_Gtv_DictEntry> {
        elem.checkTag("entry")
        elem.checkNoText()

        val attrs = elem.attrs()
        val key = attrs.get("key")
        val merge = if (!mergeAllowed) null else attrs.getTypeOpt("merge", null, parser = { Rcfg_Gtv_DictMerge.parseEntry(it) })
        attrs.checkNoMore()

        val value = parseGtv(elem)
        return key to Rcfg_Gtv_DictEntry(value, merge ?: dictMerge)
    }
}

class RunConfigGtvBuilder {
    private var value: Gtv = GtvFactory.gtv(mapOf())

    fun update(gtv: Gtv, vararg path: String) {
        val rGtv = Rcfg_Gtv.decode(gtv)
        update(rGtv, *path)
    }

    fun update(gtv: Rcfg_Gtv, vararg path: String) {
        val pathGtv = makeGtvPath(gtv, *path)
        value = pathGtv.merge(value, listOf())
    }

    fun build() = value

    private fun makeGtvPath(value: Rcfg_Gtv, vararg path: String): Rcfg_Gtv {
        var res: Rcfg_Gtv = value
        for (key in path.reversed()) {
            val elems = mapOf(key to Rcfg_Gtv_DictEntry(res, Rcfg_Gtv_DictMerge.KEEP_NEW))
            res = Rcfg_Gtv_Dict(elems, Rcfg_Gtv_DictMerge.KEEP_NEW)
        }
        return res
    }
}

private fun checkUpdateType(actualType: GtvType, expectedType: GtvType, path: List<String>) {
    checkUpdate(actualType == expectedType, path) { "cannot merge $actualType to $expectedType" }
}

private fun checkUpdate(b: Boolean, path: List<String>, msgCode: () -> String) {
    if (!b) {
        val msg = msgCode()
        failUpdate(path, msg)
    }
}

private fun failUpdate(path: List<String>, msg: String): Nothing {
    val pathStr = path.joinToString("/")
    throw IllegalStateException("$msg [path: $pathStr]")
}
