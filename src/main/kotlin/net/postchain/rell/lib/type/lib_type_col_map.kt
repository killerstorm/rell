/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.ast.S_VirtualType
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_MapSub
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.compiler.base.utils.C_MemberFuncTable
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*

import net.postchain.rell.lib.type.C_Lib_Type_Any as AnyFns

private fun matcherMapSub(keyType: R_Type, valueType: R_Type): C_ArgTypeMatcher =
    C_ArgTypeMatcher_MapSub(R_MapKeyValueTypes(keyType, valueType))

object C_Lib_Type_Map {
    fun getMemberFns(mapType: R_MapType): C_MemberFuncTable {
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val keySetType = R_SetType(keyType)
        val valueListType = R_ListType(valueType)
        return C_LibUtils.typeMemFuncBuilder(mapType)
            .add("str", R_TextType, listOf(), AnyFns.ToText_NoDb)
            .add("to_text", R_TextType, listOf(), AnyFns.ToText_NoDb)
            .add("empty", R_BooleanType, listOf(), MapFns.Empty)
            .add("size", R_IntegerType, listOf(), MapFns.Size)
            .add("len", R_IntegerType, listOf(), MapFns.Size, depError("size"))
            .add("get", valueType, listOf(keyType), MapFns.Get)
            .add("contains", R_BooleanType, listOf(keyType), MapFns.Contains)
            .add("clear", R_UnitType, listOf(), MapFns.Clear)
            .add("put", R_UnitType, listOf(keyType, valueType), MapFns.Put)
            .addEx("putAll", R_UnitType, listOf(matcherMapSub(keyType, valueType)), MapFns.PutAll, depError("put_all"))
            .addEx("put_all", R_UnitType, listOf(matcherMapSub(keyType, valueType)), MapFns.PutAll)
            .add("remove", valueType, listOf(keyType), MapFns.Remove)
            .add("keys", keySetType, listOf(), MapFns.Keys(keySetType))
            .add("values", valueListType, listOf(), MapFns.Values(valueListType))
            .build()
    }
}

object C_Lib_Type_VirtualMap {
    fun getMemberFns(type: R_VirtualMapType): C_MemberFuncTable {
        val mapType = type.innerType
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val keySetType = R_SetType(keyType)
        val valueListType = R_ListType(S_VirtualType.virtualMemberType(valueType))
        return C_LibUtils.typeMemFuncBuilder(type)
            .add("str", R_TextType, listOf(), AnyFns.ToText_NoDb)
            .add("to_text", R_TextType, listOf(), AnyFns.ToText_NoDb)
            .add("empty", R_BooleanType, listOf(), MapFns.Empty)
            .add("size", R_IntegerType, listOf(), MapFns.Size)
            .add("get", valueType, listOf(keyType), MapFns.Get)
            .add("contains", R_BooleanType, listOf(keyType), MapFns.Contains)
            .add("keys", keySetType, listOf(), MapFns.Keys(keySetType))
            .add("values", valueListType, listOf(), MapFns.Values(valueListType))
            .add("to_full", mapType, listOf(), C_Lib_Type_Virtual.ToFull)
            .build()
    }
}

private object MapFns {
    val Empty = C_SysFunction.simple1(pure = true) { a ->
        val map = a.asMap()
        Rt_BooleanValue(map.isEmpty())
    }

    val Size = C_SysFunction.simple1(pure = true) { a ->
        val map = a.asMap()
        Rt_IntValue(map.size.toLong())
    }

    val Get = C_SysFunction.simple2(pure = true) { a, b ->
        val map = a.asMap()
        val r = map[b]
        if (r == null) {
            throw Rt_Error("fn:map.get:novalue:${b.strCode()}", "Key not in map: ${b.str()}")
        }
        r
    }

    val Contains = C_SysFunction.simple2(pure = true) { a, b ->
        val map = a.asMap()
        Rt_BooleanValue(b in map)
    }

    val Clear = C_SysFunction.simple1 { a ->
        val map = a.asMutableMap()
        map.clear()
        Rt_UnitValue
    }

    val Put = C_SysFunction.simple3 { a, b, c ->
        val map = a.asMutableMap()
        map.put(b, c)
        Rt_UnitValue
    }

    val PutAll = C_SysFunction.simple2 { a, b ->
        val map1 = a.asMutableMap()
        val map2 = b.asMap()
        map1.putAll(map2)
        Rt_UnitValue
    }

    val Remove = C_SysFunction.simple2 { a, b ->
        val map = a.asMutableMap()
        val v = map.remove(b)
        if (v == null) {
            throw Rt_Error("fn:map.remove:novalue:${b.strCode()}", "Key not in map: ${b.str()}")
        }
        v
    }

    fun Keys(type: R_Type) = C_SysFunction.simple1(pure = true) { a ->
        val map = a.asMap()
        val r = mutableSetOf<Rt_Value>()
        r.addAll(map.keys)
        Rt_SetValue(type, r)
    }

    fun Values(type: R_Type) = C_SysFunction.simple1(pure = true) { a ->
        val map = a.asMap()
        val r = mutableListOf<Rt_Value>()
        r.addAll(map.values)
        Rt_ListValue(type, r)
    }
}
