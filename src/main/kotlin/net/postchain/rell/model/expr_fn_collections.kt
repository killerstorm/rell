package net.postchain.rell.model

import net.postchain.rell.runtime.*


sealed class RSysFunction_Collection: RSysFunction_Generic<MutableCollection<RtValue>>() {
    override fun extract(v: RtValue): MutableCollection<RtValue> = v.asCollection()
}

object RSysFunction_Collection_Empty: RSysFunction_Collection() {
    override fun call(obj: MutableCollection<RtValue>): RtValue = RtBooleanValue(obj.isEmpty())
}

object RSysFunction_Collection_Size: RSysFunction_Collection() {
    override fun call(obj: MutableCollection<RtValue>): RtValue = RtIntValue(obj.size.toLong())
}

object RSysFunction_Collection_Contains: RSysFunction_Collection() {
    override fun call(obj: MutableCollection<RtValue>, a: RtValue): RtValue = RtBooleanValue(obj.contains(a))
}

object RSysFunction_Collection_Clear: RSysFunction_Collection() {
    override fun call(obj: MutableCollection<RtValue>): RtValue {
        obj.clear()
        return RtUnitValue
    }
}

object RSysFunction_Collection_Remove: RSysFunction_Collection() {
    override fun call(obj: MutableCollection<RtValue>, a: RtValue): RtValue = RtBooleanValue(obj.remove(a))
}

object RSysFunction_Collection_Add: RSysFunction_Collection() {
    override fun call(obj: MutableCollection<RtValue>, a: RtValue): RtValue = RtBooleanValue(obj.add(a))
}

object RSysFunction_Collection_AddAll: RSysFunction_Collection() {
    override fun call(obj: MutableCollection<RtValue>, a: RtValue): RtValue = RtBooleanValue(obj.addAll(a.asCollection()))
}

object RSysFunction_Collection_RemoveAll: RSysFunction_Collection() {
    override fun call(obj: MutableCollection<RtValue>, a: RtValue): RtValue = RtBooleanValue(obj.removeAll(a.asCollection()))
}

object RSysFunction_Collection_ContainsAll: RSysFunction_Collection() {
    override fun call(obj: MutableCollection<RtValue>, a: RtValue): RtValue = RtBooleanValue(obj.containsAll(a.asCollection()))
}

sealed class RSysFunction_List: RSysFunction_Generic<MutableList<RtValue>>() {
    override fun extract(v: RtValue): MutableList<RtValue> = v.asList()
}

object RSysFunction_List_Get: RSysFunction_List() {
    override fun call(obj: MutableList<RtValue>, a: RtValue): RtValue {
        val i = a.asInteger()
        if (i < 0 || i >= obj.size) {
            throw RtError("fn_list_get_index:${obj.size}:$i", "Index out of bounds: $i (size ${obj.size})")
        }
        return obj[i.toInt()]
    }
}

object RSysFunction_List_IndexOf: RSysFunction_List() {
    override fun call(obj: MutableList<RtValue>, a: RtValue): RtValue = RtIntValue(obj.indexOf(a).toLong())
}

object RSysFunction_List_Sub: RSysFunction_List() {
    override fun call(type: RType, obj: MutableList<RtValue>, a: RtValue): RtValue {
        return call(type, obj, a, RtIntValue(obj.size.toLong()))
    }

    override fun call(type: RType, obj: MutableList<RtValue>, a: RtValue, b: RtValue): RtValue {
        val start = a.asInteger()
        val end = b.asInteger()
        if (start < 0 || end < start || end > obj.size) {
            throw RtError("fn_list_sub_args:${obj.size}:$start:$end",
                    "Out of range: start = $start, end = $end, size = ${obj.size}")
        }

        val r = obj.subList(start.toInt(), end.toInt())
        return RtListValue(type, r)
    }
}

object RSysFunction_List_Add: RSysFunction_List() {
    override fun call(obj: MutableList<RtValue>, a: RtValue, b: RtValue): RtValue {
        val i = a.asInteger()
        if (i < 0 || i > obj.size) {
            throw RtError("fn_list_add_index:${obj.size}:$i", "Index out of range: $i (size ${obj.size})")
        }

        obj.add(i.toInt(), b)
        return RtBooleanValue(true)
    }
}

