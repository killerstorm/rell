/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import com.google.common.math.LongMath
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_PosValue
import net.postchain.rell.compiler.base.core.C_NamespaceContext
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.def.C_GenericType
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.expr.C_TypeValueMember
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_CollectionSub
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_Simple
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.compiler.base.utils.C_LibUtils.depWarn
import net.postchain.rell.compiler.base.utils.C_MemberFuncTable
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.R_CollectionKind_List
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.lib.type.C_Lib_Type_Collection as ColFns

private fun matcher(type: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_Simple(type)
private fun matcherColSub(elementType: R_Type): C_ArgTypeMatcher = C_ArgTypeMatcher_CollectionSub(elementType)

object C_Lib_Type_List {
    const val TYPE_NAME = "list"
    val DEF_NAME = C_LibUtils.defName(TYPE_NAME)

    fun getConstructorFn(listType: R_ListType): C_GlobalFunction {
        return C_CollectionConstructorFunction(C_CollectionKindAdapter_List, listType.elementType)
    }

    fun getValueMembers(listType: R_ListType): List<C_TypeValueMember> {
        val fns = getMemberFns(listType)
        return C_LibUtils.makeValueMembers(listType, fns)
    }

    private fun getMemberFns(listType: R_ListType): C_MemberFuncTable {
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

        b.add("set", elemType, listOf(R_IntegerType, elemType), ListFns.Set)
        b.add("_set", elemType, listOf(R_IntegerType, elemType), ListFns.Set, depWarn("set"))

        b.add("add", R_BooleanType, listOf(R_IntegerType, elemType), ListFns.Add)
        b.addEx("addAll", R_BooleanType, listOf(matcher(R_IntegerType), matcherColSub(elemType)), ListFns.AddAll, depError("add_all"))
        b.addEx("add_all", R_BooleanType, listOf(matcher(R_IntegerType), matcherColSub(elemType)), ListFns.AddAll)

        val comparator = elemType.comparator()
        if (comparator != null) {
            val fn = ListFns.Sort(comparator)
            b.add("sort", R_UnitType, listOf(), fn)
            b.add("_sort", R_UnitType, listOf(), fn, depWarn("sort"))
        }

        b.add("repeat", listType, listOf(R_IntegerType), ListFns.Repeat)
        b.add("reverse", R_UnitType, listOf(), ListFns.Reverse)
        b.add("reversed", listType, listOf(), ListFns.Reversed)

        return b.build()
    }

    fun bind(b: C_SysNsProtoBuilder) {
        b.addType(TYPE_NAME, C_GenericType_List)
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

private object C_GenericType_List: C_GenericType(C_Lib_Type_List.TYPE_NAME, C_Lib_Type_List.DEF_NAME, 1) {
    override val rawConstructorFn: C_GlobalFunction = C_CollectionConstructorFunction(C_CollectionKindAdapter_List, null)

    override fun compileType0(ctx: C_NamespaceContext, pos: S_Pos, args: List<S_PosValue<R_Type>>): R_Type {
        checkEquals(args.size, 1)
        val elemEntry = args[0]
        C_CollectionKindAdapter_List.checkElementType(ctx, pos, elemEntry.pos, elemEntry.value)
        return R_ListType(elemEntry.value)
    }
}

private object C_CollectionKindAdapter_List: C_CollectionKindAdapter(C_Lib_Type_List.TYPE_NAME) {
    override fun elementTypeFromTypeHint(typeHint: C_TypeHint) = typeHint.getListElementType()
    override fun makeKind(rElementType: R_Type) = R_CollectionKind_List(R_ListType(rElementType))
    override fun checkElementType0(ctx: C_NamespaceContext, pos: S_Pos, elemTypePos: S_Pos, rElemType: R_Type) {}
}

private object ListFns {
    val Get = C_SysFunction.simple2(pure = true) { a, b ->
        val list = a.asList()
        val i = b.asInteger()
        if (i < 0 || i >= list.size) {
            throw Rt_Exception.common("fn:list.get:index:${list.size}:$i", "List index out of bounds: $i (size ${list.size})")
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
            throw Rt_Exception.common("fn:list.sub:args:${list.size}:$start:$end",
                "Invalid range: start = $start, end = $end, size = ${list.size}")
        }
        val r = list.subList(start.toInt(), end.toInt())
        return Rt_ListValue(type, r)
    }

    val Add = C_SysFunction.simple3 { a, b, c ->
        val list = a.asList()
        val i = b.asInteger()

        if (i < 0 || i > list.size) {
            throw Rt_Exception.common("fn:list.add:index:${list.size}:$i", "Index out of range: $i (size ${list.size})")
        }

        list.add(i.toInt(), c)
        Rt_BooleanValue(true)
    }

    val AddAll = C_SysFunction.simple3 { a, b, c ->
        val list = a.asList()
        val i = b.asInteger()
        val col = c.asCollection()

        if (i < 0 || i > list.size) {
            throw Rt_Exception.common("fn:list.add_all:index:${list.size}:$i", "Index out of range: $i (size ${list.size})")
        }

        val r = list.addAll(i.toInt(), col)
        Rt_BooleanValue(r)
    }

    val RemoveAt = C_SysFunction.simple2 { a, b ->
        val list = a.asList()
        val i = b.asInteger()

        if (i < 0 || i >= list.size) {
            throw Rt_Exception.common("fn:list.remove_at:index:${list.size}:$i", "Index out of range: $i (size ${list.size})")
        }

        val r = list.removeAt(i.toInt())
        r
    }

    val Set = C_SysFunction.simple3 { a, b, c ->
        val list = a.asList()
        val i = b.asInteger()

        if (i < 0 || i >= list.size) {
            throw Rt_Exception.common("fn:list.set:index:${list.size}:$i", "Index out of range: $i (size ${list.size})")
        }

        val r = list.set(i.toInt(), c)
        r
    }

    val Repeat = C_SysFunction.simple2 { a, b ->
        val list = a.asList()
        val listType = a.type()
        val n = b.asInteger()

        val total = C_Lib_Type_List.rtCheckRepeatArgs(list.size, n, "list")

        val resList: MutableList<Rt_Value> = ArrayList(total)
        if (n > 0 && list.isNotEmpty()) {
            for (i in 0 until n) {
                resList.addAll(list)
            }
        }

        Rt_ListValue(listType, resList)
    }

    val Reverse = C_SysFunction.simple1 { a ->
        val list = a.asList()
        list.reverse()
        Rt_UnitValue
    }

    val Reversed = C_SysFunction.simple1 { a ->
        val list = a.asList()
        val resList = list.toMutableList()
        resList.reverse()
        Rt_ListValue(a.type(), resList)
    }

    fun Sort(comparator: Comparator<Rt_Value>) = C_SysFunction.simple1 { a ->
        val list = a.asList()
        list.sortWith(comparator)
        Rt_UnitValue
    }
}

object C_Lib_Type_VirtualList {
    fun getValueMembers(type: R_VirtualListType): List<C_TypeValueMember> {
        val elemType = type.innerType.elementType
        val b = C_LibUtils.typeMemFuncBuilder(type)
        C_Lib_Type_VirtualCollection.bindMemberFns(b, type.innerType)
        b.add("get", elemType, listOf(R_IntegerType), VirListFns.Get)
        val fns = b.build()
        return C_LibUtils.makeValueMembers(type, fns)
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
