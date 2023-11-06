/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.lib

import net.postchain.rell.base.compiler.base.core.C_DefinitionPath
import net.postchain.rell.base.compiler.base.core.C_IdeSymbolInfo
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
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.utils.doc.DocSymbol
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
        mem: L_TypeDefMember_Constructor,
        outerTypeParams: List<M_TypeParam>,
        selfType: M_Type,
        specificSelfType: M_Type?,
        naming: C_GlobalFunctionNaming,
        validate: Boolean,
    ): C_LibFuncCase<V_GlobalFunctionCall>? {
        //TODO consider a better solution than converting to a function

        val con = mem.constructor

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

        val function = L_Function(lTypeDef.qualifiedName, header, con.body, con.pure)
        val ideInfo = C_IdeSymbolInfo.direct(IdeSymbolKind.DEF_TYPE, doc = mem.docSymbol)
        return C_LibFuncCaseUtils.makeGlobalCase(naming, function, outerTypeArgTypes, con.deprecated, ideInfo)
    }

    fun makeStaticMembers(lTypeDef: L_TypeDef): C_LibTypeMembers<C_TypeStaticMember> {
        val defPath = C_DefinitionPath("", lTypeDef.qualifiedName.parts.map { it.str })
        val members = lTypeDef.allMembers
        val list = mutableListOf<C_TypeStaticMember>()

        for (m in members.constants) {
            val c = m.constant
            val defName = defPath.subName(c.simpleName)
            val rType = L_TypeUtils.getRTypeNotNull(c.type)
            val ideInfo = C_IdeSymbolInfo.direct(IdeSymbolKind.DEF_CONSTANT, doc = m.docSymbol)
            val prop = C_NamespaceProperty_RtValue(c.value)
            list.add(C_TypeStaticMember.makeProperty(defName, c.simpleName, prop, rType, ideInfo))
        }

        val nsConverter = C_LibNamespaceConverter {
            C_GlobalFunctionNaming.makeTypeMember(lTypeDef.mGenericType.commonType, it.last)
        }

        for (e in members.staticFunctionsByName.entries) {
            val defName = defPath.subName(e.key)
            val cases = e.value.map {
                val c = C_SourceFunctionCase(it.function, it.deprecated, it.docSymbol)
                nsConverter.convertFunctionCase(c)
            }
            val naming = C_GlobalFunctionNaming.makeTypeMember(lTypeDef.mGenericType.commonType, e.key)
            val cFn = C_LibFunctionUtils.makeGlobalFunction(naming, cases)
            val ideInfo = cases.first().ideInfo
            list.add(C_TypeStaticMember.makeFunction(defName, e.key, naming, cFn, ideInfo))
        }

        return C_LibTypeMembers.simple(list.toImmList())
    }

    fun makeValueMembers(lTypeDef: L_TypeDef): C_LibTypeMembers<C_TypeValueMember> {
        val propMembers = lTypeDef.allMembers.properties
            .map { mem ->
                val ideKind = if (mem.property.pure) IdeSymbolKind.MEM_SYS_PROPERTY_PURE else IdeSymbolKind.MEM_SYS_PROPERTY
                val ideInfo = C_IdeSymbolInfo.direct(ideKind, doc = mem.docSymbol)
                val ideName = R_IdeName(mem.simpleName, ideInfo)
                val rResType = L_TypeUtils.getRTypeNotNull(mem.property.type)
                val fn = C_SysFunction.direct(mem.property.body)
                val attr: C_MemberAttr = C_MemberAttr_SysProperty(ideName, rResType, fn)
                C_TypeValueMember_BasicAttr(attr)
            }

        val fnMembers = lTypeDef.allMembers.valueFunctionsByName.entries
            .map { (name, mems) ->
                val cases = mems.map { mem ->
                    val ideInfo = C_IdeSymbolInfo.direct(IdeSymbolKind.DEF_FUNCTION_SYSTEM, doc = mem.docSymbol)
                    C_LibFuncCaseUtils.makeMemberCase(name, mem.function, mem.deprecated, ideInfo)
                }
                val fn = C_LibFunctionUtils.makeMemberFunction(cases)
                val ideInfo = cases.first().ideInfo
                C_TypeValueMember_Function(name, fn, ideInfo)
            }

        val specFnMembers = lTypeDef.allMembers.specialValueFunctions
            .map { mem ->
                val ideInfo = C_IdeSymbolInfo.direct(IdeSymbolKind.DEF_FUNCTION_SYSTEM, doc = mem.docSymbol)
                val cFn = C_SpecialLibMemberFunction(mem.fn, ideInfo)
                C_TypeValueMember_Function(mem.simpleName, cFn, ideInfo)
            }

        val list = (propMembers + fnMembers + specFnMembers).toImmList()
        return C_LibTypeMembers.simple(list)
    }
}

