/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.lib

import net.postchain.rell.base.compiler.base.core.C_DefinitionPath
import net.postchain.rell.base.compiler.base.def.C_GlobalFunction
import net.postchain.rell.base.compiler.base.expr.*
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty_RtValue
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty_SysFunction
import net.postchain.rell.base.compiler.base.utils.C_RNamePath
import net.postchain.rell.base.compiler.vexpr.V_GlobalFunctionCall
import net.postchain.rell.base.lmodel.*
import net.postchain.rell.base.model.R_IdeName
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.utils.ide.IdeSymbolInfo
import net.postchain.rell.base.utils.ide.IdeSymbolKind
import net.postchain.rell.base.utils.immMapOf
import net.postchain.rell.base.utils.mutableMultimapOf
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap

object C_LibAdapter {
    fun makeModule(lModule: L_Module): C_LibModule {
        val converter = C_LibNamespaceConverter {
            C_GlobalFunctionNaming.makeQualifiedName(it)
        }

        val namespace = converter.convertNamespace(lModule.namespace)
        val typeDefs = converter.getTypeDefs()

        val extensionTypes = typeDefs
            .mapNotNull { makeExtensionType(it) }
            .toImmList()

        return C_LibModule(lModule, typeDefs, namespace, extensionTypes)
    }

    private fun makeExtensionType(typeDef: C_LibTypeDef): C_LibExtensionType? {
        return if (!typeDef.lTypeDef.extension) null else {
            val target = typeDef.mGenericType.params[0].bounds
            val staticMembers = makeStaticMembers(typeDef.lTypeDef)
            val valueMembers = makeValueMembers(typeDef.lTypeDef)
            C_LibExtensionType(typeDef, target, staticMembers, valueMembers)
        }
    }

    fun makeConstructor(lType: L_Type): C_GlobalFunction? {
        val mSpecificType = lType.mType
        val naming = C_GlobalFunctionNaming.makeConstructor(mSpecificType)
        return constructorsToFunction(lType.typeDef, listOf(), mSpecificType, naming)
    }

    fun makeRawConstructor(lTypeDef: L_TypeDef): C_GlobalFunction? {
        val mGenericType = lTypeDef.mGenericType
        val naming = C_GlobalFunctionNaming.makeQualifiedName(lTypeDef.qualifiedName)
        return constructorsToFunction(lTypeDef, mGenericType.params, null, naming)
    }

    private fun constructorsToFunction(
        lTypeDef: L_TypeDef,
        typeParams: List<M_TypeParam>,
        mSpecificType: M_Type?,
        naming: C_GlobalFunctionNaming,
    ): C_LibGlobalFunction? {
        val mCommonType = lTypeDef.mGenericType.commonType
        val cons = lTypeDef.members.constructors

        var cases = cons.mapNotNull { con ->
            constructorToCase(lTypeDef, con, typeParams, mCommonType, mSpecificType, naming, validate = true)
        }

        if (cases.isEmpty()) {
            cases = cons.mapNotNull { con ->
                constructorToCase(lTypeDef, con, typeParams, mCommonType, mSpecificType, naming, validate = false)
            }
        }

        return if (cases.isEmpty()) null else {
            C_LibFunctionUtils.makeGlobalFunction(naming, cases)
        }
    }

    private fun constructorToCase(
        lTypeDef: L_TypeDef,
        con: L_Constructor,
        outerTypeParams: List<M_TypeParam>,
        selfType: M_Type,
        specificSelfType: M_Type?,
        naming: C_GlobalFunctionNaming,
        validate: Boolean,
    ): C_LibFuncCase<V_GlobalFunctionCall>? {
        //TODO consider a better solution than converting to a function

        val mHeader = M_FunctionHeader(
            typeParams = (outerTypeParams + con.header.typeParams).toImmList(),
            resultType = selfType,
            params = con.header.params.map { it.mParam }.toImmList(),
        )

        var header = L_FunctionHeader(mHeader, params = con.header.params)

        val outerTypeArgs = if (specificSelfType == null) immMapOf() else M_TypeUtils.getTypeArgs(specificSelfType)

        if (outerTypeArgs.isNotEmpty()) {
            header = header.replaceTypeParams(outerTypeArgs)
            if (validate) {
                try {
                    header.validate()
                } catch (e: M_TypeException) {
                    return null
                }
            }
        }

        val outerTypeArgTypes = outerTypeArgs
            .mapKeys { R_Name.of(it.key.name) }
            .mapValues { it.value.captureType() }
            .toImmMap()

        val function = L_Function(lTypeDef.qualifiedName, header, con.body)
        return C_LibFuncCaseUtils.makeGlobalCase(naming, function, outerTypeArgTypes, con.deprecated)
    }