object RSysFunction_List_AddAll: RSysFunction_List() {
    override fun call(obj: MutableList<RtValue>, a: RtValue, b: RtValue): RtValue {
        val i = a.asInteger()
        if (i < 0 || i > obj.size) {
            throw RtError("fn_list_addAll_index:${obj.size}:$i", "Index out of range: $i (size ${obj.size})")
        }

        val r = obj.addAll(i.toInt(), b.asCollection())
        return RtBooleanValue(r)
    }
}

object RSysFunction_List_RemoveAt: RSysFunction_List() {
    override fun call(obj: MutableList<RtValue>, a: RtValue): RtValue {
        val i = a.asInteger()
        if (i < 0 || i >= obj.size) {
            throw RtError("fn_list_removeAt_index:${obj.size}:$i", "Index out of range: $i (size ${obj.size})")
        }

        val r = obj.removeAt(i.toInt())
        return r
    }
}

object RSysFunction_List_Set: RSysFunction_List() {
    override fun call(obj: MutableList<RtValue>, a: RtValue, b: RtValue): RtValue {
        val i = a.asInteger()
        if (i < 0 || i >= obj.size) {
            throw RtError("fn_list_set_index:${obj.size}:$i", "Index out of range: $i (size ${obj.size})")
        }

        val r = obj.set(i.toInt(), b)
        return r
    }
}

sealed class RSysFunction_Map: RSysFunction_Generic<MutableMap<RtValue, RtValue>>() {
    override fun extract(v: RtValue): MutableMap<RtValue, RtValue> = v.asMap()
}

object RSysFunction_Map_Empty: RSysFunction_Map() {
    override fun call(obj: MutableMap<RtValue, RtValue>): RtValue = RtBooleanValue(obj.isEmpty())
}

object RSysFunction_Map_Size: RSysFunction_Map() {
    override fun call(obj: MutableMap<RtValue, RtValue>): RtValue = RtIntValue(obj.size.toLong())
}

object RSysFunction_Map_Get: RSysFunction_Map() {
    override fun call(obj: MutableMap<RtValue, RtValue>, a: RtValue): RtValue {
        val r = obj[a]
        if (r == null) {
            throw RtError("fn_map_get_novalue:${a.toStrictString()}", "Key not in map: $a")
        }
        return r
    }
}

object RSysFunction_Map_Contains: RSysFunction_Map() {
    override fun call(obj: MutableMap<RtValue, RtValue>, a: RtValue): RtValue = RtBooleanValue(a in obj)
}

object RSysFunction_Map_Clear: RSysFunction_Map() {
    override fun call(obj: MutableMap<RtValue, RtValue>): RtValue {
        obj.clear()
        return RtUnitValue
    }
}

object RSysFunction_Map_Put: RSysFunction_Map() {
    override fun call(obj: MutableMap<RtValue, RtValue>, a: RtValue, b: RtValue): RtValue {
        obj.put(a, b)
        return RtUnitValue
    }
}

object RSysFunction_Map_PutAll: RSysFunction_Map() {
    override fun call(obj: MutableMap<RtValue, RtValue>, a: RtValue): RtValue {
        obj.putAll(a.asMap())
        return RtUnitValue
    }
}

object RSysFunction_Map_Remove: RSysFunction_Map() {
    override fun call(obj: MutableMap<RtValue, RtValue>, a: RtValue): RtValue {
        val v = obj.remove(a)
        if (v == null) {
            throw RtError("fn_map_remove_novalue:${a.toStrictString()}", "Key not in map: $a")
        }
        return v
    }
}

class RSysFunction_Map_Keys(val type: RType): RSysFunction_Map() {
    override fun call(obj: MutableMap<RtValue, RtValue>): RtValue {
        val r = mutableSetOf<RtValue>()
        r.addAll(obj.keys)
        return RtSetValue(type, r)
    }
}

class RSysFunction_Map_Values(val type: RType): RSysFunction_Map() {
    override fun call(obj: MutableMap<RtValue, RtValue>): RtValue {
        val r = mutableListOf<RtValue>()
        r.addAll(obj.values)
        return RtListValue(type, r)
    }
}
