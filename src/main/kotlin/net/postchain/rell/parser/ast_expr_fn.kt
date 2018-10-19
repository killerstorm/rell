package net.postchain.rell.parser

import net.postchain.rell.model.*

val S_SYS_FUNCTIONS = listOf(
        S_SysFunction("abs", RIntegerType, listOf(RIntegerType), RSysFunction_Abs, DbSysFunction_Abs),
        S_SysFunction("min", RIntegerType, listOf(RIntegerType, RIntegerType), RSysFunction_Min, DbSysFunction_Min),
        S_SysFunction("max", RIntegerType, listOf(RIntegerType, RIntegerType), RSysFunction_Max, DbSysFunction_Max),
        S_SysFunction("json", RJSONType, listOf(RTextType), RSysFunction_Json, DbSysFunction_Json)
)

class S_SysFunction(
        val name: String,
        val type: RType,
        val params: List<RType>,
        val rFn: RSysFunction,
        val dbFn: DbSysFunction?)
{
    fun compileCall(args: List<RExpr>): RExpr {
        val types = args.map { it.type }
        checkArgs(types)
        return RCallExpr(type, rFn, args)
    }

    fun compileCallDb(args: List<DbExpr>): DbExpr {
        val types = args.map { it.type }
        checkArgs(types)

        if (dbFn == null) {
            throw CtError("expr_call_nosql:$name", "Function '$name' has no SQL equivalent")
        }

        return CallDbExpr(type, dbFn, args)
    }

    fun compileMemberCall(base: RExpr, args: List<RExpr>): RExpr {
        val types = args.map { it.type }
        checkArgs(types)
        val fullArgs = listOf(base) + args
        return RCallExpr(type, rFn, fullArgs)
    }

    fun compileMemberCallDb(base: DbExpr, args: List<DbExpr>): DbExpr {
        val types = args.map { it.type }
        checkArgs(types)

        if (dbFn == null) {
            throw CtError("expr_call_nosql:${base.type.toStrictString()}:$name",
                    "Function '$name' of ${base.type.toStrictString()} has no SQL equivalent")
        }

        val fullArgs = listOf(base) + args
        return CallDbExpr(type, dbFn, fullArgs)
    }

    private fun checkArgs(args: List<RType>) {
        if (args.size != params.size) {
            throw CtError("expr_call_argcnt:$name:${params.size}:${args.size}",
                    "Wrong number of arguments for '$name': ${args.size} instead of ${params.size}")
        }

        for (i in params.indices) {
            val param = params[i]
            val arg = args[i]
            if (!param.accepts(arg)) {
                throw CtError("expr_call_argtype:$name:$i:${param.toStrictString()}:${arg.toStrictString()}",
                        "Wrong argument type for '$name' #${i + 1}: ${arg.toStrictString()} instead of ${param.toStrictString()}")
            }
        }
    }
}

object S_SysMemberFunctions {
    private val INT_FNS = makeFnMap(
            S_SysFunction("str", RTextType, listOf(), RSysFunction_Int_Str, DbSysFunction_Int_Str)
    )

    private val TEXT_FNS = makeFnMap(
            S_SysFunction("len", RIntegerType, listOf(), RSysFunction_Text_Len, DbSysFunction_Text_Len)
    )

    private val BYTEARRAY_FNS = makeFnMap(
            S_SysFunction("len", RIntegerType, listOf(), RSysFunction_ByteArray_Len, DbSysFunction_ByteArray_Len)
    )

    private val JSON_FNS = makeFnMap(
            S_SysFunction("str", RTextType, listOf(), RSysFunction_Json_Str, DbSysFunction_Json_Str)
    )

    private val LIST_FNS = makeFnMap(
            S_SysFunction("len", RIntegerType, listOf(), RSysFunction_List_Len, null)
    )


    fun getMemberFunction(type: RType, name: String): S_SysFunction {
        val map = getTypeMemberFunctions(type)
        val fn = map[name]
        if (fn == null) {
            throw CtError("expr_call_unknown:${type.toStrictString()}:$name",
                    "Unknown function '$name' for type ${type.toStrictString()}")
        }
        return fn
    }

    private fun getTypeMemberFunctions(type: RType): Map<String, S_SysFunction> {
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

    private fun makeFnMap(vararg fns: S_SysFunction): Map<String, S_SysFunction> {
        val map = mutableMapOf<String, S_SysFunction>()
        for (fn in fns) {
            check(!(fn.name in map))
            map[fn.name] = fn
        }
        return map.toMap()
    }
}
