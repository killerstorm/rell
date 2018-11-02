package net.postchain.rell.parser

import net.postchain.rell.model.*

abstract class S_SysFunction(val name: String) {
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

sealed class S_SysMemberFunction(val name: String, val type: RType) {
    abstract fun compileCall(base: RExpr, args: List<RExpr>): RExpr
    abstract fun compileCallDb(base: DbExpr, args: List<DbExpr>): DbExpr
}

class S_OverloadFnCase(val params: List<RType>, val rFn: RSysFunction, val dbFn: DbSysFunction?) {
    fun <T> matchArgs(args: List<T>, matcher: (RType, T) -> T?): List<T>? {
        if (args.size != params.size) {
            return null
        }

        val args2 = mutableListOf<T>()
        for ((i, arg) in args.withIndex()) {
            val arg2 = matcher(params[i], arg)
            if (arg2 == null) {
                return null
            }
            args2.add(arg2)
        }

        return args2
    }

    companion object {
        fun match(name: String, cases: List<S_OverloadFnCase>, args: List<RExpr>): Pair<RSysFunction, List<RExpr>> {
            val (case, args2) = match0(name, cases, args, RExpr::type, RType::matchOpt)
            return Pair(case.rFn, args2)
        }

        fun matchDb(name: String, cases: List<S_OverloadFnCase>, args: List<DbExpr>): Pair<DbSysFunction, List<DbExpr>> {
            val (case, args2) = match0(name, cases, args, DbExpr::type, RType::matchOpt)
            if (case.dbFn == null) {
                throw CtError("expr_call_nosql:$name", "Function '$name' has no SQL equivalent")
            }
            return Pair(case.dbFn, args2)
        }

        private fun <T> match0(
                name: String,
                cases: List<S_OverloadFnCase>,
                args: List<T>,
                getter: (T) -> RType,
                matcher: (RType, T) -> T?
        ): Pair<S_OverloadFnCase, List<T>>
        {
            for (case in cases) {
                val args2 = case.matchArgs(args, matcher)
                if (args2 != null) {
                    return Pair(case, args2)
                }
            }
            throw errNoMatch(name, args, getter)
        }

        private fun <T> errNoMatch(name: String, args: List<T>, getter: (T) -> RType): CtError {
            val argsStrShort = args.joinToString(",") { getter(it).toStrictString() }
            val argsStr = args.joinToString { getter(it).toStrictString() }
            return CtError("expr_call_argtypes:$name:$argsStrShort", "Function $name undefined for arguments ($argsStr)")
        }
    }
}

class S_StdSysFunction(
        name: String,
        val type: RType,
        val cases: List<S_OverloadFnCase>
): S_SysFunction(name)
{
    override fun compileCall(args: List<RExpr>): RExpr {
        val (rFn, args2) = S_OverloadFnCase.match(name, cases, args)
        return RSysCallExpr(type, rFn, args2)
    }

    override fun compileCallDb(args: List<DbExpr>): DbExpr {
        val (dbFn, args2) = S_OverloadFnCase.matchDb(name, cases, args)
        return CallDbExpr(type, dbFn, args2)
    }
}

class S_StdSysMemberFunction(name: String, type: RType, val cases: List<S_OverloadFnCase>)
    : S_SysMemberFunction(name, type)
{
    override fun compileCall(base: RExpr, args: List<RExpr>): RExpr {
        val (rFn, args2) = S_OverloadFnCase.match("${base.type.toStrictString()}.$name", cases, args)
        val fullArgs = listOf(base) + args2
        return RSysCallExpr(type, rFn, fullArgs)
    }

    override fun compileCallDb(base: DbExpr, args: List<DbExpr>): DbExpr {
        val (dbFn, args2) = S_OverloadFnCase.matchDb("${base.type.toStrictString()}.$name", cases, args)
        val fullArgs = listOf(base) + args2
        return CallDbExpr(type, dbFn, fullArgs)
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
