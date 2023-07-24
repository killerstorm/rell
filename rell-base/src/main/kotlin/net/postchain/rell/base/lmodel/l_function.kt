/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.C_TypeAdapter
import net.postchain.rell.base.compiler.base.core.C_VarUid
import net.postchain.rell.base.compiler.base.def.C_GlobalFunction
import net.postchain.rell.base.compiler.base.expr.C_VarFact
import net.postchain.rell.base.compiler.base.expr.C_VarFacts
import net.postchain.rell.base.compiler.base.lib.C_LibMemberFunction
import net.postchain.rell.base.compiler.base.lib.C_SysFunction
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.utils.*

enum class L_ParamArity(val mArity: M_ParamArity) {
    ONE(M_ParamArity.ONE),
    ZERO_ONE(M_ParamArity.ZERO_ONE),
    ZERO_MANY(M_ParamArity.ZERO_MANY),
    ONE_MANY(M_ParamArity.ONE_MANY),
}

enum class L_ParamImplication {
    NOT_NULL {
        override fun toVarFacts(varId: C_VarUid): C_VarFacts {
            return C_VarFacts.of(nulled = mapOf(varId to C_VarFact.NO))
        }
    },
    ;

    abstract fun toVarFacts(varId: C_VarUid): C_VarFacts
}

class L_FunctionParam(
    val name: R_Name?,
    val mParam: M_FunctionParam,
    val lazy: Boolean,
    val implies: L_ParamImplication?,
) {
    val type = mParam.type
    val arity = mParam.arity
    val nullable = mParam.nullable

    override fun toString() = strCode()

    fun strCode(): String {
        var res = mParam.strCode()
        if (lazy) res = "@lazy $res"
        if (implies != null) res = "@implies($implies) $res"
        return res
    }

    fun replaceMParam(newMParam: M_FunctionParam): L_FunctionParam {
        return if (newMParam === mParam) this else L_FunctionParam(
            name = name,
            newMParam,
            lazy = lazy,
            implies = implies,
        )
    }

    fun validate() {
        mParam.validate()
    }
}

class L_FunctionHeader(
    private val mHeader: M_FunctionHeader,
    val params: List<L_FunctionParam>,
) {
    val typeParams = mHeader.typeParams
    val resultType = mHeader.resultType

    init {
        checkEquals(params.size, mHeader.params.size)
        for (i in params.indices) {
            check(params[i].mParam === mHeader.params[i]) {
                "$mHeader ; $i ; ${params[i].mParam} ; ${mHeader.params[i]}"
            }
        }
    }

    fun strCode(name: String? = null): String {
        val parts = mutableListOf<String>()
        if (typeParams.isNotEmpty()) parts.add(typeParams.joinToString(",", "<", ">") { it.strCode() })
        val s = "${name ?: ""}(${params.joinToString(", ") { it.strCode() }}): ${resultType.strCode()}"
        parts.add(s)
        return parts.joinToString(" ")
    }

    override fun toString() = strCode()

    fun validate() {
        mHeader.validate()
    }

    fun replaceTypeParams(map: Map<M_TypeParam, M_TypeSet>): L_FunctionHeader {
        if (map.isEmpty()) {
            return this
        }

        val mHeader2 = mHeader.replaceTypeParams(map)
        checkEquals(mHeader2.params.size, params.size)

        val params2 = params.mapIndexedOrSame { i, param ->
            param.replaceMParam(mHeader2.params[i])
        }

        return if (mHeader2 === mHeader && params2 === params) this else L_FunctionHeader(mHeader2, params2)
    }

    fun matchParams(nArgs: Int): L_FunctionParamsMatch? {
        val mMatch = mHeader.matchParams(nArgs)
        mMatch ?: return null
        val actualParams = mMatch.paramIndexes.map { i -> params[i].replaceMParam(mMatch.actualParams[i]) }.toImmList()
        return L_FunctionParamsMatch(this, mMatch, actualParams)
    }
}

class L_FunctionParamsMatch(
    private val header: L_FunctionHeader,
    private val mMatch: M_FunctionParamsMatch,
    val actualParams: List<L_FunctionParam>,
) {
    fun matchArgs(argTypes: List<M_Type>, expectedResultType: M_Type?): L_FunctionHeaderMatch? {
        val m = mMatch.matchArgs(argTypes, expectedResultType)
        m ?: return null

        val adapters = m.conversions.mapNotNullAllOrNull {
            L_TypeUtils.getTypeAdapter(it)
        }

        adapters ?: return null

        val resParams = actualParams
            .mapIndexed { i, lParam -> lParam.replaceMParam(m.actualHeader.params[i]) }
            .toImmList()

        val actualHeader = L_FunctionHeader(m.actualHeader, resParams)

        return L_FunctionHeaderMatch(
            actualHeader,
            adapters = adapters,
            typeArgs = m.typeArgs.mapKeys { R_Name.of(it.key) }.toImmMap(),
        )
    }
}

