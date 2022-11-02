/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.fn.C_FormalParamsFuncCaseMatch
import net.postchain.rell.compiler.base.fn.C_MemberFuncCaseMatch
import net.postchain.rell.compiler.base.fn.C_MemberSpecialFuncCase
import net.postchain.rell.compiler.base.fn.C_SysMemberFormalParamsFuncBody
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.Db_SysFunction
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.sql.SqlConstants
import java.nio.ByteBuffer
import java.util.*
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object C_Lib_Type_Text: C_Lib_Type("text", R_TextType) {
    val DB_SUBSCRIPT: Db_SysFunction = TextFns.Subscript_Db

    override fun bindStaticFunctions(b: C_GlobalFuncBuilder) {
        b.add("from_bytes", R_TextType, listOf(R_ByteArrayType), TextFns.FromBytes_1)
        b.add("from_bytes", R_TextType, listOf(R_ByteArrayType, R_BooleanType), TextFns.FromBytes_2)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        b.add("empty", R_BooleanType, listOf(), TextFns.Empty)
        b.add("size", R_IntegerType, listOf(), TextFns.Size)
        b.add("len", R_IntegerType, listOf(), TextFns.Size, depError("size"))
        b.add("upperCase", R_TextType, listOf(), TextFns.UpperCase, depError("upper_case"))
        b.add("upper_case", R_TextType, listOf(), TextFns.UpperCase)
        b.add("lowerCase", R_TextType, listOf(), TextFns.LowerCase, depError("lower_case"))
        b.add("lower_case", R_TextType, listOf(), TextFns.LowerCase)
        b.add("compareTo", R_IntegerType, listOf(R_TextType), TextFns.CompareTo, depError("compare_to"))
        b.add("compare_to", R_IntegerType, listOf(R_TextType), TextFns.CompareTo)
        b.add("contains", R_BooleanType, listOf(R_TextType), TextFns.Contains)
        b.add("startsWith", R_BooleanType, listOf(R_TextType), TextFns.StartsWith, depError("starts_with"))
        b.add("starts_with", R_BooleanType, listOf(R_TextType), TextFns.StartsWith)
        b.add("endsWith", R_BooleanType, listOf(R_TextType), TextFns.EndsWith, depError("ends_with"))
        b.add("ends_with", R_BooleanType, listOf(R_TextType), TextFns.EndsWith)
        b.add("format", C_SysFn_Text_Format)
        b.add("replace", R_TextType, listOf(R_TextType, R_TextType), TextFns.Replace)
        b.add("split", R_ListType(R_TextType), listOf(R_TextType), TextFns.Split)
        b.add("trim", R_TextType, listOf(), TextFns.Trim/*, TextFns.Trim*/)
        b.add("like", R_BooleanType, listOf(R_TextType), TextFns.Like)
        b.add("matches", R_BooleanType, listOf(R_TextType), TextFns.Matches)
        b.add("encode", R_ByteArrayType, listOf(), TextFns.ToBytes, depError("to_bytes"))
        b.add("charAt", R_IntegerType, listOf(R_IntegerType), TextFns.CharAt, depError("char_at"))
        b.add("char_at", R_IntegerType, listOf(R_IntegerType), TextFns.CharAt)
        b.add("indexOf", R_IntegerType, listOf(R_TextType), TextFns.IndexOf_2, depError("index_of"))
        b.add("index_of", R_IntegerType, listOf(R_TextType), TextFns.IndexOf_2)
        b.add("indexOf", R_IntegerType, listOf(R_TextType, R_IntegerType), TextFns.IndexOf_3, depError("index_of"))
        b.add("index_of", R_IntegerType, listOf(R_TextType, R_IntegerType), TextFns.IndexOf_3)
        b.add("lastIndexOf", R_IntegerType, listOf(R_TextType), TextFns.LastIndexOf_2, depError("last_index_of"))
        b.add("last_index_of", R_IntegerType, listOf(R_TextType), TextFns.LastIndexOf_2)
        b.add("lastIndexOf", R_IntegerType, listOf(R_TextType, R_IntegerType), TextFns.LastIndexOf_3, depError("last_index_of"))
        b.add("last_index_of", R_IntegerType, listOf(R_TextType, R_IntegerType), TextFns.LastIndexOf_3)
        b.add("repeat", R_TextType, listOf(R_IntegerType), TextFns.Repeat)
        b.add("reversed", R_TextType, listOf(), TextFns.Reversed)
        b.add("sub", R_TextType, listOf(R_IntegerType), TextFns.Sub_2)
        b.add("sub", R_TextType, listOf(R_IntegerType, R_IntegerType), TextFns.Sub_3)
        b.add("to_bytes", R_ByteArrayType, listOf(), TextFns.ToBytes)
    }

    override fun bindAliases(b: C_SysNsProtoBuilder) {
        bindAlias(b, "name")
        bindAlias(b, "tuid")
    }

    private object C_SysFn_Text_Format: C_MemberSpecialFuncCase() {
        override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_MemberFuncCaseMatch {
            val body = C_SysMemberFormalParamsFuncBody(R_TextType, TextFns.Format)
            return C_FormalParamsFuncCaseMatch(body, args)
        }
    }
}

