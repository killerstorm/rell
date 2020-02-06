package net.postchain.rell.repl

import net.postchain.gtv.Gtv
import net.postchain.rell.PostchainUtils
import net.postchain.rell.compiler.C_Message
import net.postchain.rell.model.R_CollectionType
import net.postchain.rell.model.R_MapType
import net.postchain.rell.model.R_StackPos
import net.postchain.rell.runtime.Rt_BaseError
import net.postchain.rell.runtime.Rt_UnitValue
import net.postchain.rell.runtime.Rt_Value

interface ReplOutputChannel {
    fun printCompilerError(code: String, msg: String)
    fun printCompilerMessage(message: C_Message)
    fun printRuntimeError(e: Rt_BaseError, stack: List<R_StackPos>?)
    fun printPlatformRuntimeError(e: Throwable)
    fun setValueFormat(format: ReplValueFormat)
    fun printValue(value: Rt_Value)
    fun printControl(code: String, msg: String)
}

enum class ReplValueFormat {
    DEFAULT,
    STRICT,
    ONE_ITEM_PER_LINE,
    GTV_JSON,
    GTV_XML
}

object ReplValueFormatter {
    fun format(v: Rt_Value, format: ReplValueFormat): String? {
        val res = when (format) {
            ReplValueFormat.DEFAULT -> formatDefault(v)
            ReplValueFormat.STRICT -> formatStrict(v)
            ReplValueFormat.ONE_ITEM_PER_LINE -> formatOneItemPerLine(v)
            ReplValueFormat.GTV_JSON -> formatGtvJson(v)
            ReplValueFormat.GTV_XML -> formatGtvXml(v)
        }
        return res
    }

    private fun formatDefault(v: Rt_Value): String? {
        return if (v == Rt_UnitValue) null else v.toString()
    }

    private fun formatStrict(v: Rt_Value): String? {
        return v.toStrictString()
    }

    private fun formatOneItemPerLine(v: Rt_Value): String? {
        if (v == Rt_UnitValue) return null
        val type = v.type()
        return when (type) {
            is R_CollectionType -> v.asCollection().joinToString("\n")
            is R_MapType -> v.asMap().entries.joinToString("\n")
            else -> v.toString()
        }
    }

    private fun formatGtvJson(v: Rt_Value): String? {
        return formatGtv(v) {
            PostchainUtils.gtvToJsonPretty(it)
        }
    }

    private fun formatGtvXml(v: Rt_Value): String? {
        return formatGtv(v) {
            val xml = PostchainUtils.gtvToXml(it)
            val res = xml.removePrefix("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            res
        }
    }

    private fun formatGtv(v: Rt_Value, gtvToString: (Gtv) -> String): String? {
        if (v == Rt_UnitValue) return null

        val type = v.type()
        val flags = type.completeFlags()
        if (!flags.gtv.toGtv) {
            return "Type $type cannot be converted to Gtv. Switch to a different output format."
        }

        val gtv = type.rtToGtv(v, true)
        val res = gtvToString(gtv).trim()
        return res
    }
}
