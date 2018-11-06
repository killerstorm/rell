package net.postchain.rell.model

import net.postchain.rell.runtime.*
import java.util.*
import java.util.regex.Pattern

sealed class RSysFunction_Text: RSysFunction_Generic<String>() {
    override fun extract(v: RtValue): String = v.asString()
}

object RSysFunction_Text_Empty: RSysFunction_Text() {
    override fun call(obj: String): RtValue = RtBooleanValue(obj.isEmpty())
}

object RSysFunction_Text_Size: RSysFunction_Text() {
    override fun call(obj: String): RtValue = RtIntValue(obj.length.toLong())
}

object RSysFunction_Text_UpperCase: RSysFunction_Text() {
    override fun call(obj: String): RtValue = RtTextValue(obj.toUpperCase())
}

object RSysFunction_Text_LowerCase: RSysFunction_Text() {
    override fun call(obj: String): RtValue = RtTextValue(obj.toLowerCase())
}

object RSysFunction_Text_CompareTo: RSysFunction_Text() {
    override fun call(obj: String, a: RtValue): RtValue = RtIntValue(obj.compareTo(a.asString()).toLong())
}

object RSysFunction_Text_StartsWith: RSysFunction_Text() {
    override fun call(obj: String, a: RtValue): RtValue = RtBooleanValue(obj.startsWith(a.asString()))
}

object RSysFunction_Text_EndsWith: RSysFunction_Text() {
    override fun call(obj: String, a: RtValue): RtValue = RtBooleanValue(obj.endsWith(a.asString()))
}

object RSysFunction_Text_Contains: RSysFunction_Text() {
    override fun call(obj: String, a: RtValue): RtValue = RtBooleanValue(obj.contains(a.asString()))
}

object RSysFunction_Text_IndexOf: RSysFunction_Text() {
    override fun call(obj: String, a: RtValue): RtValue = RtIntValue(obj.indexOf(a.asString()).toLong())

    override fun call(obj: String, a: RtValue, b: RtValue): RtValue {
        val start = b.asInteger()
        if (start < 0 || start >= obj.length) {
            throw RtError("fn_text_indexOf_index:${obj.length}:$start", "Index out of bounds: $start (length ${obj.length})")
        }
        return RtIntValue(obj.indexOf(a.asString(), start.toInt()).toLong())
    }
}

object RSysFunction_Text_LastIndexOf: RSysFunction_Text() {
    override fun call(obj: String, a: RtValue): RtValue = RtIntValue(obj.lastIndexOf(a.asString()).toLong())

    override fun call(obj: String, a: RtValue, b: RtValue): RtValue {
        val start = b.asInteger()
        if (start < 0 || start >= obj.length) {
            throw RtError("fn_text_lastIndexOf_index:${obj.length}:$start", "Index out of bounds: $start (length ${obj.length})")
        }
        return RtIntValue(obj.lastIndexOf(a.asString(), start.toInt()).toLong())
    }
}

object RSysFunction_Text_Replace: RSysFunction_Text() {
    override fun call(obj: String, a: RtValue, b: RtValue): RtValue = RtTextValue(obj.replace(a.asString(), b.asString()))
}

object RSysFunction_Text_Split: RSysFunction_Text() {
    private val type = RListType(RTextType)

    override fun call(obj: String, a: RtValue): RtValue {
        val arr = obj.split(a.asString())
        val list = MutableList<RtValue>(arr.size) { RtTextValue(arr[it]) }
        return RtListValue(type, list)
    }
}

object RSysFunction_Text_Trim: RSysFunction_Text() {
    override fun call(obj: String): RtValue = RtTextValue(obj.trim())
}

object RSysFunction_Text_Matches: RSysFunction_Text() {
    override fun call(obj: String, a: RtValue): RtValue = RtBooleanValue(Pattern.matches(a.asString(), obj))
}

object RSysFunction_Text_Encode: RSysFunction_Text() {
    override fun call(obj: String): RtValue = RtByteArrayValue(obj.toByteArray())
}

object RSysFunction_Text_CharAt: RSysFunction_Text() {
    override fun call(obj: String, a: RtValue): RtValue {
        val index = a.asInteger()
        if (index < 0 || index >= obj.length) {
            throw RtError("fn_text_charAt_index:${obj.length}:$index", "Index out of bounds: $index (length ${obj.length})")
        }
        val c = obj[index.toInt()]
        return RtIntValue(c.toLong())
    }
}

object RSysFunction_Text_Sub: RSysFunction_Text() {
    override fun call(obj: String, a: RtValue): RtValue {
        val start = a.asInteger()
        return call0(obj, start, obj.length.toLong())
    }

    override fun call(obj: String, a: RtValue, b: RtValue): RtValue {
        val start = a.asInteger()
        val end = b.asInteger()
        return call0(obj, start, end)
    }

    private fun call0(s: String, start: Long, end: Long): RtValue {
        val len = s.length
        if (start < 0 || start > len || end < start || end > len) {
            throw RtError("fn_text_sub_range:$len:$start:$end",
                    "Invalid substring range: start = $start, end = $end (length $len)")
        }
        return RtTextValue(s.substring(start.toInt(), end.toInt()))
    }
}

object RSysFunction_Text_Format: RSysFunction_Text() {
    override fun call(obj: String, args: List<RtValue>): RtValue {
        val anys = args.map { it.asFormatArg() }.toTypedArray()
        val r = try {
            obj.format(*anys)
        } catch (e: IllegalFormatException) {
            obj
        }
        return RtTextValue(r)
    }
}
