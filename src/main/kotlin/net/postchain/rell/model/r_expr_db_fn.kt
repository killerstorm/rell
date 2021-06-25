package net.postchain.rell.model

import net.postchain.rell.compiler.C_Constants
import net.postchain.rell.sql.SqlConstants
import net.postchain.rell.utils.toImmList
import java.util.regex.Pattern

abstract class Db_SysFunction(val name: String) {
    abstract fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>)
}

abstract class Db_SysFn_Simple(name: String, val sql: String): Db_SysFunction(name) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
        bld.append(sql)
        bld.append("(")
        bld.append(args, ", ") {
            it.toSql(ctx, bld, false)
        }
        bld.append(")")
    }
}

abstract class Db_SysFn_Template(name: String, private val arity: Int, template: String): Db_SysFunction(name) {
    private val fragments: List<Pair<String?, Int?>>

    init {
        val pat = Pattern.compile("#\\d")
        val m = pat.matcher(template)

        val list = mutableListOf<Pair<String?, Int?>>()
        var i = 0

        while (m.find()) {
            val start = m.start()
            val end = m.end()
            if (i < start) list.add(Pair(template.substring(i, start), null))
            val v = m.group().substring(1).toInt()
            list.add(Pair(null, v))
            i = end
        }

        if (i < template.length) list.add(Pair(template.substring(i), null))

        fragments = list.toImmList()
    }

    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
        check(args.size == arity)
        for (f in fragments) {
            if (f.first != null) bld.append(f.first!!)
            if (f.second != null) args[f.second!!].toSql(ctx, bld, false)
        }
    }
}

abstract class Db_SysFn_Cast(name: String, val type: String): Db_SysFunction(name) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
        check(args.size == 1)
        bld.append("(")
        args[0].toSql(ctx, bld, true)
        bld.append("::$type)")
    }
}

object Db_SysFn_Int_ToText: Db_SysFn_Cast("int.to_text", "TEXT")

object Db_SysFn_Abs_Integer: Db_SysFn_Simple("abs", "ABS")
object Db_SysFn_Abs_Decimal: Db_SysFn_Simple("abs", "ABS")
object Db_SysFn_Min_Integer: Db_SysFn_Simple("min", "LEAST")
object Db_SysFn_Min_Decimal: Db_SysFn_Simple("min", "LEAST")
object Db_SysFn_Max_Integer: Db_SysFn_Simple("max", "GREATEST")
object Db_SysFn_Max_Decimal: Db_SysFn_Simple("max", "GREATEST")

object Db_SysFn_Sign: Db_SysFn_Simple("sign", "SIGN")

object Db_SysFn_Text_CharAt: Db_SysFn_Template("text.char_at", 2, "ASCII(${SqlConstants.FN_TEXT_GETCHAR}(#0, (#1)::INT))")
object Db_SysFn_Text_Contains: Db_SysFn_Template("text.contains", 2, "(STRPOS(#0, #1) > 0)")
object Db_SysFn_Text_Empty: Db_SysFn_Template("text.empty", 1, "(LENGTH(#0) = 0)")
object Db_SysFn_Text_EndsWith: Db_SysFn_Template("text.ends_with", 2, "(RIGHT(#0, LENGTH(#1)) = #1)")
object Db_SysFn_Text_IndexOf: Db_SysFn_Template("text.index_of", 2, "(STRPOS(#0, #1) - 1)")
object Db_SysFn_Text_Like: Db_SysFn_Template("text.like", 2, "((#0) LIKE (#1))")
object Db_SysFn_Text_LowerCase: Db_SysFn_Simple("text.lower_case", "LOWER")
object Db_SysFn_Text_Replace: Db_SysFn_Template("text.replace", 3, "REPLACE(#0, #1, #2)")
object Db_SysFn_Text_Size: Db_SysFn_Simple("text.size", "LENGTH")
object Db_SysFn_Text_StartsWith: Db_SysFn_Template("text.starts_with", 2, "(LEFT(#0, LENGTH(#1)) = #1)")
object Db_SysFn_Text_Sub_1: Db_SysFn_Template("text.sub/1", 2, "${SqlConstants.FN_TEXT_SUBSTR1}(#0, (#1)::INT)")
object Db_SysFn_Text_Sub_2: Db_SysFn_Template("text.sub/2", 3, "${SqlConstants.FN_TEXT_SUBSTR2}(#0, (#1)::INT, (#2)::INT)")
object Db_SysFn_Text_Subscript: Db_SysFn_Template("text.[]", 2, "${SqlConstants.FN_TEXT_GETCHAR}(#0, (#1)::INT)")
//object Db_SysFn_Text_Trim: Db_SysFn_Template("text.trim", 1, "TRIM(#0, ' '||CHR(9)||CHR(10)||CHR(13))")
object Db_SysFn_Text_UpperCase: Db_SysFn_Simple("text.upper_case", "UPPER")

