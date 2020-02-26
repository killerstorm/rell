/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.runcfg

import net.postchain.common.hexStringToByteArray
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.GtvNull
import net.postchain.gtv.GtvType
import java.math.BigInteger

object RunConfigGtvParser {
    fun parseNestedGtv(elem: RellXmlElement): Gtv {
        elem.checkNoText()
        elem.check(elem.elems.size == 1) { "expected exactly one nested element, but found ${elem.elems.size}" }
        val res = parseGtv(elem.elems[0])
        return res
    }

    private fun parseGtv(elem: RellXmlElement): Gtv {
        return when (elem.tag) {
            "null" -> {
                elem.attrs().checkNoMore()
                elem.checkNoText()
                elem.checkNoElems()
                GtvNull
            }
            "int" -> {
                elem.attrs().checkNoMore()
                elem.checkNoElems()
                val value = elem.parseText { BigInteger(it) }
                GtvFactory.gtv(value)
            }
            "string" -> {
                elem.attrs().checkNoMore()
                elem.checkNoElems()
                val value = elem.text ?: ""
                GtvFactory.gtv(value)
            }
            "bytea" -> {
                elem.attrs().checkNoMore()
                elem.checkNoElems()
                val value = elem.parseText { it.hexStringToByteArray() }
                GtvFactory.gtv(value)
            }
            "array" -> parseGtvArray(elem)
            "dict" -> parseGtvDict(elem)
            else -> throw elem.errorTag()
        }
    }

    private fun parseGtvArray(elem: RellXmlElement): Gtv {
        elem.attrs().checkNoMore()
        elem.checkNoText()

        val list = mutableListOf<Gtv>()
        for (sub in elem.elems) {
            val gtv = parseGtv(sub)
            list.add(gtv)
        }

        return GtvFactory.gtv(list)
    }

    private fun parseGtvDict(elem: RellXmlElement): Gtv {
        elem.attrs().checkNoMore()
        elem.checkNoText()

        val map = mutableMapOf<String, Gtv>()
        for (sub in elem.elems) {
            sub.checkTag("entry")
            sub.checkNoText()

            val attrs = sub.attrs()
            val key = attrs.get("key")
            attrs.checkNoMore()

            sub.check(key !in map) { "duplicate entry key: '$key'" }

            val gtv = parseNestedGtv(sub)
            map[key] = gtv
        }

        return GtvFactory.gtv(map)
    }
}

class RunConfigGtvBuilder {
    private var value: Gtv = GtvFactory.gtv(mapOf())

    fun update(gtv: Gtv, vararg path: String) {
        val pathGtv = makeGtvPath(gtv, *path)
        value = update(value, pathGtv)
    }

    fun build() = value

    private fun makeGtvPath(value: Gtv, vararg path: String): Gtv {
        var res: Gtv = value
        for (key in path.reversed()) {
            res = GtvFactory.gtv(mapOf(key to res))
        }
        return res
    }

    companion object {
        fun update(value: Gtv, update: Gtv): Gtv {
            return update(value, update, listOf())
        }

        private fun update(value: Gtv, update: Gtv, path: List<String>): Gtv {
            val type = value.type
            return if (type == GtvType.DICT) {
                updateDict(value, update, path)
            } else if (type == GtvType.ARRAY) {
                updateArray(value, update, path)
            } else {
                update
            }
        }

        private fun updateDict(value: Gtv, update: Gtv, path: List<String>): Gtv {
            checkUpdateType(value, update, GtvType.DICT, path)

            val valueMap = value.asDict()
            val updateMap = update.asDict()
            if (updateMap.isEmpty()) {
                return value
            } else if (valueMap.isEmpty()) {
                return update
            }

            val res = mutableMapOf<String, Gtv>()
            res.putAll(valueMap)

            for ((key, updValue) in updateMap) {
                val oldValue = res[key]
                val resValue = if (oldValue == null) updValue else {
                    update(oldValue, updValue, path + key)
                }
                res[key] = resValue
            }

            return GtvFactory.gtv(res)
        }

        private fun updateArray(value: Gtv, update: Gtv, path: List<String>): Gtv {
            checkUpdateType(value, update, GtvType.ARRAY, path)

            val valueArray = value.asArray()
            val updateArray = update.asArray()
            if (updateArray.isEmpty()) {
                return value
            } else if (valueArray.isEmpty()) {
                return update
            }

            val res = mutableListOf<Gtv>()
            res.addAll(valueArray)
            res.addAll(updateArray)

            return GtvFactory.gtv(res)
        }

        private fun checkUpdateType(value: Gtv, update: Gtv, expectedType: GtvType, path: List<String>) {
            checkUpdate(update.type == expectedType, path) { "cannot merge ${update.type} to ${value.type}" }
        }

        private fun checkUpdate(b: Boolean, path: List<String>, msgCode: () -> String) {
            check(b) {
                val msg = msgCode()
                val pathStr = path.joinToString("/")
                "$msg [path: $pathStr]"
            }
        }
    }
}
