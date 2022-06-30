/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_CollectionSub
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_Simple
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.compiler.base.utils.C_MemberFuncTable
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.lib.type.C_Lib_Type_Collection as ColFns

private fun matcher(type: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_Simple(type)
private fun matcherColSub(elementType: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_CollectionSub(elementType)

object C_Lib_Type_List {
    fun getMemberFns(listType: R_ListType): C_MemberFuncTable {
        val elemType = listType.elementType

        val b = C_LibUtils.typeMemFuncBuilder(listType)
        ColFns.bindMemberFns(b, listType)

        b.add("get", elemType, listOf(R_IntegerType), ListFns.Get)

        b.add("indexOf", R_IntegerType, listOf(elemType), ListFns.IndexOf, depError("index_of"))
        b.add("index_of", R_IntegerType, listOf(elemType), ListFns.IndexOf)

        b.add("removeAt", elemType, listOf(R_IntegerType), ListFns.RemoveAt, depError("remove_at"))
        b.add("remove_at", elemType, listOf(R_IntegerType), ListFns.RemoveAt)

        b.add("sub", listType, listOf(R_IntegerType), ListFns.Sub_2)
        b.add("sub", listType, listOf(R_IntegerType, R_IntegerType), ListFns.Sub_3)

        b.add("_set", elemType, listOf(R_IntegerType, elemType), ListFns.Set)

        b.add("add", R_BooleanType, listOf(R_IntegerType, elemType), ListFns.Add)
        b.addEx("addAll", R_BooleanType, listOf(matcher(R_IntegerType), matcherColSub(elemType)), ListFns.AddAll, depError("add_all"))
        b.addEx("add_all", R_BooleanType, listOf(matcher(R_IntegerType), matcherColSub(elemType)), ListFns.AddAll)

        val comparator = elemType.comparator()
        if (comparator != null) {
            b.add("_sort", R_UnitType, listOf(), ListFns.Sort(comparator))
        }

        return b.build()
    }
}

private object ListFns {
    val Get = C_SysFunction.simple2(pure = true) { a, b ->
        val list = a.asList()
        val i = b.asInteger()
        if (i < 0 || i >= list.size) {
            throw Rt_Error("fn:list.get:index:${list.size}:$i", "List index out of bounds: $i (size ${list.size})")
        }
        list[i.toInt()]
    }

    val IndexOf = C_SysFunction.simple2(pure = true) { a, b ->
        val list = a.asList()
        Rt_IntValue(list.indexOf(b).toLong())
    }

    val Sub_2 = C_SysFunction.simple2(pure = true) { a, b ->
        val type = a.type()
        val list = a.asList()
        val start = b.asInteger()
        calcSub(type, list, start, list.size.toLong())
    }

    val Sub_3 = C_SysFunction.simple3(pure = true) { a, b, c ->
        val type = a.type()
        val list = a.asList()
        val start = b.asInteger()
        val end = c.asInteger()
        calcSub(type, list, start, end)
    }

    private fun calcSub(type: R_Type, list: MutableList<Rt_Value>, start: Long, end: Long): Rt_Value {
        if (start < 0 || end < start || end > list.size) {
            throw Rt_Error("fn:list.sub:args:${list.size}:$start:$end",
                "Out of range: start = $start, end = $end, size = ${list.size}")
        }
        val r = list.subList(start.toInt(), end.toInt())
        return Rt_ListValue(type, r)
    }

    val Add = C_SysFunction.simple3 { a, b, c ->
        val list = a.asList()
        val i = b.asInteger()

        if (i < 0 || i > list.size) {
            throw Rt_Error("fn:list.add:index:${list.size}:$i", "Index out of range: $i (size ${list.size})")
        }

        list.add(i.toInt(), c)
        Rt_BooleanValue(true)
    }

    val AddAll = C_SysFunction.simple3 { a, b, c ->
        val list = a.asList()
        val i = b.asInteger()
        val col = c.asCollection()

        if (i < 0 || i > list.size) {
            throw Rt_Error("fn:list.add_all:index:${list.size}:$i", "Index out of range: $i (size ${list.size})")
        }

        val r = list.addAll(i.toInt(), col)
        Rt_BooleanValue(r)
    }

    val RemoveAt = C_SysFunction.simple2 { a, b ->
        val list = a.asList()
        val i = b.asInteger()

        if (i < 0 || i >= list.size) {
            throw Rt_Error("fn:list.remove_at:index:${list.size}:$i", "Index out of range: $i (size ${list.size})")
        }

        val r = list.removeAt(i.toInt())
        r
    }

    val Set = C_SysFunction.simple3 { a, b, c ->
        val list = a.asList()
        val i = b.asInteger()

        if (i < 0 || i >= list.size) {
            throw Rt_Error("fn:list.set:index:${list.size}:$i", "Index out of range: $i (size ${list.size})")
        }

        val r = list.set(i.toInt(), c)
        r
    }

    fun Sort(comparator: Comparator<Rt_Value>) = C_SysFunction.simple1 { a ->
        val list = a.asList()
        list.sortWith(comparator)
        Rt_UnitValue
    }
}

object C_Lib_Type_VirtualList {
    fun getMemberFns(type: R_VirtualListType): C_MemberFuncTable {
        val elemType = type.innerType.elementType
        val b = C_LibUtils.typeMemFuncBuilder(type)
        C_Lib_Type_VirtualCollection.bindMemberFns(b, type.innerType)
        b.add("get", elemType, listOf(R_IntegerType), VirListFns.Get)
        return b.build()
    }
}

private object VirListFns {
    val Get = C_SysFunction.simple2(pure = true) { a, b ->
        val list = a.asVirtualList()
        val index = b.asInteger()
        val res = list.get(index)
        res
    }
}