class L_FunctionHeaderMatch(
    val actualHeader: L_FunctionHeader,
    val adapters: List<C_TypeAdapter>,
    val typeArgs: Map<R_Name, M_Type>,
)

class L_Function(
    val qualifiedName: R_QualifiedName,
    val header: L_FunctionHeader,
    val body: L_FunctionBody,
) {
    val simpleName = qualifiedName.last

    fun strCode(actualName: R_QualifiedName = qualifiedName): String {
        return "function ${header.strCode(actualName.str())}"
    }

    fun replaceTypeParams(map: Map<M_TypeParam, M_TypeSet>): L_Function {
        val header2 = header.replaceTypeParams(map)
        return if (header2 === header) this else L_Function(qualifiedName, header2, body)
    }

    fun validate() {
        header.validate()
    }
}

class L_FunctionBodyMeta(
    val callPos: S_Pos,
    val rSelfType: R_Type,
    val rResultType: R_Type,
    val rTypeArgs: Map<String, R_Type>,
) {
    fun typeArg(name: String): R_Type {
        return rTypeArgs.getValue(name)
    }

    fun typeArgs(name1: String, name2: String): Pair<R_Type, R_Type> {
        val type1 = rTypeArgs.getValue(name1)
        val type2 = rTypeArgs.getValue(name2)
        return Pair(type1, type2)
    }
}

sealed class L_FunctionBody {
    abstract fun getSysFunction(meta: L_FunctionBodyMeta): C_SysFunction

    private class L_FunctionBody_Direct(private val fn: C_SysFunction): L_FunctionBody() {
        override fun getSysFunction(meta: L_FunctionBodyMeta) = fn
    }

    private class L_FunctionBody_Delegating(
        private val block: (L_FunctionBodyMeta) -> C_SysFunction,
    ): L_FunctionBody() {
        override fun getSysFunction(meta: L_FunctionBodyMeta): C_SysFunction {
            return block(meta)
        }
    }

    companion object {
        fun direct(fn: C_SysFunction): L_FunctionBody = L_FunctionBody_Direct(fn)

        fun delegating(block: (L_FunctionBodyMeta) -> C_SysFunction): L_FunctionBody {
            return L_FunctionBody_Delegating(block)
        }
    }
}

class L_NamespaceMember_Function(
    qualifiedName: R_QualifiedName,
    val function: L_Function,
    val deprecated: C_Deprecated?,
): L_NamespaceMember(qualifiedName) {
    override fun strCode(): String {
        val parts = listOfNotNull(
            if (deprecated == null) null else L_InternalUtils.deprecatedStrCode(deprecated),
            function.strCode(qualifiedName),
        )
        return parts.joinToString(" ")
    }
}

class L_NamespaceMember_SpecialFunction(
    qualifiedName: R_QualifiedName,
    val function: C_GlobalFunction,
): L_NamespaceMember(qualifiedName) {
    override fun strCode() = "special function ${qualifiedName.str()}()"
}

class L_TypeDefMember_Function(
    val simpleName: R_Name,
    val function: L_Function,
    val isStatic: Boolean,
    val deprecated: C_Deprecated?,
): L_TypeDefMember() {
    override fun strCode(): String {
        val parts = listOfNotNull(
            if (deprecated == null) null else L_InternalUtils.deprecatedStrCode(deprecated),
            if (isStatic) "static" else null,
            function.strCode(R_QualifiedName.of(simpleName)),
        )
        return parts.joinToString(" ")
    }

    fun replaceTypeParams(map: Map<M_TypeParam, M_TypeSet>): L_TypeDefMember_Function {
        val function2 = function.replaceTypeParams(map)
        return if (function2 === function) this else {
            function2.validate()
            L_TypeDefMember_Function(simpleName, function2, isStatic, deprecated)
        }
    }
}

class L_TypeDefMember_SpecialFunction(val simpleName: R_Name, val fn: C_LibMemberFunction): L_TypeDefMember() {
    override fun strCode() = "special function $simpleName(...)"
}
