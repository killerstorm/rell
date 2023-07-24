/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.mtype

import net.postchain.rell.base.utils.*

enum class M_ParamArity(val many: Boolean) {
    ONE(false),
    ZERO_ONE(false),
    ZERO_MANY(true),
    ONE_MANY(true),
}

class M_FunctionParam(
    val name: String?,
    val type: M_Type,
    val arity: M_ParamArity,
    val exact: Boolean,
    val nullable: Boolean,
) {
    override fun toString() = strCode()

    fun strCode(): String {
        var res = type.strCode()
        if (name != null) res = "$name: $res"
        if (arity != M_ParamArity.ONE) res = "@arity($arity) $res"
        if (exact) res = "@exact $res"
        if (nullable) res = "@nullable $res"
        return res
    }

    fun replaceTypeParams(map: Map<M_TypeParam, M_TypeSet>): M_FunctionParam {
        val resType = type.replaceParams(map, true).type()
        return M_FunctionParam(
            name = name,
            type = resType,
            arity = arity,
            exact = exact,
            nullable = nullable,
        )
    }

    fun toSimpleParam(): M_FunctionParam {
        return if (arity == M_ParamArity.ONE) this else M_FunctionParam(
            name = name,
            type = type,
            arity = M_ParamArity.ONE,
            exact = exact,
            nullable = nullable,
        )
    }

    fun validate() {
        type.validate()
    }

    companion object {
        fun make(
            type: M_Type,
            arity: M_ParamArity = M_ParamArity.ONE,
            exact: Boolean = false,
            nullable: Boolean = false,
        ) = M_FunctionParam(
            name = null,
            type = type,
            arity = arity,
            exact = exact,
            nullable = nullable,
        )
    }
}

class M_FunctionHeader(
    val typeParams: List<M_TypeParam>,
    val resultType: M_Type,
    val params: List<M_FunctionParam>,
) {
    init {
        checkTypeParams()
        checkParams()
    }

    private val simpleParams: List<M_FunctionParam> by lazy {
        params.map { it.toSimpleParam() }.toImmList()
    }

    private fun checkTypeParams() {
        val names = typeParams.map { it.name }
        val uniqueNames = names.toSet()
        check(uniqueNames.size == typeParams.size) { "Type parameter names are not unique: $names" }

        // Make sure that a type parameter may depend only on type parameters defined before it.
        val set = mutableSetOf<M_TypeParam>()
        for (typeParam in typeParams.asReversed()) {
            set.add(typeParam)
            val refs = typeParam.bounds.getTypeParams()
            val common = refs.intersect(set)
            check(common.isEmpty()) {
                "Type parameter $typeParam depends on type parameter(s) defined after it: $common"
            }
        }
    }

    private fun checkParams() {
        var prevArity: M_ParamArity = M_ParamArity.ONE

        for (param in params) {
            val arityOk = when (param.arity) {
                M_ParamArity.ONE -> prevArity == M_ParamArity.ONE
                M_ParamArity.ZERO_ONE, M_ParamArity.ZERO_MANY, M_ParamArity.ONE_MANY -> {
                    prevArity == M_ParamArity.ONE || prevArity == M_ParamArity.ZERO_ONE
                }
            }
            check(arityOk) { "Parameter with arity ${param.arity} cannot be added after a parameter with arity $prevArity" }
            prevArity = param.arity
        }
    }

    fun strCode(): String {
        val genStr = if (typeParams.isEmpty()) "" else "<${typeParams.joinToString(",")}>"
        return "$genStr(${params.joinToString(",")}):$resultType"
    }

    override fun toString() = strCode()

    fun validate() {
        typeParams.forEach { it.validate() }
        resultType.validate()
        params.forEach { it.validate() }
    }

    fun replaceTypeParams(map: Map<M_TypeParam, M_TypeSet>): M_FunctionHeader {
        val typeParams2 = typeParams.mapOrSame { it.replaceTypeParams(map) }

        val typeParamsMap = typeParams.indices
            .filter { i -> typeParams[i] !== typeParams2[i] && typeParams[i] !in map }
            .associate { i -> typeParams[i] to M_TypeSets.one(M_Types.param(typeParams2[i])) }
        val fullMap = if (typeParamsMap.isEmpty()) map else (map + typeParamsMap)

        val resultType2 = resultType.replaceParamsOut(fullMap)
        val params2 = params.mapOrSame { it.replaceTypeParams(fullMap) }

        return if (typeParams2 === typeParams && resultType2 === resultType && params2 === params) this else {
            M_FunctionHeader(typeParams2, resultType2, params2)
        }
    }

    fun matchParams(nArgs: Int): M_FunctionParamsMatch? {
        val paramIndexes = matchArgsCount(nArgs)
        paramIndexes ?: return null

        val actualParams = paramIndexes.map { simpleParams[it] }.toImmList()
        return M_FunctionParamsMatch(this, paramIndexes, actualParams)
    }

    private fun matchArgsCount(nArgs: Int): List<Int>? {
        val resParams = mutableListOf<Int>()
        var argsLeft = nArgs

        for ((index, param) in params.withIndex()) {
            when (param.arity) {
                M_ParamArity.ONE -> {
                    if (argsLeft <= 0) return null
                    --argsLeft
                    resParams.add(index)
                }
                M_ParamArity.ZERO_ONE -> {
                    if (argsLeft > 0) {
                        --argsLeft
                        resParams.add(index)
                    }
                }
                M_ParamArity.ZERO_MANY -> {
                    while (argsLeft > 0) {
                        --argsLeft
                        resParams.add(index)
                    }
                }
                M_ParamArity.ONE_MANY -> {
                    if (argsLeft <= 0) return null
                    while (argsLeft > 0) {
                        --argsLeft
                        resParams.add(index)
                    }
                }
            }
        }

        if (argsLeft > 0) {
            return null
        }

        checkEquals(resParams.size, nArgs)
        return resParams.toImmList()
    }
}