private object TextFns {
    private val CHARSET = Charsets.UTF_8

    val Empty = C_SysFunction.simple1(Db_SysFunction.template("text.empty", 1, "(LENGTH(#0) = 0)"), pure = true) { a ->
        val s = a.asString()
        Rt_BooleanValue(s.isEmpty())
    }

    val Size = C_SysFunction.simple1(Db_SysFunction.simple("text.size", "LENGTH"), pure = true) { a ->
        val s = a.asString()
        Rt_IntValue(s.length.toLong())
    }

    val UpperCase = C_SysFunction.simple1(Db_SysFunction.simple("text.upper_case", "UPPER"), pure = true) { a ->
        val s = a.asString()
        Rt_TextValue(s.toUpperCase())
    }

    val LowerCase = C_SysFunction.simple1(Db_SysFunction.simple("text.lower_case", "LOWER"), pure = true) { a ->
        val s = a.asString()
        Rt_TextValue(s.toLowerCase())
    }

    val CompareTo = C_SysFunction.simple2(pure = true) { a, b ->
        val s1 = a.asString()
        val s2 = b.asString()
        Rt_IntValue(s1.compareTo(s2).toLong())
    }

    val StartsWith = C_SysFunction.simple2(
        Db_SysFunction.template("text.starts_with", 2, "(LEFT(#0, LENGTH(#1)) = #1)"),
        pure = true
    ) { a, b ->
        val s1 = a.asString()
        val s2 = b.asString()
        Rt_BooleanValue(s1.startsWith(s2))
    }

    val EndsWith = C_SysFunction.simple2(
        Db_SysFunction.template("text.ends_with", 2, "(RIGHT(#0, LENGTH(#1)) = #1)"),
        pure = true
    ) { a, b ->
        val s1 = a.asString()
        val s2 = b.asString()
        Rt_BooleanValue(s1.endsWith(s2))
    }

    val Contains = C_SysFunction.simple2(
        Db_SysFunction.template("text.contains", 2, "(STRPOS(#0, #1) > 0)"),
        pure = true
    ) { a, b ->
        val s1 = a.asString()
        val s2 = b.asString()
        Rt_BooleanValue(s1.contains(s2))
    }

    val IndexOf_2 = C_SysFunction.simple2(
        Db_SysFunction.template("text.index_of", 2, "(STRPOS(#0, #1) - 1)"),
        pure = true
    ) { a, b ->
        val s1 = a.asString()
        val s2 = b.asString()
        Rt_IntValue(s1.indexOf(s2).toLong())
    }

    val IndexOf_3 = C_SysFunction.simple3(pure = true) { a, b, c ->
        val s1 = a.asString()
        val s2 = b.asString()
        val start = c.asInteger()
        if (start < 0 || start >= s1.length) {
            throw Rt_Error(
                "fn:text.index_of:index:${s1.length}:$start",
                "Index out of bounds: $start (length ${s1.length})"
            )
        }
        Rt_IntValue(s1.indexOf(s2, start.toInt()).toLong())
    }

    val LastIndexOf_2 = C_SysFunction.simple2(pure = true) { a, b ->
        val s1 = a.asString()
        val s2 = b.asString()
        Rt_IntValue(s1.lastIndexOf(s2).toLong())
    }

    val LastIndexOf_3 = C_SysFunction.simple3(pure = true) { a, b, c ->
        val s1 = a.asString()
        val s2 = b.asString()
        val start = c.asInteger()
        if (start < 0 || start >= s1.length) {
            throw Rt_Error(
                "fn:text.last_index_of:index:${s1.length}:$start",
                "Index out of bounds: $start (length ${s1.length})"
            )
        }
        Rt_IntValue(s1.lastIndexOf(s2, start.toInt()).toLong())
    }

    val Repeat = C_SysFunction.simple2(
        Db_SysFunction.template("text.repeat", 2, "${SqlConstants.FN_TEXT_REPEAT}(#0, (#1)::INT)"),
        pure = true
    ) { a, b ->
        val s = a.asString()
        val n = b.asInteger()
        C_Lib_Type_List.rtCheckRepeatArgs(s.length, n, "text")
        if (s.isEmpty() || n == 1L) a else {
            val res = s.repeat(n.toInt())
            Rt_TextValue(res)
        }
    }

    val Replace = C_SysFunction.simple3(
        Db_SysFunction.template("text.replace", 3, "REPLACE(#0, #1, #2)"),
        pure = true
    ) { a, b, c ->
        val s1 = a.asString()
        val s2 = b.asString()
        val s3 = c.asString()
        Rt_TextValue(s1.replace(s2, s3))
    }

    val Reversed = C_SysFunction.simple1(
        Db_SysFunction.template("text.reversed", 1, "REVERSE(#0)"),
        pure = true
    ) { a ->
        val s = a.asString()
        if (s.length <= 1) a else {
            val res = s.reversed()
            Rt_TextValue(res)
        }
    }

