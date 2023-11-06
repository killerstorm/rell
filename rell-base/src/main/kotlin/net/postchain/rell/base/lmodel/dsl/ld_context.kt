/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.utils.C_RNamePath
import net.postchain.rell.base.lmodel.L_FullName
import net.postchain.rell.base.lmodel.L_Module
import net.postchain.rell.base.lmodel.L_TypeDef
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.mtype.M_Types
import net.postchain.rell.base.utils.*

class Ld_DeclareTables(val moduleName: R_ModuleName) {
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
    fun getFullName(simpleName: R_Name): L_FullName {
        val qName = namePath.qualifiedName(simpleName)
        return L_FullName(tables.moduleName, qName)
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
    private val currentType: L_FullName?,
) {
    val moduleName = tables.moduleName
    val typeCtx = Ld_TypeFinishContext(this, typeParams = immMapOf())

    fun getMType(fullName: Ld_FullName, errPos: Exception? = null): M_Type {
        val mType = getMTypeOrNull(fullName)
        mType ?: throw IllegalStateException("Type not found: $fullName", errPos)
        return mType
    }

    private fun getMTypeOrNull(fullName: Ld_FullName): M_Type? {
        if (fullName.moduleName != null) {
            val mod = tables.imports[fullName.moduleName]
            val mType = mod?.getMTypeOrNull(fullName.qName)
            return mType
        }

        val found = mutableListOf<Pair<M_Type, R_ModuleName?>>()

        if (fullName.qName.size() == 1) {
            val predef = tables.predefTypes[fullName.qName.last.str]
            if (predef != null) {
                found.add(predef to null)
            }
        }

        val localMType = tables.mTypes[fullName.qName]
        if (localMType != null) {
            found.add(localMType to moduleName)
        }

        val localTypeDef = tables.typeDefs[fullName.qName]?.getTypeDef(this)
        if (localTypeDef != null) {
            found.add(localTypeDef.getMType() to moduleName)
        }

        val imported = tables.imports.values
            .mapNotNull {
                val mType = it.getMTypeOrNull(fullName.qName)
                if (mType == null) null else (mType to it.moduleName)
            }
        found.addAll(imported)

        checkTypeAmbiguity(fullName.qName, found) { it.second?.str() ?: "<built-in>" }

        return found.singleOrNull()?.first
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

        val importedDefs = tables.imports.values.mapNotNull { it.getTypeDefOrNull(fullName.qName) }
        val ldDef = tables.typeDefs[fullName.qName]
        val lDef = ldDef?.getTypeDef(this)

        val allDefs = importedDefs + listOfNotNull(lDef)
        checkTypeAmbiguity(fullName.qName, allDefs) { it.fullName.moduleName.str() }

        return allDefs.singleOrNull()
    }

    private fun <T> checkTypeAmbiguity(qualifiedName: R_QualifiedName, types: List<T>, moduleGetter: (T) -> String) {
        Ld_Exception.check(types.size <= 1) {
            val mods = types.map { moduleGetter(it) }.sorted()
            "type_ambiguous:$qualifiedName:${mods.joinToString(",")}" to
                    "Type name $qualifiedName is ambiguous, defined in modules: ${mods.joinToString()}"
        }
    }

    fun pushType(fullName: L_FullName): Ld_NamespaceFinishContext {
        return Ld_NamespaceFinishContext(parent = this, tables = tables, currentType = fullName)
    }

    fun getTypeStack(): List<L_FullName> {
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
        return defCtx.getMType(fullName, errPos)
    }

    fun getTypeDef(fullName: Ld_FullName, errPos: Exception? = null): L_TypeDef {
        return defCtx.getTypeDef(fullName, errPos)
    }
}