private class C_LibNamespaceConverter(
    private val functionNameGetter: (R_QualifiedName) -> C_GlobalFunctionNaming,
) {
    // Important to not convert same definition more than once. Same definition may be used by multiple members
    // because of aliases.
    private val typeDefMap = mutableMapOf<L_TypeDef, C_LibTypeDef>()

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
                    val libMem = C_LibNamespaceMember.makeStruct(lMember.struct.rStruct, lMember.docSymbol)
                    b.addMember(lMember.simpleName, libMem)
                }
                is L_NamespaceMember_Constant -> {
                    convertMemberConstant(b, lMember)
                }
                is L_NamespaceMember_Property -> {
                    convertMemberProperty(b, lMember)
                }
                is L_NamespaceMember_SpecialProperty -> {
                    val ideInfo = C_IdeSymbolInfo.direct(IdeSymbolKind.MEM_SYS_PROPERTY, doc = lMember.docSymbol)
                    val libMem = C_LibNamespaceMember.makeProperty(lMember.property, ideInfo)
                    b.addMember(lMember.simpleName, libMem)
                }
                is L_NamespaceMember_Function -> {
                    functions.put(lMember.simpleName, lMember)
                }
                is L_NamespaceMember_SpecialFunction -> {
                    val ideInfo = C_IdeSymbolInfo.direct(IdeSymbolKind.DEF_FUNCTION_SYSTEM, doc = lMember.docSymbol)
                    val cFn = C_SpecialLibGlobalFunction(lMember.fn, ideInfo)
                    val libMem = C_LibNamespaceMember.makeFunction(cFn, ideInfo)
                    b.addMember(lMember.simpleName, libMem)
                }
            }
        }

        for ((name, mems) in functions.asMap().entries) {
            val cases = mems.map {
                val c = C_SourceFunctionCase(it.function, it.deprecated, it.docSymbol)
                convertFunctionCase(c)
            }
            val qualifiedName = b.basePath.qualifiedName(name)
            val naming = functionNameGetter(qualifiedName)
            val fn = C_LibFunctionUtils.makeGlobalFunction(naming, cases)
            val ideInfo = cases.first().ideInfo
            val libMem = C_LibNamespaceMember.makeFunction(fn, ideInfo)
            b.addMember(name, libMem)
        }
    }

    private fun convertMemberNamespace(b: C_LibNamespace.Maker, lMember: L_NamespaceMember_Namespace) {
        b.addNamespace(lMember.simpleName, lMember.docSymbol) { subB ->
            convertMembers(subB, lMember.namespace)
        }
    }

    private fun convertMemberType(b: C_LibNamespace.Maker, lMember: L_NamespaceMember_Type) {
        val libTypeDef = typeDefMap.computeIfAbsent(lMember.typeDef) {
            convertTypeDef(it)
        }

        if (!libTypeDef.lTypeDef.hidden) {
            val ideInfo = C_IdeSymbolInfo.direct(kind = IdeSymbolKind.DEF_TYPE, doc = libTypeDef.lTypeDef.docSymbol)
            val libMem = C_LibNamespaceMember.makeType(libTypeDef, ideInfo, deprecated = lMember.deprecated)
            b.addMember(lMember.simpleName, libMem)
        }
    }

    private fun convertMemberConstant(b: C_LibNamespace.Maker, lMember: L_NamespaceMember_Constant) {
        val value = lMember.constant.value
        val ideInfo = C_IdeSymbolInfo.direct(IdeSymbolKind.DEF_CONSTANT, doc = lMember.docSymbol)
        val prop = C_NamespaceProperty_RtValue(value)
        val libMem = C_LibNamespaceMember.makeProperty(prop, ideInfo)
        b.addMember(lMember.simpleName, libMem)
    }

    private fun convertMemberProperty(b: C_LibNamespace.Maker, member: L_NamespaceMember_Property) {
        val rType = L_TypeUtils.getRTypeNotNull(member.property.type)
        val ideKind = if (member.property.pure) IdeSymbolKind.MEM_SYS_PROPERTY_PURE else IdeSymbolKind.MEM_SYS_PROPERTY
        val ideInfo = C_IdeSymbolInfo.direct(ideKind, doc = member.docSymbol)
        val prop = C_NamespaceProperty_SysFunction(rType, member.property.fn)
        val libMem = C_LibNamespaceMember.makeProperty(prop, ideInfo)
        b.addMember(member.simpleName, libMem)
    }

    private fun convertTypeDef(lTypeDef: L_TypeDef): C_LibTypeDef {
        val rawConstructor = C_LibAdapter.makeRawConstructor(lTypeDef)
        val staticMembers = C_LibAdapter.makeStaticMembers(lTypeDef)
        val valueMembers = C_LibAdapter.makeValueMembers(lTypeDef)
        return C_LibTypeDef(lTypeDef.qualifiedName.str(), lTypeDef, rawConstructor, staticMembers, valueMembers)
    }

    fun convertFunctionCase(c: C_SourceFunctionCase): C_LibFuncCase<V_GlobalFunctionCall> {
        val naming = functionNameGetter(c.function.qualifiedName)
        val ideInfo = C_IdeSymbolInfo.direct(IdeSymbolKind.DEF_FUNCTION_SYSTEM, doc = c.doc)
        val case = C_LibFuncCaseUtils.makeGlobalCase(naming, c.function, immMapOf(), null, ideInfo)
        return if (c.deprecated == null) case else C_DeprecatedLibFuncCase(case, c.deprecated)
    }
}

private class C_SourceFunctionCase(
    val function: L_Function,
    val deprecated: C_Deprecated?,
    val doc: DocSymbol,
)
