/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model.lib

import net.postchain.rell.model.R_SysFunction_1
import net.postchain.rell.model.R_SysFunction_2
import net.postchain.rell.model.R_SysFunction_Generic
import net.postchain.rell.model.R_Type
import net.postchain.rell.runtime.*


sealed class R_SysFn_Collection: R_SysFunction_Generic<MutableCollection<Rt_Value>>() {
    override fun extract(v: Rt_Value): MutableCollection<Rt_Value> = v.asCollection()
}

object R_SysFn_Collection_Empty: R_SysFn_Collection() {
    override fun call(obj: MutableCollection<Rt_Value>): Rt_Value = Rt_BooleanValue(obj.isEmpty())
}

object R_SysFn_Collection_Size: R_SysFn_Collection() {
    override fun call(obj: MutableCollection<Rt_Value>): Rt_Value = Rt_IntValue(obj.size.toLong())
}

object R_SysFn_Collection_Contains: R_SysFn_Collection() {
    override fun call(obj: MutableCollection<Rt_Value>, a: Rt_Value): Rt_Value = Rt_BooleanValue(obj.contains(a))
}

object R_SysFn_Collection_Clear: R_SysFn_Collection() {
    override fun call(obj: MutableCollection<Rt_Value>): Rt_Value {
        obj.clear()
        return Rt_UnitValue
    }
}

object R_SysFn_Collection_Remove: R_SysFn_Collection() {
    override fun call(obj: MutableCollection<Rt_Value>, a: Rt_Value): Rt_Value = Rt_BooleanValue(obj.remove(a))
}

object R_SysFn_Collection_Add: R_SysFn_Collection() {
    override fun call(obj: MutableCollection<Rt_Value>, a: Rt_Value): Rt_Value = Rt_BooleanValue(obj.add(a))
}

object R_SysFn_Collection_AddAll: R_SysFn_Collection() {
    override fun call(obj: MutableCollection<Rt_Value>, a: Rt_Value): Rt_Value = Rt_BooleanValue(obj.addAll(a.asCollection()))
}

object R_SysFn_Collection_RemoveAll: R_SysFn_Collection() {
    override fun call(obj: MutableCollection<Rt_Value>, a: Rt_Value): Rt_Value = Rt_BooleanValue(obj.removeAll(a.asCollection()))
}

object R_SysFn_Collection_ContainsAll: R_SysFn_Collection() {
    override fun call(obj: MutableCollection<Rt_Value>, a: Rt_Value): Rt_Value = Rt_BooleanValue(obj.containsAll(a.asCollection()))
}

sealed class R_SysFn_List: R_SysFunction_Generic<MutableList<Rt_Value>>() {
    override fun extract(v: Rt_Value): MutableList<Rt_Value> = v.asList()
}

object R_SysFn_List_Get: R_SysFn_List() {
    override fun call(obj: MutableList<Rt_Value>, a: Rt_Value): Rt_Value {
        val i = a.asInteger()
        if (i < 0 || i >= obj.size) {
            throw Rt_Error("fn:list.get:index:${obj.size}:$i", "List index out of bounds: $i (size ${obj.size})")
        }
        return obj[i.toInt()]
    }
}

object R_SysFn_List_IndexOf: R_SysFn_List() {
    override fun call(obj: MutableList<Rt_Value>, a: Rt_Value): Rt_Value = Rt_IntValue(obj.indexOf(a).toLong())
}

object R_SysFn_List_Sub: R_SysFn_List() {
    override fun call(type: R_Type, obj: MutableList<Rt_Value>, a: Rt_Value): Rt_Value {
        return call(type, obj, a, Rt_IntValue(obj.size.toLong()))
    }

    override fun call(type: R_Type, obj: MutableList<Rt_Value>, a: Rt_Value, b: Rt_Value): Rt_Value {
        val start = a.asInteger()
        val end = b.asInteger()
        if (start < 0 || end < start || end > obj.size) {
            throw Rt_Error("fn:list.sub:args:${obj.size}:$start:$end",
                    "Out of range: start = $start, end = $end, size = ${obj.size}")
        }

        val r = obj.subList(start.toInt(), end.toInt())
        return Rt_ListValue(type, r)
    }
}

object R_SysFn_List_Add: R_SysFn_List() {
    override fun call(obj: MutableList<Rt_Value>, a: Rt_Value, b: Rt_Value): Rt_Value {
        val i = a.asInteger()
        if (i < 0 || i > obj.size) {
            throw Rt_Error("fn:list.add:index:${obj.size}:$i", "Index out of range: $i (size ${obj.size})")
        }

        obj.add(i.toInt(), b)
        return Rt_BooleanValue(true)
    }
}

object R_SysFn_List_AddAll: R_SysFn_List() {
    override fun call(obj: MutableList<Rt_Value>, a: Rt_Value, b: Rt_Value): Rt_Value {
        val i = a.asInteger()
        if (i < 0 || i > obj.size) {
            throw Rt_Error("fn:list.add_all:index:${obj.size}:$i", "Index out of range: $i (size ${obj.size})")
        }

        val r = obj.addAll(i.toInt(), b.asCollection())
        return Rt_BooleanValue(r)
    }
}

