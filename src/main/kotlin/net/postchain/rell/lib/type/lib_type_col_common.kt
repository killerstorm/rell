/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_CollectionSub
import net.postchain.rell.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.lib.type.C_Lib_Type_Any as AnyFns

private fun matcherColSub(elementType: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_CollectionSub(elementType)

object C_Lib_Type_Collection {
    fun bindMemberFns(b: C_MemberFuncBuilder, listType: R_ListType) {
        val elemType = listType.elementType

        b.add("str", R_TextType, listOf(), AnyFns.ToText_NoDb)
        b.add("to_text", R_TextType, listOf(), AnyFns.ToText_NoDb)

        b.add("empty", R_BooleanType, listOf(), ColFns.Empty)
        b.add("size", R_IntegerType, listOf(), ColFns.Size)
        b.add("len", R_IntegerType, listOf(), ColFns.Size, depError("size"))

        b.add("contains", R_BooleanType, listOf(elemType), ColFns.Contains)
        b.addEx("containsAll", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.ContainsAll, depError("contains_all"))
        b.addEx("contains_all", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.ContainsAll)

        b.add("add", R_BooleanType, listOf(elemType), ColFns.Add)
        b.addEx("addAll", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.AddAll, depError("add_all"))
        b.addEx("add_all", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.AddAll)

        b.add("remove", R_BooleanType, listOf(elemType), ColFns.Remove)
        b.addEx("removeAll", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.RemoveAll, depError("remove_all"))
        b.addEx("remove_all", R_BooleanType, listOf(matcherColSub(elemType)), ColFns.RemoveAll)

        b.add("clear", R_UnitType, listOf(), ColFns.Clear)

        val comparator = elemType.comparator()
        if (comparator != null) {
            b.add("sorted", listType, listOf(), ColFns.Sorted(listType, comparator))
        }
    }
}

private object ColFns {
    val Empty = C_SysFunction.simple1(pure = true) { a ->
        val col = a.asCollection()
        Rt_BooleanValue(col.isEmpty())
    }

    val Size = C_SysFunction.simple1(pure = true) { a ->
        val col = a.asCollection()
        Rt_IntValue(col.size.toLong())
    }

    val Contains = C_SysFunction.simple2(pure = true) { a, b ->
        val col = a.asCollection()
        Rt_BooleanValue(col.contains(b))
    }

    val Clear = C_SysFunction.simple1 { a ->
        val col = a.asCollection()
        col.clear()
        Rt_UnitValue
    }

    val Remove = C_SysFunction.simple2 { a, b ->
        val col = a.asCollection()
        Rt_BooleanValue(col.remove(b))
    }

    val Add = C_SysFunction.simple2 { a, b ->
        val col = a.asCollection()
        Rt_BooleanValue(col.add(b))
    }

    val AddAll = C_SysFunction.simple2 { a, b ->
        val col = a.asCollection()
        Rt_BooleanValue(col.addAll(b.asCollection()))
    }

    val RemoveAll = C_SysFunction.simple2 { a, b ->
        val col1 = a.asCollection()
        val col2 = b.asCollection()
        Rt_BooleanValue(col1.removeAll(col2))
    }

    val ContainsAll = C_SysFunction.simple2(pure = true) { a, b ->
        val col1 = a.asCollection()
        val col2 = b.asCollection()
        Rt_BooleanValue(col1.containsAll(col2))
    }

    fun Sorted(type: R_Type, comparator: Comparator<Rt_Value>) = C_SysFunction.simple1(pure = true) { a ->
        val col = a.asCollection()
        val copy = ArrayList(col)
        copy.sortWith(comparator)
        Rt_ListValue(type, copy)
    }
}

object C_Lib_Type_VirtualCollection {
    fun bindMemberFns(b: C_MemberFuncBuilder, innerType: R_CollectionType) {
        b.add("str", R_TextType, listOf(), AnyFns.ToText_NoDb)
        b.add("to_text", R_TextType, listOf(), AnyFns.ToText_NoDb)
        b.add("empty", R_BooleanType, listOf(), VirColFns.Empty)
        b.add("size", R_IntegerType, listOf(), VirColFns.Size)
        b.add("to_full", innerType, listOf(), C_Lib_Type_Virtual.ToFull)
    }
}

private object VirColFns {
    val Empty = C_SysFunction.simple1(pure = true) { a ->
        val col = a.asVirtualCollection()
        Rt_BooleanValue(col.size() == 0)
    }

    val Size = C_SysFunction.simple1(pure = true) { a ->
        val col = a.asVirtualCollection()
        Rt_IntValue(col.size().toLong())
    }
}
