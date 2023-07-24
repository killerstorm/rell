/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel

import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.model.*
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.ide.IdeSymbolInfo
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.mapNotNullAllOrNull

object L_TypeUtils {
    fun makeMType(rType: R_Type, parent: M_GenericTypeParent? = null): M_Type {
        val genType = makeMGenericType(rType, parent)
        return genType.getType(immListOf())
    }

    fun makeMGenericType(rType: R_Type, parent: M_GenericTypeParent? = null): M_GenericType {
        check(rType != R_NullType)
        check(rType !is R_NullableType) { rType }
        check(rType !is R_FunctionType) { rType }
        check(rType !is R_CollectionType) { rType }
        check(rType !is R_MapType) { rType }
        check(rType !is R_TupleType) { rType }
        check(rType !is R_VirtualType) { rType }

        val addon = C_MGenericTypeAddon_Simple(rType)
        return M_GenericType.make(rType.name, immListOf(), parent, addon)
    }

    fun makeMGenericType(
        name: String,
        params: List<M_TypeParam>,
        parent: M_GenericTypeParent?,
        rTypeFactory: L_TypeDefRTypeFactory?,
        strCodeStrategy: L_TypeDefStrCodeStrategy?,
        supertypeStrategy: L_TypeDefSupertypeStrategy,
    ): M_GenericType {
        val addon = C_MGenericTypeAddon_LTypeDef(
            rTypeFactory = rTypeFactory,
            strCodeStrategy = strCodeStrategy,
            supertypeStrategy = supertypeStrategy,
        )
        return M_GenericType.make(name, params, parent = parent, addon = addon)
    }

    fun getRType(mType: M_Type): R_Type? {
        return when (mType) {
            M_Types.ANYTHING -> null
            M_Types.NOTHING -> null
            M_Types.ANY -> null
            M_Types.NULL -> R_NullType
            is M_Type_Param -> null
            is M_Type_Nullable -> getRTypeForNullable(mType)
            is M_Type_Function -> getRTypeForFunction(mType)
            is M_Type_Tuple -> getRTypeForTuple(mType)
            is M_Type_Generic -> getRTypeForGeneric(mType)
            else -> null
        }
    }

    private fun getRTypeForNullable(mType: M_Type_Nullable): R_Type? {
        val rValueType = getRType(mType.valueType)
        return if (rValueType == null || rValueType == R_UnitType) null else C_Types.toNullable(rValueType)
    }

    private fun getRTypeForFunction(mType: M_Type_Function): R_Type? {
        val rResult = getRType(mType.resultType)
        val rParams = if (rResult == null) null else mType.paramTypes.mapNotNullAllOrNull { getRType(it) }
        return if (rResult == null || rParams == null) null else R_FunctionType(rParams, rResult)
    }

    private fun getRTypeForTuple(mType: M_Type_Tuple): R_TupleType? {
        val rFields = mType.fieldTypes.indices.mapNotNullAllOrNull { i ->
            val rFieldType = getRType(mType.fieldTypes[i])
            if (rFieldType == null) null else {
                val mName = mType.fieldNames[i].value
                val rName = if (mName == null) null else R_IdeName(R_Name.of(mName), IdeSymbolInfo.MEM_TUPLE_ATTR)
                R_TupleField(rName, rFieldType)
            }
        }
        return if (rFields == null) null else R_TupleType(rFields)
    }

    private fun getRTypeForGeneric(mType: M_Type_Generic): R_Type? {
        val addon = getTypeAddon(mType)
        val mArgs = mType.typeArgs.mapNotNullAllOrNull { it.getExactType() }
        return if (mArgs == null) null else addon.getRType(mArgs)
    }

    fun getExpectedType(typeParams: List<M_TypeParam>, mPatternType: M_Type, hint: C_TypeHint): M_Type? {
        val resTypes = hint.mTypes.mapNotNull { matchExpectedType(typeParams, mPatternType, it) }
        val resType = resTypes.singleOrNull()
        return resType
    }

    private fun matchExpectedType(typeParams: List<M_TypeParam>, mPatternType: M_Type, mHintType: M_Type): M_Type? {
        val resolver = M_TypeParamsResolver(typeParams)
        val match = resolver.matchTypeParamsOut(mPatternType, mHintType)
        if (!match) {
            return null
        }

        val typeArgs = resolver.resolve()
        typeArgs ?: return null

        val typeSets = typeArgs.mapValues { M_TypeSets.one(it.value) }
        val typeRep = mPatternType.replaceParams(typeSets, false)
        return typeRep.type()
    }

    fun getTypeAdapter(conversion: M_Conversion): C_TypeAdapter? {
        return when (conversion) {
            M_Conversion_Direct -> C_TypeAdapter_Direct
            is M_Conversion_Nullable -> {
                val subAdapter = getTypeAdapter(conversion.valueConversion)
                val resultType = getRType(conversion.resultType)
                if (subAdapter == null || resultType == null) null else C_TypeAdapter_Nullable(resultType, subAdapter)
            }
            is M_Conversion_Generic -> {
                (conversion as M_Conversion_CTypeAdapter).adapter
            }
        }
    }

    private fun getTypeAddon(mType: M_Type_Generic): C_MGenericTypeAddon {
        val addon = mType.genericType.addon
        check(addon is C_MGenericTypeAddon) { "$mType ${addon.javaClass.canonicalName}" }
        return addon
    }
}

private class M_Conversion_CTypeAdapter(val adapter: C_TypeAdapter): M_Conversion_Generic()

private sealed class C_MGenericTypeAddon: M_GenericTypeAddon() {
    abstract fun getRType(args: List<M_Type>): R_Type?
}

private class C_MGenericTypeAddon_Simple(private val rType: R_Type): C_MGenericTypeAddon() {
    override fun getRType(args: List<M_Type>): R_Type {
        checkEquals(args.size, 0)
        return rType
    }

    override fun getConversion(sourceType: M_Type): M_Conversion_Generic? {
        val rSourceType = L_TypeUtils.getRType(sourceType)
        rSourceType ?: return null

        val adapter = rType.getTypeAdapter(rSourceType)
        adapter ?: return null

        return M_Conversion_CTypeAdapter(adapter)
    }
}

private class C_MGenericTypeAddon_LTypeDef(
    private val rTypeFactory: L_TypeDefRTypeFactory?,
    private val strCodeStrategy: L_TypeDefStrCodeStrategy?,
    private val supertypeStrategy: L_TypeDefSupertypeStrategy,
): C_MGenericTypeAddon() {
    override fun getRType(args: List<M_Type>): R_Type? {
        rTypeFactory ?: return null
        val rArgs = args.mapNotNullAllOrNull { L_TypeUtils.getRType(it) }
        rArgs ?: return null
        return rTypeFactory.getRType(rArgs)
    }

    override fun strCode(typeName: String, args: List<M_TypeSet>): String {
        return strCodeStrategy?.strCode(typeName, args) ?: super.strCode(typeName, args)
    }

    override fun isSpecialSuperTypeOf(type: M_Type): Boolean {
        return supertypeStrategy.isSpecialSuperTypeOf(type)
    }

    override fun isPossibleSpecialCompositeSuperTypeOf(type: M_Type_Composite): Boolean {
        return supertypeStrategy.isPossibleSpecialCompositeSuperTypeOf(type)
    }
}
