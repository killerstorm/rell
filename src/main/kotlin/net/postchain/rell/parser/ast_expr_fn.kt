package net.postchain.rell.parser

import net.postchain.rell.model.*

abstract class S_SysFunction(val name: String) {
    abstract fun compileCall(args: List<RExpr>): RExpr
    abstract fun compileCallDb(args: List<DbExpr>): DbExpr

    companion object {
        fun matchArgs(name: String, params: List<RType>, args: List<RExpr>) {
            val argTypes = args.map { it.type }
            matchArgs0(name, params, argTypes)
        }

        fun matchArgsDb(name: String, params: List<RType>, args: List<DbExpr>) {
            val argTypes = args.map { it.type }
            matchArgs0(name, params, argTypes)
        }

        private fun matchArgs0(name: String, params: List<RType>, args: List<RType>) {
            if (args.size != params.size) {
                throw CtError("expr_call_argcnt:$name:${params.size}:${args.size}",
                        "Wrong number of arguments for '$name': ${args.size} instead of ${params.size}")
            }

            for ((i, param) in params.withIndex()) {
                val arg = args[i]
                if (!param.isAssignableFrom(arg)) {
                    throw CtError("expr_call_argtype:$name:$i:${param.toStrictString()}:${arg.toStrictString()}",
                            "Wrong argument type for '$name' #${i + 1}: ${arg.toStrictString()} instead of ${param.toStrictString()}")
                }
            }
        }
    }
}

abstract class S_SysMemberFunction(val name: String) {
    abstract fun compileCall(baseType: RType, args: List<RExpr>): RMemberCalculator
    abstract fun compileCallDb(base: DbExpr, args: List<DbExpr>): DbExpr
}

sealed class C_ArgTypeMatcher {
    abstract fun match(type: RType): Boolean
}

class C_ArgTypeMatcher_Simple(val targetType: RType): C_ArgTypeMatcher() {
    override fun match(type: RType) = targetType.isAssignableFrom(type)
}

class C_ArgTypeMatcher_CollectionSub(val elementType: RType): C_ArgTypeMatcher() {
    override fun match(type: RType) = type is RCollectionType && elementType.isAssignableFrom(type.elementType)
}

class C_ArgTypeMatcher_MapSub(val keyType: RType, val valueType: RType): C_ArgTypeMatcher() {
    override fun match(type: RType) =
            type is RMapType
            && keyType.isAssignableFrom(type.keyType)
            && valueType.isAssignableFrom(type.valueType)
}

sealed class C_GlobalOverloadFnCase {
    abstract fun compileCall(name: String, args: List<RExpr>): RExpr?
    abstract fun compileCallDb(name: String, args: List<DbExpr>): DbExpr?
}

class C_StdGlobalOverloadFnCase(
        val params: List<C_ArgTypeMatcher>,
        val type: RType,
        val rFn: RSysFunction,
        val dbFn: DbSysFunction?
): C_GlobalOverloadFnCase()
{
    override fun compileCall(name: String, args: List<RExpr>): RExpr? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        return RSysCallExpr(type, rFn, args)
    }

    override fun compileCallDb(name: String, args: List<DbExpr>): DbExpr? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        if (dbFn == null) throw CtError("expr_call_nosql:$name", "Function '$name' has no SQL equivalent")
        return CallDbExpr(type, dbFn, args)
    }
}

abstract class C_CustomGlobalOverloadFnCase: C_GlobalOverloadFnCase() {
    override fun compileCallDb(name: String, args: List<DbExpr>): DbExpr? = null
}

sealed class C_MemberOverloadFnCase {
    abstract fun compileCall(name: String, args: List<RExpr>): RMemberCalculator?
    abstract fun compileCallDb(name: String, base: DbExpr, args: List<DbExpr>): DbExpr?
}

class C_StdMemberOverloadFnCase(
        val params: List<C_ArgTypeMatcher>,
        val type: RType,
        val rFn: RSysFunction,
        val dbFn: DbSysFunction?
): C_MemberOverloadFnCase()
{
    override fun compileCall(name: String, args: List<RExpr>): RMemberCalculator? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        return RMemberCalculator_SysFn(type, rFn, args)
    }

    override fun compileCallDb(name: String, base: DbExpr, args: List<DbExpr>): DbExpr? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        if (dbFn == null) throw CtError("expr_call_nosql:$name", "Function '$name' has no SQL equivalent")
        val fullArgs = listOf(base) + args
        return CallDbExpr(type, dbFn, fullArgs)
    }
}

object C_OverloadFnUtils {
    fun compileCall(name: String, cases: List<C_GlobalOverloadFnCase>, args: List<RExpr>): RExpr {
        for (case in cases) {
            val res = case.compileCall(name, args)
            if (res != null) {
                return res
            }
        }
        throw errNoMatch(name, args.map { it.type })
    }

    fun compileCallDb(name: String, cases: List<C_GlobalOverloadFnCase>, args: List<DbExpr>): DbExpr {
        for (case in cases) {
            val res = case.compileCallDb(name, args)
            if (res != null) {
                return res
            }
        }
        throw errNoMatch(name, args.map { it.type })
    }

    fun compileCall(name: String, cases: List<C_MemberOverloadFnCase>, args: List<RExpr>): RMemberCalculator {
        for (case in cases) {
            val res = case.compileCall(name, args)
            if (res != null) {
                return res
            }
        }
        throw errNoMatch(name, args.map { it.type })
    }

    fun compileCallDb(name: String, cases: List<C_MemberOverloadFnCase>, base: DbExpr, args: List<DbExpr>): DbExpr {
        for (case in cases) {
            val res = case.compileCallDb(name, base, args)
            if (res != null) {
                return res
            }
        }
        throw errNoMatch(name, args.map { it.type })
    }

    fun matchArgs(params: List<C_ArgTypeMatcher>, args: List<RType>): Boolean {
        if (args.size != params.size) {
            return false
        }

        for ((i, arg) in args.withIndex()) {
            if (!params[i].match(arg)) {
                return false
            }
        }

        return true
    }

    fun errNoMatch(name: String, args: List<RType>): CtError {
        val argsStrShort = args.joinToString(",") { it.toStrictString() }
        val argsStr = args.joinToString { it.toStrictString() }
        return CtError("expr_call_argtypes:$name:$argsStrShort", "Function $name undefined for arguments ($argsStr)")
    }
}

class S_StdSysFunction(name: String, private val cases: List<C_GlobalOverloadFnCase>): S_SysFunction(name) {
    override fun compileCall(args: List<RExpr>) = C_OverloadFnUtils.compileCall(name, cases, args)
    override fun compileCallDb(args: List<DbExpr>) = C_OverloadFnUtils.compileCallDb(name, cases, args)
}

class S_StdSysMemberFunction(name: String, val cases: List<C_MemberOverloadFnCase>): S_SysMemberFunction(name) {
    override fun compileCall(baseType: RType, args: List<RExpr>): RMemberCalculator {
        return C_OverloadFnUtils.compileCall("${baseType.toStrictString()}.$name", cases, args)
    }

    override fun compileCallDb(base: DbExpr, args: List<DbExpr>): DbExpr {
        return C_OverloadFnUtils.compileCallDb("${base.type.toStrictString()}.$name", cases, base, args)
    }
}