    private val SPLIT_TYPE = R_ListType(R_TextType)

    val Split = C_SysFunction.simple2(pure = true) { a, b ->
        val s1 = a.asString()
        val s2 = b.asString()
        val arr = s1.split(s2)
        val list = MutableList<Rt_Value>(arr.size) { Rt_TextValue(arr[it]) }
        Rt_ListValue(SPLIT_TYPE, list)
    }

    val Trim = C_SysFunction.simple1(pure = true) { a ->
        val s = a.asString()
        Rt_TextValue(s.trim())
    }

    //object Trim_Db: Db_SysFn_Template("text.trim", 1, "TRIM(#0, ' '||CHR(9)||CHR(10)||CHR(13))")

    val Like = C_SysFunction.simple2(Db_SysFunction.template("text.like", 2, "((#0) LIKE (#1))"), pure = true) { a, b ->
        val s = a.asString()
        val pattern = b.asString()
        val res = Rt_TextValue.like(s, pattern)
        Rt_BooleanValue(res)
    }

    val Matches = C_SysFunction.simple2(pure = true) { a, b ->
        val s = a.asString()
        val pattern = b.asString()
        val res = try {
            Pattern.matches(pattern, s)
        } catch (e: PatternSyntaxException) {
            throw Rt_Error("fn:text.matches:bad_regex", "Invalid regular expression: $pattern")
        }
        Rt_BooleanValue(res)
    }

    val ToBytes = C_SysFunction.simple1(pure = true) { a ->
        val s = a.asString()
        val ba = s.toByteArray(CHARSET)
        Rt_ByteArrayValue(ba)
    }

    val FromBytes_1 = C_SysFunction.simple1(pure = true) { a ->
        fromBytes(a, false)
    }

    val FromBytes_2 = C_SysFunction.simple2(pure = true) { a, b ->
        val ignoreErr = b.asBoolean()
        fromBytes(a, ignoreErr)
    }

    private fun fromBytes(a: Rt_Value, ignoreErr: Boolean): Rt_Value {
        val bytes = a.asByteArray()
        val s = if (ignoreErr) {
            String(bytes, CHARSET)
        } else {
            val decoder = CHARSET.newDecoder()
            val byteBuffer = ByteBuffer.wrap(bytes)
            val charBuffer = Rt_Utils.wrapErr("fn:text.from_bytes") {
                decoder.decode(byteBuffer)
            }
            charBuffer.toString()
        }
        return Rt_TextValue(s)
    }

    val CharAt = C_SysFunction.simple2(
        Db_SysFunction.template("text.char_at", 2, "ASCII(${SqlConstants.FN_TEXT_GETCHAR}(#0, (#1)::INT))"),
        pure = true
    ) { a, b ->
        val s = a.asString()
        val index = b.asInteger()
        if (index < 0 || index >= s.length) {
            throw Rt_Error(
                "fn:text.char_at:index:${s.length}:$index",
                "Text index out of bounds: $index (length ${s.length})"
            )
        }
        val r = s[index.toInt()]
        Rt_IntValue(r.toLong())
    }

    val Sub_2 = C_SysFunction.simple2(
        Db_SysFunction.template("text.sub/1", 2, "${SqlConstants.FN_TEXT_SUBSTR1}(#0, (#1)::INT)"),
        pure = true
    ) { a, b ->
        val s = a.asString()
        val start = b.asInteger()
        calcSub(s, start, s.length.toLong())
    }

    val Sub_3 = C_SysFunction.simple3(
        Db_SysFunction.template("text.sub/2", 3, "${SqlConstants.FN_TEXT_SUBSTR2}(#0, (#1)::INT, (#2)::INT)"),
        pure = true
    ) { a, b, c ->
        val s = a.asString()
        val start = b.asInteger()
        val end = c.asInteger()
        calcSub(s, start, end)
    }

    private fun calcSub(s: String, start: Long, end: Long): Rt_Value {
        val len = s.length
        if (start < 0 || start > len || end < start || end > len) {
            throw Rt_Error(
                "fn:text.sub:range:$len:$start:$end",
                "Invalid substring range: start = $start, end = $end (length $len)"
            )
        }
        return Rt_TextValue(s.substring(start.toInt(), end.toInt()))
    }

    val Format = C_SysFunction.simple(pure = true) { args ->
        Rt_Utils.check(args.isNotEmpty()) { "fn:text.format:no_args" toCodeMsg "No arguments" }
        val s = args[0].asString()
        val anys = args.drop(1).map { it.asFormatArg() }.toTypedArray()
        val r = try {
            s.format(Locale.US, *anys)
        } catch (e: IllegalFormatException) {
            s
        }
        Rt_TextValue(r)
    }

    val Subscript_Db = Db_SysFunction.template("text.[]", 2, "${SqlConstants.FN_TEXT_GETCHAR}(#0, (#1)::INT)")
}