object Db_SysFn_ByteArray_Empty: Db_SysFn_Template("byte_array.empty", 1, "(LENGTH(#0) = 0)")
object Db_SysFn_ByteArray_Size: Db_SysFn_Template("byte_array.size", 1, "LENGTH(#0)")
object Db_SysFn_ByteArray_Sub_1: Db_SysFn_Template("byte_array.sub/1", 2, "${SqlConstants.FN_BYTEA_SUBSTR1}(#0, (#1)::INT)")
object Db_SysFn_ByteArray_Sub_2: Db_SysFn_Template("byte_array.sub/2", 3, "${SqlConstants.FN_BYTEA_SUBSTR2}(#0, (#1)::INT, (#2)::INT)")
object Db_SysFn_ByteArray_Subscript: Db_SysFn_Template("byte_array.[]", 2, "GET_BYTE(#0, (#1)::INT)")
object Db_SysFn_ByteArray_ToHex: Db_SysFn_Template("byte_array.to_hex", 1, "ENCODE(#0, 'HEX')")
object Db_SysFn_ByteArray_ToBase64: Db_SysFn_Template("byte_array.to_base64", 1, "ENCODE(#0, 'BASE64')")

object Db_SysFn_Json: Db_SysFn_Cast("json", "JSONB")
object Db_SysFn_Json_ToText: Db_SysFn_Cast("json.to_text", "TEXT")

object Db_SysFn_ToText: Db_SysFn_Cast("to_text", "TEXT")

object Db_SysFn_Aggregation_Sum: Db_SysFn_Template("sum", 1, "COALESCE(SUM(#0),0)")
object Db_SysFn_Aggregation_Min: Db_SysFn_Simple("min", "MIN")
object Db_SysFn_Aggregation_Max: Db_SysFn_Simple("max", "MAX")

object Db_SysFn_Decimal {
    object FromInteger: Db_SysFn_Cast("decimal(integer)", C_Constants.DECIMAL_SQL_TYPE_STR)
    object FromText: Db_SysFn_Cast("decimal(text)", C_Constants.DECIMAL_SQL_TYPE_STR)

    object Ceil: Db_SysFn_Simple("decimal.ceil", "CEIL")
    object Floor: Db_SysFn_Simple("decimal.floor", "FLOOR")

    object Round: Db_SysFunction("decimal.round") {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
            check(args.size == 1 || args.size == 2)
            bld.append("ROUND(")
            args[0].toSql(ctx, bld, false)
            if (args.size == 2) {
                // Argument #2 has to be casted to INT, PostgreSQL doesn't allow BIGINT.
                bld.append(", ")
                args[1].toSql(ctx, bld, true)
                bld.append("::INT")
            }
            bld.append(")")
        }
    }

    object Pow: Db_SysFn_Simple("decimal.pow", "POW")
    object Sign: Db_SysFn_Simple("decimal.sign", "SIGN")
    object Sqrt: Db_SysFn_Simple("decimal.sqrt", "SQRT")

    object ToInteger: Db_SysFunction("decimal.to_integer") {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
            check(args.size == 1)
            bld.append("TRUNC(")
            args[0].toSql(ctx, bld, false)
            bld.append(")::BIGINT")
        }
    }

    object ToText: Db_SysFunction("decimal.to_text") {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
            // Using regexp to remove trailing zeros.
            check(args.size == 1)
            bld.append("REGEXP_REPLACE(")
            args[0].toSql(ctx, bld, true)
            // Clever regexp: can handle special cases like "0.0", "0.000000", etc.
            bld.append(" :: TEXT, '(([.][0-9]*[1-9])(0+)\$)|([.]0+\$)', '\\2')")
        }
    }
}

object Db_SysFn_Nop: Db_SysFunction("NOP") {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
        check(args.size == 1)
        args[0].toSql(ctx, bld, true)
    }
}