class M_FunctionParamsMatch(
    private val header: M_FunctionHeader,
    val paramIndexes: List<Int>,
    val actualParams: List<M_FunctionParam>,
) {
    init {
        checkEquals(paramIndexes.size, actualParams.size)
    }

    fun matchArgs(argTypes: List<M_Type>, expectedResultType: M_Type?): M_FunctionHeaderMatch? {
        checkEquals(argTypes.size, paramIndexes.size)

        val capArgTypes = argTypes.map { it.captureWildcards() }

        val match = if (header.typeParams.isEmpty()) {
            val actualHeader = M_FunctionHeader(immListOf(), header.resultType, actualParams)
            M_FunTypeParamsMatch(immMapOf(), actualHeader)
        } else {
            matchReplaceTypeParams(capArgTypes, expectedResultType)
        }
        match ?: return null

        val conversions = match.actualHeader.params.indices.mapNotNullAllOrNull { i ->
            val param = match.actualHeader.params[i]
            val paramType = param.type
            val argType = capArgTypes[i]

            when {
                param.exact && argType != paramType -> null
                param.nullable && argType !is M_Type_Nullable -> null
                else -> paramType.getConversion(argType)
            }
        }

        conversions ?: return null

        return M_FunctionHeaderMatch(match.typeArgs, match.actualHeader, conversions)
    }

    private fun matchReplaceTypeParams(
        argTypes: List<M_Type>,
        expectedResultType: M_Type?,
    ): M_FunTypeParamsMatch? {
        val resolver = M_TypeParamsResolver(header.typeParams)

        for (i in actualParams.indices) {
            val param = actualParams[i]
            val argType = argTypes[i]
            if (!resolver.matchTypeParamsIn(param.type, argType)) {
                return null
            }
        }

        if (!resolver.allParamsMatched() && expectedResultType != null) {
            resolver.matchTypeParamsOut(header.resultType, expectedResultType)
        }

        val typeArgs = resolver.resolve()
        typeArgs ?: return null

        val typeSets = typeArgs.mapValues {
            M_TypeSets.one(it.value)
        }

        val replacedHeader = header.replaceTypeParams(typeSets)
        try {
            replacedHeader.validate()
        } catch (e: M_TypeException) {
            return null
        }

        val resTypeArgs = typeArgs.mapKeys { it.key.name }

        val fullParams = paramIndexes.map { replacedHeader.params[it] }.toImmList()
        val unresolved = header.typeParams.filter { it !in typeArgs }.toImmList()

        val actualHeader = M_FunctionHeader(
            unresolved,
            replacedHeader.resultType,
            fullParams,
        )

        return M_FunTypeParamsMatch(resTypeArgs, actualHeader)
    }

    private class M_FunTypeParamsMatch(
        val typeArgs: Map<String, M_Type>,
        val actualHeader: M_FunctionHeader,
    )
}

class M_FunctionHeaderMatch(
    val typeArgs: Map<String, M_Type>,
    val actualHeader: M_FunctionHeader,
    val conversions: List<M_Conversion>,
) {
    init {
        checkEquals(conversions.size, actualHeader.params.size)
    }
}
