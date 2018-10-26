package net.postchain.rell.parser

import net.postchain.rell.model.*

val S_SYS_FUNCTIONS = listOf<S_SysFunction>(
        S_StdSysFunction("unit", RUnitType, listOf(), RSysFunction_Unit, null),

        S_StdSysFunction("abs", RIntegerType, listOf(RIntegerType), RSysFunction_Abs, DbSysFunction_Abs),
        S_StdSysFunction("min", RIntegerType, listOf(RIntegerType, RIntegerType), RSysFunction_Min, DbSysFunction_Min),
        S_StdSysFunction("max", RIntegerType, listOf(RIntegerType, RIntegerType), RSysFunction_Max, DbSysFunction_Max),
        S_StdSysFunction("json", RJSONType, listOf(RTextType), RSysFunction_Json, DbSysFunction_Json),

        S_SysFunction_Range,
        S_SysFunction_Print("print", RSysFunction_Print(false)),
        S_SysFunction_Print("log", RSysFunction_Print(true))
)

sealed class S_SysFunction(val name: String) {
    abstract fun compileCall(args: List<RExpr>): RExpr
    abstract fun compileCallDb(args: List<DbExpr>): DbExpr

    companion object {
        fun matchArgs(name: String, params: List<RType>, args: List<RExpr>): List<RExpr> {
            return matchArgs0(name, params, args, RExpr::type, { t, e -> t.matchOpt(e) })
        }

        fun matchArgsDb(name: String, params: List<RType>, args: List<DbExpr>): List<DbExpr> {
            return matchArgs0(name, params, args, DbExpr::type, { t, e -> t.matchOpt(e) })
        }
    }
}

private class S_StdSysFunction(
        name: String,
        val type: RType,
        val params: List<RType>,
        val rFn: RSysFunction,
        val dbFn: DbSysFunction?
): S_SysFunction(name)
{
    override fun compileCall(args: List<RExpr>): RExpr {
        val args2 = matchArgs(name, params, args)
        return RSysCallExpr(type, rFn, args2)
    }

    override fun compileCallDb(args: List<DbExpr>): DbExpr {
        val args2 = matchArgsDb(name, params, args)
        if (dbFn == null) {
            throw CtError("expr_call_nosql:$name", "Function '$name' has no SQL equivalent")
        }
        return CallDbExpr(type, dbFn, args2)
    }
}

private class S_SysFunction_Print(name: String, val rFn: RSysFunction): S_SysFunction(name) {
    override fun compileCall(args: List<RExpr>): RExpr {
        // Print supports any number of arguments and any types.
        return RSysCallExpr(RUnitType, rFn, args)
    }

    override fun compileCallDb(args: List<DbExpr>): DbExpr {
        TODO()
    }
}

private object S_SysFunction_Range: S_SysFunction("range") {
    override fun compileCall(args: List<RExpr>): RExpr {
        val allParams = listOf(RIntegerType, RIntegerType, RIntegerType)
        val n = Math.min(Math.max(args.size, 1), 3)
        val params = allParams.subList(0, n)

        val args2 = matchArgs(name, params, args)

        val allArgs = if (args2.size == 1) {
            listOf(RIntegerLiteralExpr(0), args2[0], RIntegerLiteralExpr(1))
        } else if (args2.size == 2) {
            listOf(args2[0], args2[1], RIntegerLiteralExpr(1))
        } else {
            args2
        }

        return RSysCallExpr(RRangeType, RSysFunction_Range, allArgs)
    }

    override fun compileCallDb(args: List<DbExpr>): DbExpr = TODO()
}

class S_SysMemberFunction(
        val name: String,
        val type: RType,
        val params: List<RType>,
        val rFn: RSysFunction,
        val dbFn: DbSysFunction?
) {
    fun compileCall(base: RExpr, args: List<RExpr>): RExpr {
        val args2 = S_SysFunction.matchArgs(name, params, args)
        val fullArgs = listOf(base) + args2
        return RSysCallExpr(type, rFn, fullArgs)
    }

    fun compileCallDb(base: DbExpr, args: List<DbExpr>): DbExpr {
        val args2 = S_SysFunction.matchArgsDb(name, params, args)

        if (dbFn == null) {
            throw CtError("expr_call_nosql:${base.type.toStrictString()}:$name",
                    "Function '$name' of ${base.type.toStrictString()} has no SQL equivalent")
        }

        val fullArgs = listOf(base) + args2
        return CallDbExpr(type, dbFn, fullArgs)
    }
}

object S_SysMemberFunctions {
    private val INT_FNS = makeFnMap(
            S_SysMemberFunction("str", RTextType, listOf(), RSysFunction_Int_Str, DbSysFunction_Int_Str)
    )

    private val TEXT_FNS = makeFnMap(
            S_SysMemberFunction("len", RIntegerType, listOf(), RSysFunction_Text_Len, DbSysFunction_Text_Len)
    )

    private val BYTEARRAY_FNS = makeFnMap(
            S_SysMemberFunction("len", RIntegerType, listOf(), RSysFunction_ByteArray_Len, DbSysFunction_ByteArray_Len)
    )

    private val JSON_FNS = makeFnMap(
            S_SysMemberFunction("str", RTextType, listOf(), RSysFunction_Json_Str, DbSysFunction_Json_Str)
    )

    private val LIST_FNS = makeFnMap(
            S_SysMemberFunction("len", RIntegerType, listOf(), RSysFunction_List_Len, null)
    )


    fun getMemberFunction(type: RType, name: String): S_SysMemberFunction {
        val map = getTypeMemberFunctions(type)
        val fn = map[name]
        if (fn == null) {
            throw CtError("expr_call_unknown:${type.toStrictString()}:$name",
                    "Unknown function '$name' for type ${type.toStrictString()}")
        }
        return fn
    }

    private fun getTypeMemberFunctions(type: RType): Map<String, S_SysMemberFunction> {
        if (type == RIntegerType) {
            return INT_FNS
        } else if (type == RTextType) {
            return TEXT_FNS
        } else if (type == RByteArrayType) {
            return BYTEARRAY_FNS
        } else if (type == RJSONType) {
            return JSON_FNS
        } else if (type is RListType) {
            return LIST_FNS
        } else {
            return mapOf()
        }
    }

    private fun makeFnMap(vararg fns: S_SysMemberFunction): Map<String, S_SysMemberFunction> {
        val map = mutableMapOf<String, S_SysMemberFunction>()
        for (fn in fns) {
            check(!(fn.name in map))
            map[fn.name] = fn
        }
        return map.toMap()
    }
}

private fun <T> matchArgs0(
        name: String,
        params: List<RType>,
        args: List<T>,
        typeGetter: (T) -> RType,
        typeMatcher: (RType, T) -> T?
): List<T>
{
    if (args.size != params.size) {
        throw CtError("expr_call_argcnt:$name:${params.size}:${args.size}",
                "Wrong number of arguments for '$name': ${args.size} instead of ${params.size}")
    }

    val res = mutableListOf<T>()

    for (i in params.indices) {
        val param = params[i]
        val arg = args[i]
        val arg2 = typeMatcher(param, arg)
        if (arg2 == null) {
            val argType = typeGetter(arg)
            throw CtError("expr_call_argtype:$name:$i:${param.toStrictString()}:${argType.toStrictString()}",
                    "Wrong argument type for '$name' #${i + 1}: ${argType.toStrictString()} instead of ${param.toStrictString()}")
        }
        res.add(arg2)
    }

    return res
}
