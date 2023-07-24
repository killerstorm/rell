/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.utils.C_RNamePath
import net.postchain.rell.base.lmodel.L_Module
import net.postchain.rell.base.lmodel.L_TypeDef
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.mtype.M_Types
import net.postchain.rell.base.utils.*

class Ld_DeclareTables(private val moduleName: R_ModuleName) {
    private val typeDefs = mutableMapOf<R_QualifiedName, Ld_TypeDef.Declaration>()
    private val mTypes = mutableMapOf<R_QualifiedName, M_Type>()
    private var finished = false

    fun declareType(qualifiedName: R_QualifiedName, typeDef: Ld_TypeDef.Declaration) {
        checkName(qualifiedName)
        typeDefs[qualifiedName] = typeDef
    }

    fun declareType(qualifiedName: R_QualifiedName, mType: M_Type) {
        checkName(qualifiedName)
        mTypes[qualifiedName] = mType
    }

    private fun checkName(qualifiedName: R_QualifiedName) {
        check(qualifiedName !in typeDefs) { "Name conflict: $qualifiedName" }
        check(qualifiedName !in mTypes) { "Name conflict: $qualifiedName" }
    }

    fun finish(imports: Map<R_ModuleName, L_Module>): Ld_NamespaceFinishContext {
        check(!finished)
        finished = true

        val tables = Ld_NamespaceFinishTables(
            moduleName,
            PREDEF_TYPES,
            imports = imports,
            typeDefs = typeDefs.toImmMap(),
            mTypes = mTypes.toImmMap(),
        )

        return Ld_NamespaceFinishContext(parent = null, tables = tables, currentType = null)
    }

    companion object {
        private val PREDEF_TYPES: Map<String, M_Type> = immMapOf(
            "anything" to M_Types.ANYTHING,
            "nothing" to M_Types.NOTHING,
            "any" to M_Types.ANY,
            "null" to M_Types.NULL,
        )
    }
}

class Ld_DeclareContext(private val tables: Ld_DeclareTables, val namePath: C_RNamePath) {
    fun getQualifiedName(simpleName: R_Name): R_QualifiedName {
        return namePath.qualifiedName(simpleName)
    }

    fun nestedNamespaceContext(simpleName: R_Name): Ld_DeclareContext {
        val subPath = namePath.append(simpleName)
        return Ld_DeclareContext(tables, subPath)
    }

    fun declareType(qualifiedName: R_QualifiedName, typeDef: Ld_TypeDef.Declaration) {
        tables.declareType(qualifiedName, typeDef)
    }

    fun declareType(qualifiedName: R_QualifiedName, mType: M_Type) {
        tables.declareType(qualifiedName, mType)
    }

    fun finish(imports: Map<R_ModuleName, L_Module>): Ld_NamespaceFinishContext {
        check(namePath.parts.isEmpty()) // Allowed only on root context.
        return tables.finish(imports)
    }
}

class Ld_NamespaceFinishTables(
    val moduleName: R_ModuleName,
    val predefTypes: Map<String, M_Type>,
    val imports: Map<R_ModuleName, L_Module>,
    val typeDefs: Map<R_QualifiedName, Ld_TypeDef.Declaration>,
    val mTypes: Map<R_QualifiedName, M_Type>,
)

class Ld_NamespaceFinishContext(
    private val parent: Ld_NamespaceFinishContext?,
    private val tables: Ld_NamespaceFinishTables,
    private val currentType: R_QualifiedName?,
) {
    val moduleName = tables.moduleName
    val typeCtx = Ld_TypeFinishContext(this, typeParams = immMapOf())

    fun getType(fullName: Ld_FullName, errPos: Exception? = null): M_Type {
        if (fullName.moduleName == null) {
            var mType = if (fullName.qName.size() != 1) null else tables.predefTypes[fullName.qName.last.str]
            if (mType == null) mType = tables.mTypes[fullName.qName]
            if (mType != null) {
                return mType
            }
        }

        val lTypeDef = getTypeDef(fullName, errPos)
        return lTypeDef.getMType()
    }

    fun getTypeDef(fullName: Ld_FullName, errPos: Exception? = null): L_TypeDef {
        val typeDef = getTypeDefOrNull(fullName)
        typeDef ?: throw IllegalStateException("Type not found: $fullName", errPos)
        return typeDef
    }

    private fun getTypeDefOrNull(fullName: Ld_FullName): L_TypeDef? {
        if (fullName.moduleName != null) {
            val mod = tables.imports[fullName.moduleName]
            val modDef = mod?.getTypeDefOrNull(fullName.qName)
            return modDef
        }

        val modDefs = tables.imports.values.mapNotNull { it.getTypeDefOrNull(fullName.qName) }
        val ldDef = tables.typeDefs[fullName.qName]
        val lDef = ldDef?.getTypeDef(this)

        val allDefs = modDefs + listOfNotNull(lDef)
        Ld_Exception.check(allDefs.size <= 1) {
            val mods = allDefs.map { it.fullName.moduleName }.sorted()
            "type_ambiguous:${fullName.qName}:${mods.joinToString(",")}" to
                    "Type name ${fullName.qName} is ambiguous, defined in modules: ${mods.joinToString()}"
        }

        return allDefs.singleOrNull()
    }

    fun pushType(typeDef: R_QualifiedName): Ld_NamespaceFinishContext {
        return Ld_NamespaceFinishContext(parent = this, tables = tables, currentType = typeDef)
    }

    fun getTypeStack(): List<R_QualifiedName> {
        return CommonUtils.chainToList(this) { it.parent }
            .mapNotNull { it.currentType }
            .reversed()
            .toImmList()
    }
}

class Ld_TypeFinishContext(
    val defCtx: Ld_NamespaceFinishContext,
    private val typeParams: Map<R_Name, M_Type>,
) {
    fun subCtx(typeParams: Map<R_Name, M_Type>): Ld_TypeFinishContext {
        return if (typeParams.isEmpty()) this else {
            val resTypeParams = this.typeParams.unionNoConflicts(typeParams)
            Ld_TypeFinishContext(defCtx, typeParams = resTypeParams)
        }
    }

    fun getType(fullName: Ld_FullName, errPos: Exception? = null): M_Type {
        if (fullName.moduleName == null && fullName.qName.size() == 1) {
            val mType = typeParams[fullName.qName.last]
            if (mType != null) {
                return mType
            }
        }
        return defCtx.getType(fullName, errPos)
    }

    fun getTypeDef(fullName: Ld_FullName, errPos: Exception? = null): L_TypeDef {
        return defCtx.getTypeDef(fullName, errPos)
    }
}