object R_SysFn_List_RemoveAt: R_SysFn_List() {
    override fun call(obj: MutableList<Rt_Value>, a: Rt_Value): Rt_Value {
        val i = a.asInteger()
        if (i < 0 || i >= obj.size) {
            throw Rt_Error("fn:list.remove_at:index:${obj.size}:$i", "Index out of range: $i (size ${obj.size})")
        }

        val r = obj.removeAt(i.toInt())
        return r
    }
}

object R_SysFn_List_Set: R_SysFn_List() {
    override fun call(obj: MutableList<Rt_Value>, a: Rt_Value, b: Rt_Value): Rt_Value {
        val i = a.asInteger()
        if (i < 0 || i >= obj.size) {
            throw Rt_Error("fn:list.set:index:${obj.size}:$i", "Index out of range: $i (size ${obj.size})")
        }

        val r = obj.set(i.toInt(), b)
        return r
    }
}

class R_SysFn_List_Sort(private val comparator: Comparator<Rt_Value>): R_SysFn_List() {
    override fun call(obj: MutableList<Rt_Value>): Rt_Value {
        obj.sortWith(comparator)
        return Rt_UnitValue
    }
}

object R_SysFn_VirtualCollection_Empty: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value = Rt_BooleanValue(arg.asVirtualCollection().size() == 0)
}

object R_SysFn_VirtualCollection_Size: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value = Rt_IntValue(arg.asVirtualCollection().size().toLong())
}

object R_SysFn_VirtualList_Get: R_SysFunction_2() {
    override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
        val list = arg1.asVirtualList()
        val index = arg2.asInteger()
        val res = list.get(index)
        return res
    }
}

class R_SysFn_Collection_Sorted(private val type: R_Type, private val comparator: Comparator<Rt_Value>): R_SysFn_Collection() {
    override fun call(obj: MutableCollection<Rt_Value>): Rt_Value {
        val copy = ArrayList(obj)
        copy.sortWith(comparator)
        return Rt_ListValue(type, copy)
    }
}

sealed class R_SysFn_Map: R_SysFunction_Generic<Map<Rt_Value, Rt_Value>>() {
    override fun extract(v: Rt_Value) = v.asMap()
}

sealed class R_SysFn_MutableMap: R_SysFunction_Generic<MutableMap<Rt_Value, Rt_Value>>() {
    override fun extract(v: Rt_Value) = v.asMutableMap()
}

object R_SysFn_Map_Empty: R_SysFn_Map() {
    override fun call(obj: Map<Rt_Value, Rt_Value>): Rt_Value = Rt_BooleanValue(obj.isEmpty())
}

object R_SysFn_Map_Size: R_SysFn_Map() {
    override fun call(obj: Map<Rt_Value, Rt_Value>): Rt_Value = Rt_IntValue(obj.size.toLong())
}

object R_SysFn_Map_Get: R_SysFn_Map() {
    override fun call(obj: Map<Rt_Value, Rt_Value>, a: Rt_Value): Rt_Value {
        val r = obj[a]
        if (r == null) {
            throw Rt_Error("fn:map.get:novalue:${a.toStrictString()}", "Key not in map: $a")
        }
        return r
    }
}

object R_SysFn_Map_Contains: R_SysFn_Map() {
    override fun call(obj: Map<Rt_Value, Rt_Value>, a: Rt_Value): Rt_Value = Rt_BooleanValue(a in obj)
}

object R_SysFn_MutableMap_Clear: R_SysFn_MutableMap() {
    override fun call(obj: MutableMap<Rt_Value, Rt_Value>): Rt_Value {
        obj.clear()
        return Rt_UnitValue
    }
}

object R_SysFn_MutableMap_Put: R_SysFn_MutableMap() {
    override fun call(obj: MutableMap<Rt_Value, Rt_Value>, a: Rt_Value, b: Rt_Value): Rt_Value {
        obj.put(a, b)
        return Rt_UnitValue
    }
}

object R_SysFn_MutableMap_PutAll: R_SysFn_MutableMap() {
    override fun call(obj: MutableMap<Rt_Value, Rt_Value>, a: Rt_Value): Rt_Value {
        obj.putAll(a.asMap())
        return Rt_UnitValue
    }
}

object R_SysFn_MutableMap_Remove: R_SysFn_MutableMap() {
    override fun call(obj: MutableMap<Rt_Value, Rt_Value>, a: Rt_Value): Rt_Value {
        val v = obj.remove(a)
        if (v == null) {
            throw Rt_Error("fn:map.remove:novalue:${a.toStrictString()}", "Key not in map: $a")
        }
        return v
    }
}

class R_SysFn_Map_Keys(val type: R_Type): R_SysFn_Map() {
    override fun call(obj: Map<Rt_Value, Rt_Value>): Rt_Value {
        val r = mutableSetOf<Rt_Value>()
        r.addAll(obj.keys)
        return Rt_SetValue(type, r)
    }
}

class R_SysFn_Map_Values(val type: R_Type): R_SysFn_Map() {
    override fun call(obj: Map<Rt_Value, Rt_Value>): Rt_Value {
        val r = mutableListOf<Rt_Value>()
        r.addAll(obj.values)
        return Rt_ListValue(type, r)
    }
}