    fun makeStaticMembers(lTypeDef: L_TypeDef): C_LibTypeMembers<C_TypeStaticMember> {
        val defPath = C_DefinitionPath("", lTypeDef.qualifiedName.parts.map { it.str })
        val members = lTypeDef.allMembers
        val list = mutableListOf<C_TypeStaticMember>()

        for (c in members.constants) {
            val defName = defPath.subName(c.simpleName)
            val rType = C_LibAdapterInternals.getRType(c.type)
            val value = c.value.getValue(rType)
            val prop = C_NamespaceProperty_RtValue(IdeSymbolInfo.DEF_CONSTANT, value)
            list.add(C_TypeStaticMember.makeProperty(defName, c.simpleName, prop, rType))
        }

        val nsConverter = C_LibNamespaceConverter {
            C_GlobalFunctionNaming.makeTypeMember(lTypeDef.mGenericType.commonType, it.last)
        }

        for (e in members.staticFunctionsByName.entries) {
            val defName = defPath.subName(e.key)
            val cases = e.value.map { it.function to it.deprecated }
            val naming = C_GlobalFunctionNaming.makeTypeMember(lTypeDef.mGenericType.commonType, e.key)
            val cFn = nsConverter.convertFunction(naming, cases)
            list.add(C_TypeStaticMember.makeFunction(defName, e.key, naming, cFn))
        }

        return C_LibTypeMembers.simple(list.toImmList())
    }

    fun makeValueMembers(lTypeDef: L_TypeDef): C_LibTypeMembers<C_TypeValueMember> {
        val propMembers = lTypeDef.allMembers.properties
            .map { mem ->
                val ideName = C_LibUtils.ideName(mem.simpleName, IdeSymbolKind.MEM_STRUCT_ATTR)
                val rResType = C_LibAdapterInternals.getRType(mem.property.type)
                val attr: C_MemberAttr = C_MemberAttr_SysProperty(ideName, rResType, mem.property.fn)
                C_TypeValueMember_BasicAttr(attr)
            }

        val fnMembers = lTypeDef.allMembers.valueFunctionsByName.entries
            .map { (name, mems) ->
                val cases = mems.map { mem ->
                    C_LibFuncCaseUtils.makeMemberCase(name, mem.function, mem.deprecated)
                }
                val fn = C_LibFunctionUtils.makeMemberFunction(cases)
                val ideName = R_IdeName(name, fn.ideInfo)
                C_TypeValueMember_Function(ideName, fn)
            }

        val specFnMembers = lTypeDef.allMembers.specialValueFunctions
            .map { mem ->
                val ideName = R_IdeName(mem.simpleName, mem.fn.ideInfo)
                C_TypeValueMember_Function(ideName, mem.fn)
            }

        val list = (propMembers + fnMembers + specFnMembers).toImmList()
        return C_LibTypeMembers.simple(list)
    }
}

private object C_LibAdapterInternals {
    fun getRType(mType: M_Type): R_Type {
        val rType = L_TypeUtils.getRType(mType)
        checkNotNull(rType) { "No R_Type: ${mType.strCode()}" }
        return rType
    }
}

