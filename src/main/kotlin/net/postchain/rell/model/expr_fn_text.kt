package net.postchain.rell.model

import net.postchain.rell.runtime.*
import java.util.*
import java.util.regex.Pattern

sealed class R_SysFn_Text: R_SysFunction_Generic<String>() {
    override fun extract(v: Rt_Value): String = v.asString()
}

object R_SysFn_Text_Empty: R_SysFn_Text() {
    override fun call(obj: String): Rt_Value = Rt_BooleanValue(obj.isEmpty())
}

object R_SysFn_Text_Size: R_SysFn_Text() {
    override fun call(obj: String): Rt_Value = Rt_IntValue(obj.length.toLong())
}

object R_SysFn_Text_UpperCase: R_SysFn_Text() {
    override fun call(obj: String): Rt_Value = Rt_TextValue(obj.toUpperCase())
}

object R_SysFn_Text_LowerCase: R_SysFn_Text() {
    override fun call(obj: String): Rt_Value = Rt_TextValue(obj.toLowerCase())
}

object R_SysFn_Text_CompareTo: R_SysFn_Text() {
    override fun call(obj: String, a: Rt_Value): Rt_Value = Rt_IntValue(obj.compareTo(a.asString()).toLong())
}

object R_SysFn_Text_StartsWith: R_SysFn_Text() {
    override fun call(obj: String, a: Rt_Value): Rt_Value = Rt_BooleanValue(obj.startsWith(a.asString()))
}

object R_SysFn_Text_EndsWith: R_SysFn_Text() {
    override fun call(obj: String, a: Rt_Value): Rt_Value = Rt_BooleanValue(obj.endsWith(a.asString()))
}

object R_SysFn_Text_Contains: R_SysFn_Text() {
    override fun call(obj: String, a: Rt_Value): Rt_Value = Rt_BooleanValue(obj.contains(a.asString()))
}

object R_SysFn_Text_IndexOf: R_SysFn_Text() {
    override fun call(obj: String, a: Rt_Value): Rt_Value = Rt_IntValue(obj.indexOf(a.asString()).toLong())

    override fun call(obj: String, a: Rt_Value, b: Rt_Value): Rt_Value {
        val start = b.asInteger()
        if (start < 0 || start >= obj.length) {
            throw Rt_Error("fn_text_indexOf_index:${obj.length}:$start", "Index out of bounds: $start (length ${obj.length})")
        }
        return Rt_IntValue(obj.indexOf(a.asString(), start.toInt()).toLong())
    }
}

object R_SysFn_Text_LastIndexOf: R_SysFn_Text() {
    override fun call(obj: String, a: Rt_Value): Rt_Value = Rt_IntValue(obj.lastIndexOf(a.asString()).toLong())

    override fun call(obj: String, a: Rt_Value, b: Rt_Value): Rt_Value {
        val start = b.asInteger()
        if (start < 0 || start >= obj.length) {
            throw Rt_Error("fn_text_lastIndexOf_index:${obj.length}:$start", "Index out of bounds: $start (length ${obj.length})")
        }
        return Rt_IntValue(obj.lastIndexOf(a.asString(), start.toInt()).toLong())
    }
}

object R_SysFn_Text_Replace: R_SysFn_Text() {
    override fun call(obj: String, a: Rt_Value, b: Rt_Value): Rt_Value = Rt_TextValue(obj.replace(a.asString(), b.asString()))
}

object R_SysFn_Text_Split: R_SysFn_Text() {
    private val type = R_ListType(R_TextType)

    override fun call(obj: String, a: Rt_Value): Rt_Value {
        val arr = obj.split(a.asString())
        val list = MutableList<Rt_Value>(arr.size) { Rt_TextValue(arr[it]) }
        return Rt_ListValue(type, list)
    }
}

object R_SysFn_Text_Trim: R_SysFn_Text() {
    override fun call(obj: String): Rt_Value = Rt_TextValue(obj.trim())
}

object R_SysFn_Text_Matches: R_SysFn_Text() {
    override fun call(obj: String, a: Rt_Value): Rt_Value = Rt_BooleanValue(Pattern.matches(a.asString(), obj))
}

object R_SysFn_Text_Encode: R_SysFn_Text() {
    override fun call(obj: String): Rt_Value = Rt_ByteArrayValue(obj.toByteArray())
}

object R_SysFn_Text_CharAt: R_SysFn_Text() {
    override fun call(obj: String, a: Rt_Value): Rt_Value {
        val index = a.asInteger()
        if (index < 0 || index >= obj.length) {
            throw Rt_Error("fn_text_charAt_index:${obj.length}:$index", "Index out of bounds: $index (length ${obj.length})")
        }
        val c = obj[index.toInt()]
        return Rt_IntValue(c.toLong())
    }
}

object R_SysFn_Text_Sub: R_SysFn_Text() {
    override fun call(obj: String, a: Rt_Value): Rt_Value {
        val start = a.asInteger()
        return call0(obj, start, obj.length.toLong())
    }

    override fun call(obj: String, a: Rt_Value, b: Rt_Value): Rt_Value {
        val start = a.asInteger()
        val end = b.asInteger()
        return call0(obj, start, end)
    }

    private fun call0(s: String, start: Long, end: Long): Rt_Value {
        val len = s.length
        if (start < 0 || start > len || end < start || end > len) {
            throw Rt_Error("fn_text_sub_range:$len:$start:$end",
                    "Invalid substring range: start = $start, end = $end (length $len)")
        }
        return Rt_TextValue(s.substring(start.toInt(), end.toInt()))
    }
}

object R_SysFn_Text_Format: R_SysFn_Text() {
    override fun call(obj: String, args: List<Rt_Value>): Rt_Value {
        val anys = args.map { it.asFormatArg() }.toTypedArray()
        val r = try {
            obj.format(Locale.US, *anys)
        } catch (e: IllegalFormatException) {
            obj
        }
        return Rt_TextValue(r)
    }
}
