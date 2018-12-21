package net.postchain.rell.parser

import net.postchain.rell.model.*
import java.util.*

abstract class S_SysFunction(val name: String) {
    abstract fun compileCall(pos: S_Pos, args: List<RExpr>): RExpr
    abstract fun compileCallDb(pos: S_Pos, args: List<DbExpr>): DbExpr

    companion object {
        fun matchArgs(name: S_Name, params: List<RType>, args: List<RExpr>) {
            val argTypes = args.map { it.type }
            matchArgs0(name, params, argTypes)
        }

        private fun matchArgs0(name: S_Name, params: List<RType>, args: List<RType>) {
            val nameStr = name.str

            if (args.size != params.size) {
                throw C_Error(name.pos, "expr_call_argcnt:$nameStr:${params.size}:${args.size}",
                        "Wrong number of arguments for '$nameStr': ${args.size} instead of ${params.size}")
            }

            for ((i, param) in params.withIndex()) {
                val arg = args[i]
                if (!param.isAssignableFrom(arg)) {
                    throw C_Error(name.pos, "expr_call_argtype:$nameStr:$i:${param.toStrictString()}:${arg.toStrictString()}",
                            "Wrong argument type for '$nameStr' #${i + 1}: ${arg.toStrictString()} instead of ${param.toStrictString()}")
                }
            }
        }
    }
}

abstract class S_SysMemberFunction(val name: String) {
    abstract fun compileCall(pos: S_Pos, baseType: RType, args: List<RExpr>): RMemberCalculator
    abstract fun compileCallDb(pos: S_Pos, base: DbExpr, args: List<DbExpr>): DbExpr
}

sealed class C_ArgTypeMatcher {
    abstract fun match(type: RType): Boolean
}

object C_ArgTypeMatcher_Any: C_ArgTypeMatcher() {
    override fun match(type: RType) = true
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

abstract class C_GlobalOverloadFnCase {
    abstract fun compileCall(pos: S_Pos, name: String, args: List<RExpr>): RExpr?
    abstract fun compileCallDb(pos: S_Pos, name: String, args: List<DbExpr>): Optional<DbExpr>?
}

class C_StdGlobalOverloadFnCase(
        val params: List<C_ArgTypeMatcher>,
        val type: RType,
        val rFn: RSysFunction,
        val dbFn: DbSysFunction?
): C_GlobalOverloadFnCase()
{
    override fun compileCall(pos: S_Pos, name: String, args: List<RExpr>): RExpr? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        return RSysCallExpr(type, rFn, args)
    }

    override fun compileCallDb(pos: S_Pos, name: String, args: List<DbExpr>): Optional<DbExpr>? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        if (dbFn == null) return Optional.empty()
        return Optional.of(CallDbExpr(type, dbFn, args))
    }
}

abstract class C_CustomGlobalOverloadFnCase: C_GlobalOverloadFnCase() {
    override fun compileCallDb(pos: S_Pos, name: String, args: List<DbExpr>): Optional<DbExpr>? = null
}

abstract class C_MemberOverloadFnCase {
    abstract fun compileCall(pos: S_Pos, name: String, args: List<RExpr>): RMemberCalculator?
    abstract fun compileCallDb(pos: S_Pos, name: String, base: DbExpr, args: List<DbExpr>): Optional<DbExpr>?
}

class C_StdMemberOverloadFnCase(
        val params: List<C_ArgTypeMatcher>,
        val type: RType,
        val rFn: RSysFunction,
        val dbFn: DbSysFunction?
): C_MemberOverloadFnCase()
{
    override fun compileCall(pos: S_Pos, name: String, args: List<RExpr>): RMemberCalculator? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        return RMemberCalculator_SysFn(type, rFn, args)
    }

    override fun compileCallDb(pos: S_Pos, name: String, base: DbExpr, args: List<DbExpr>): Optional<DbExpr>? {
        if (!C_OverloadFnUtils.matchArgs(params, args.map { it.type })) return null
        if (dbFn == null) return Optional.empty()
        val fullArgs = listOf(base) + args
        return Optional.of(CallDbExpr(type, dbFn, fullArgs))
    }
}

object C_OverloadFnUtils {
    fun compileCall(pos: S_Pos, name: String, cases: List<C_GlobalOverloadFnCase>, args: List<RExpr>): RExpr {
        for (case in cases) {
            val res = case.compileCall(pos, name, args)
            if (res != null) {
                return res
            }
        }
        throw errNoMatch(pos, name, args.map { it.type })
    }

    fun compileCallDb(pos: S_Pos, name: String, cases: List<C_GlobalOverloadFnCase>, args: List<DbExpr>): DbExpr {
        for (case in cases) {
            val opt = case.compileCallDb(pos, name, args)
            if (opt != null) {
                return compileCallDbCase(pos, name, opt)
            }
        }
        throw errNoMatch(pos, name, args.map { it.type })
    }

    fun compileCall(pos: S_Pos, name: String, cases: List<C_MemberOverloadFnCase>, args: List<RExpr>): RMemberCalculator {
        for (case in cases) {
            val res = case.compileCall(pos, name, args)
            if (res != null) {
                return res
            }
        }
        throw errNoMatch(pos, name, args.map { it.type })
    }

    fun compileCallDb(pos: S_Pos, name: String, cases: List<C_MemberOverloadFnCase>, base: DbExpr, args: List<DbExpr>): DbExpr {
        for (case in cases) {
            val opt = case.compileCallDb(pos, name, base, args)
            if (opt != null) {
                return compileCallDbCase(pos, name, opt)
            }
        }
        throw errNoMatch(pos, name, args.map { it.type })
    }

    private fun compileCallDbCase(pos: S_Pos, name: String, opt: Optional<DbExpr>): DbExpr {
        if (opt.isPresent) {
            return opt.get()
        } else {
            throw C_Utils.errFunctionNoSql(pos, name)
        }
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

    fun errNoMatch(pos: S_Pos, name: String, args: List<RType>): C_Error {
        val argsStrShort = args.joinToString(",") { it.toStrictString() }
        val argsStr = args.joinToString { it.toStrictString() }
        return C_Error(pos, "expr_call_argtypes:$name:$argsStrShort", "Function $name undefined for arguments ($argsStr)")
    }
}

class S_StdSysFunction(name: String, private val cases: List<C_GlobalOverloadFnCase>): S_SysFunction(name) {
    override fun compileCall(pos: S_Pos, args: List<RExpr>) = C_OverloadFnUtils.compileCall(pos, name, cases, args)
    override fun compileCallDb(pos: S_Pos, args: List<DbExpr>) = C_OverloadFnUtils.compileCallDb(pos, name, cases, args)
}

class S_StdSysMemberFunction(name: String, val cases: List<C_MemberOverloadFnCase>): S_SysMemberFunction(name) {
    override fun compileCall(pos: S_Pos, baseType: RType, args: List<RExpr>): RMemberCalculator {
        return C_OverloadFnUtils.compileCall(pos, "${baseType.toStrictString()}.$name", cases, args)
    }

    override fun compileCallDb(pos: S_Pos, base: DbExpr, args: List<DbExpr>): DbExpr {
        return C_OverloadFnUtils.compileCallDb(pos, "${base.type.toStrictString()}.$name", cases, base, args)
    }
}