private class C_LibNamespaceConverter(
    private val functionNameGetter: (R_QualifiedName) -> C_GlobalFunctionNaming,
) {
    // Important to not convert same definition more than once. Same definition may be used by multiple members
    // because of aliases.
    private val typeDefMap = mutableMapOf<L_TypeDef, C_LibTypeDef>()
    private val functionMap = mutableMapOf<L_Function, C_LibFuncCase<V_GlobalFunctionCall>>()

    fun convertNamespace(lNs: L_Namespace): C_LibNamespace {
        val b = C_LibNamespace.Builder(C_RNamePath.EMPTY)
        convertMembers(b, lNs)
        return b.build()
    }

    fun getTypeDefs(): List<C_LibTypeDef> = typeDefMap.values.toImmList()

    private fun convertMembers(b: C_LibNamespace.Maker, lNs: L_Namespace) {
        val functions = mutableMultimapOf<R_Name, L_NamespaceMember_Function>()

        for (lMember in lNs.members) {
            when (lMember) {
                is L_NamespaceMember_Namespace -> {
                    convertMemberNamespace(b, lMember)
                }
                is L_NamespaceMember_Type -> {
                    convertMemberType(b, lMember)
                }
                is L_NamespaceMember_Struct -> {
                    val libMem = C_LibNamespaceMember.makeStruct(lMember.struct.rStruct)
                    b.addMember(lMember.simpleName, libMem)
                }
                is L_NamespaceMember_Constant -> {
                    convertMemberConstant(b, lMember)
                }
                is L_NamespaceMember_Property -> {
                    convertMemberProperty(b, lMember)
                }
                is L_NamespaceMember_SpecialProperty -> {
                    val libMem = C_LibNamespaceMember.makeProperty(lMember.property)
                    b.addMember(lMember.simpleName, libMem)
                }
                is L_NamespaceMember_Function -> {
                    functions.put(lMember.simpleName, lMember)
                }
                is L_NamespaceMember_SpecialFunction -> {
                    val libMem = C_LibNamespaceMember.makeFunction(lMember.function)
                    b.addMember(lMember.simpleName, libMem)
                }
            }
        }

        for ((name, mems) in functions.asMap().entries) {
            val cases = mems.map { it.function to it.deprecated }
            val qualifiedName = b.basePath.qualifiedName(name)
            val naming = functionNameGetter(qualifiedName)
            val fn = convertFunction(naming, cases)
            val libMem = C_LibNamespaceMember.makeFunction(fn)
            b.addMember(name, libMem)
        }
    }

    private fun convertMemberNamespace(b: C_LibNamespace.Maker, lMember: L_NamespaceMember_Namespace) {
        b.addNamespace(lMember.simpleName) { subB ->
            convertMembers(subB, lMember.namespace)
        }
    }

    private fun convertMemberType(b: C_LibNamespace.Maker, lMember: L_NamespaceMember_Type) {
        val libTypeDef = typeDefMap.computeIfAbsent(lMember.typeDef) {
            convertTypeDef(it)
        }

        if (!libTypeDef.lTypeDef.hidden) {
            val libMem = C_LibNamespaceMember.makeType(libTypeDef, deprecated = lMember.deprecated)
            b.addMember(lMember.simpleName, libMem)
        }
    }

    private fun convertMemberConstant(b: C_LibNamespace.Maker, lMember: L_NamespaceMember_Constant) {
        val rType = C_LibAdapterInternals.getRType(lMember.constant.type)
        val value = lMember.constant.value.getValue(rType)
        val prop = C_NamespaceProperty_RtValue(IdeSymbolInfo.DEF_CONSTANT, value)
        val libMem = C_LibNamespaceMember.makeProperty(prop)
        b.addMember(lMember.simpleName, libMem)
    }

    private fun convertMemberProperty(b: C_LibNamespace.Maker, member: L_NamespaceMember_Property) {
        val rType = C_LibAdapterInternals.getRType(member.property.type)
        val prop = C_NamespaceProperty_SysFunction(member.property.ideInfo, rType, member.property.fn)
        val libMem = C_LibNamespaceMember.makeProperty(prop)
        b.addMember(member.simpleName, libMem)
    }

    private fun convertTypeDef(lTypeDef: L_TypeDef): C_LibTypeDef {
        val rawConstructor = C_LibAdapter.makeRawConstructor(lTypeDef)
        val staticMembers = C_LibAdapter.makeStaticMembers(lTypeDef)
        val valueMembers = C_LibAdapter.makeValueMembers(lTypeDef)
        return C_LibTypeDef(lTypeDef.qualifiedName.str(), lTypeDef, rawConstructor, staticMembers, valueMembers)
    }

    fun convertFunction(
        naming: C_GlobalFunctionNaming,
        lFunctions: List<Pair<L_Function, C_Deprecated?>>,
    ): C_LibGlobalFunction {
        val cases = lFunctions.map { (lFn, deprecated) ->
            convertFunctionCase(lFn, deprecated)
        }
        return C_LibFunctionUtils.makeGlobalFunction(naming, cases)
    }

    private fun convertFunctionCase(
        function: L_Function,
        deprecated: C_Deprecated?,
    ): C_LibFuncCase<V_GlobalFunctionCall> {
        val case = functionMap.computeIfAbsent(function) {
            val naming = functionNameGetter(function.qualifiedName)
            C_LibFuncCaseUtils.makeGlobalCase(naming, function, immMapOf(), null)
        }
        return if (deprecated == null) case else C_DeprecatedLibFuncCase(case, deprecated)
    }
}
