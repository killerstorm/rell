/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import com.google.common.math.LongMath
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_ListType
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.utils.immListOf

object Lib_Type_List {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("list") {
            generic("T")
            parent("collection<T>")

            rType { t -> R_ListType(t) }

            constructor(pure = true) {
                bodyMeta {
                    val elementType = fnBodyMeta.typeArg("T")
                    val rKind = R_CollectionKind_List(R_ListType(elementType))
                    body { ->
                        rKind.makeRtValue(immListOf())
                    }
                }
            }

            constructor(pure = true) {
                param("values", type = "iterable<-T>")
                bodyMeta {
                    val elementType = fnBodyMeta.typeArg("T")
                    val rKind = R_CollectionKind_List(R_ListType(elementType))
                    body { arg ->
                        val iterable = arg.asIterable()
                        rKind.makeRtValue(iterable)
                    }
                }
            }

            function("get", "T", pure = true) {
                param("index", "integer")
                body { a, b ->
                    val list = a.asList()
                    val i = b.asInteger()
                    if (i < 0 || i >= list.size) {
                        throw Rt_Exception.common(
                            "fn:list.get:index:${list.size}:$i",
                            "List index out of bounds: $i (size ${list.size})",
                        )
                    }
                    list[i.toInt()]
                }
            }

            function("index_of", "integer", pure = true) {
                alias("indexOf", deprecated = C_MessageType.ERROR)
                param("value", "T")
                body { a, b ->
                    val list = a.asList()
                    Rt_IntValue.get(list.indexOf(b).toLong())
                }
            }

            function("remove_at", "T") {
                alias("removeAt", deprecated = C_MessageType.ERROR)
                param("index", "integer")
                body { a, b ->
                    val list = a.asList()
                    val i = b.asInteger()

                    if (i < 0 || i >= list.size) {
                        throw Rt_Exception.common(
                            "fn:list.remove_at:index:${list.size}:$i",
                            "Index out of range: $i (size ${list.size})",
                        )
                    }

                    val r = list.removeAt(i.toInt())
                    r
                }
            }

            function("sub", "list<T>", pure = true) {
                param("start", "integer")
                body { a, b ->
                    val type = a.type()
                    val list = a.asList()
                    val start = b.asInteger()
                    calcSub(type, list, start, list.size.toLong())
                }
            }

            function("sub", "list<T>", pure = true) {
                param("start", "integer")
                param("end", "integer")
                body { a, b, c ->
                    val type = a.type()
                    val list = a.asList()
                    val start = b.asInteger()
                    val end = c.asInteger()
                    calcSub(type, list, start, end)
                }
            }

            function("set", "T") {
                alias("_set", deprecated = C_MessageType.WARNING)
                param("index", "integer")
                param("value", "T")
                body { a, b, c ->
                    val list = a.asList()
                    val i = b.asInteger()

                    if (i < 0 || i >= list.size) {
                        throw Rt_Exception.common(
                            "fn:list.set:index:${list.size}:$i",
                            "Index out of range: $i (size ${list.size})",
                        )
                    }

                    val r = list.set(i.toInt(), c)
                    r
                }
            }

            function("add", "boolean") {
                param("index", "integer")
                param("value", "T")
                body { a, b, c ->
                    val list = a.asList()
                    val i = b.asInteger()

                    if (i < 0 || i > list.size) {
                        throw Rt_Exception.common(
                            "fn:list.add:index:${list.size}:$i",
                            "Index out of range: $i (size ${list.size})",
                        )
                    }

                    list.add(i.toInt(), c)
                    Rt_BooleanValue.TRUE
                }
            }

            function("add_all", "boolean") {
                alias("addAll", deprecated = C_MessageType.ERROR)
                param("index", "integer")
                param("values", "collection<-T>")
                body { a, b, c ->
                    val list = a.asList()
                    val i = b.asInteger()
                    val col = c.asCollection()

                    if (i < 0 || i > list.size) {
                        throw Rt_Exception.common("fn:list.add_all:index:${list.size}:$i", "Index out of range: $i (size ${list.size})")
                    }

                    val r = list.addAll(i.toInt(), col)
                    Rt_BooleanValue.get(r)
                }
            }

            function("sort", "unit") {
                alias("_sort", deprecated = C_MessageType.WARNING)
                bodyMeta {
                    val valueType = fnBodyMeta.typeArg("T")
                    val comparator = Lib_Type_Collection.getSortComparator(this, valueType)
                    body { a ->
                        val list = a.asList()
                        list.sortWith(comparator)
                        Rt_UnitValue
                    }
                }
            }

            function("repeat", "list<T>") {
                param("n", "integer")
                body { a, b ->
                    val list = a.asList()
                    val n = b.asInteger()

                    val total = rtCheckRepeatArgs(list.size, n, "list")

                    val resList: MutableList<Rt_Value> = ArrayList(total)
                    if (n > 0 && list.isNotEmpty()) {
                        for (i in 0 until n) {
                            resList.addAll(list)
                        }
                    }

                    Rt_ListValue(a.type(), resList)
                }
            }

            function("reverse", "unit") {
                body { a ->
                    val list = a.asList()
                    list.reverse()
                    Rt_UnitValue
                }
            }

            function("reversed", "list<T>") {
                body { a ->
                    val list = a.asList()
                    val resList = list.toMutableList()
                    resList.reverse()
                    Rt_ListValue(a.type(), resList)
                }
            }
        }
    }

    private fun calcSub(type: R_Type, list: MutableList<Rt_Value>, start: Long, end: Long): Rt_Value {
        if (start < 0 || end < start || end > list.size) {
            throw Rt_Exception.common("fn:list.sub:args:${list.size}:$start:$end",
                "Invalid range: start = $start, end = $end, size = ${list.size}")
        }
        val r = list.subList(start.toInt(), end.toInt())
        return Rt_ListValue(type, r)
    }

    fun rtCheckRepeatArgs(s: Int, n: Long, type: String): Int {
        return if (n < 0) {
            throw Rt_Exception.common("fn:$type.repeat:n_negative:$n", "Negative count: $n")
        } else if (n > Integer.MAX_VALUE) {
            throw Rt_Exception.common("fn:$type.repeat:n_out_of_range:$n", "Count out of range: $n")
        } else {
            val total = LongMath.checkedMultiply(s.toLong(), n) // Must never fail, but using checkedMultiply() for extra safety
            if (total > Integer.MAX_VALUE) {
                throw Rt_Exception.common("fn:$type.repeat:too_big:$total", "Resulting size is too large: $s * $n = $total")
            }
            total.toInt()
        }
    }
}

private class R_CollectionKind_List(type: R_Type): R_CollectionKind(type) {
    override fun makeRtValue(col: Iterable<Rt_Value>): Rt_Value {
        val list = mutableListOf<Rt_Value>()
        list.addAll(col)
        return Rt_ListValue(type, list)
    }
}
