/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.utils.C_RFullNamePath
import net.postchain.rell.base.lmodel.*
import net.postchain.rell.base.model.R_FullName
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_QualifiedName
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.mtype.M_Types
import net.postchain.rell.base.utils.*
import net.postchain.rell.base.utils.futures.FcCycleException
import net.postchain.rell.base.utils.futures.FcExecutor
import net.postchain.rell.base.utils.futures.FcFuture

class Ld_ModuleContext(
    val moduleName: R_ModuleName,
    val fcExec: FcExecutor,
    val finishCtxFuture: FcFuture<Ld_NamespaceFinishContext>,
) {
    private val members = mutableMultimapOf<R_QualifiedName, FcFuture<List<L_NamespaceMember>>>()
    private var finished = false

    fun declareMember(qualifiedName: R_QualifiedName, future: FcFuture<List<L_NamespaceMember>>) {
        members.put(qualifiedName, future)
    }

    fun finish(imports: Map<R_ModuleName, L_Module>): Ld_NamespaceFinishContext {
        check(!finished)
        finished = true

        val resMembers = members.asMap().mapValues { (_, futures) ->
            fcExec.future().after(futures.toList()).computeOnDemand(true).compute { lists ->
                lists.flatten()
            }
        }

        val tables = Ld_NamespaceFinishTables(
            moduleName,
            PREDEF_TYPES,
            imports = imports,
            members = resMembers.toImmMap(),
        )

        return Ld_NamespaceFinishContext(fcExec, tables = tables)
    }

    companion object {
        private val PREDEF_TYPES: Map<String, L_AbstractTypeDef> =
                immMapOf(
                    "anything" to M_Types.ANYTHING,
                    "nothing" to M_Types.NOTHING,
                    "any" to M_Types.ANY,
                    "null" to M_Types.NULL,
                )
                .mapValues { L_MTypeDef(it.value) }
                .toImmMap()
    }
}

class Ld_NamespaceContext(
    private val moduleCtx: Ld_ModuleContext,
    val namePath: C_RFullNamePath,
) {
    val fcExec = moduleCtx.fcExec
    val finishCtxFuture = moduleCtx.finishCtxFuture

    fun getFullName(simpleName: R_Name): R_FullName {
        return namePath.fullName(simpleName)
    }

    fun nestedNamespaceContext(simpleName: R_Name): Ld_NamespaceContext {
        val subPath = namePath.append(simpleName)
        return Ld_NamespaceContext(moduleCtx, subPath)
    }

    fun declareMember(qualifiedName: R_QualifiedName, future: FcFuture<List<L_NamespaceMember>>) {
        moduleCtx.declareMember(qualifiedName, future)
    }

    fun finish(imports: Map<R_ModuleName, L_Module>): Ld_NamespaceFinishContext {
        check(namePath.parts.isEmpty()) // Allowed only on root context.
        return moduleCtx.finish(imports)
    }
}

class Ld_NamespaceFinishTables(
    val moduleName: R_ModuleName,
    val predefTypes: Map<String, L_AbstractTypeDef>,
    val imports: Map<R_ModuleName, L_Module>,
    val members: Map<R_QualifiedName, FcFuture<List<L_NamespaceMember>>>,
)

class Ld_NamespaceFinishContext(
    val fcExec: FcExecutor,
    private val tables: Ld_NamespaceFinishTables,
) {
    val moduleName = tables.moduleName
    val typeCtx = Ld_TypeFinishContext(this, typeParams = immMapOf())

    fun getMType(fullName: Ld_FullName, errPos: Exception? = null): M_Type {
        val absTypeDef = getTypeDefOrNull(fullName)
        val mType = absTypeDef?.getMTypeOrNull()
        return mType ?: errTypeNotFound(fullName, errPos)
    }

    fun getTypeDef(fullName: Ld_FullName, errPos: Exception? = null): L_TypeDef {
        val absTypeDef = getTypeDefOrNull(fullName)
        val typeDef = absTypeDef?.getTypeDefOrNull()
        return typeDef ?: errTypeNotFound(fullName, errPos)
    }

    private fun errTypeNotFound(fullName: Ld_FullName, errPos: Exception?): Nothing {
        throw Ld_Exception("type_not_found:$fullName", "Type not found: $fullName", errPos)
    }

    private fun getTypeDefOrNull(fullName: Ld_FullName): L_AbstractTypeDef? {
        if (fullName.moduleName != null) {
            val mod = tables.imports[fullName.moduleName]
            val typeDef = mod?.getAbstractTypeDefOrNull(fullName.qualifiedName)
            return typeDef
        }

        val found = mutableListOf<Pair<L_AbstractTypeDef, R_ModuleName?>>()

        if (fullName.qualifiedName.size() == 1) {
            val predef = tables.predefTypes[fullName.qualifiedName.last.str]
            if (predef != null) {
                found.add(predef to null)
            }
        }

        val localDefF = tables.members[fullName.qualifiedName]
        if (localDefF != null) {
            val localDef = try {
                localDefF.getResult()
            } catch (e: FcCycleException) {
                handleTypeRecursion(fullName.qualifiedName, e)
            }

            val lTypeDef = localDef.singleOrNull()?.getAbstractTypeDefOrNull()
            if (lTypeDef != null) {
                found.add(lTypeDef to moduleName)
            }
        }

        val imported = tables.imports.values
            .mapNotNull {
                val typeDef = it.getAbstractTypeDefOrNull(fullName.qualifiedName)
                if (typeDef == null) null else (typeDef to it.moduleName)
            }
        found.addAll(imported)

        checkTypeAmbiguity(fullName.qualifiedName, found) { it.second?.str() ?: "<built-in>" }

        return found.singleOrNull()?.first
    }

    private fun handleTypeRecursion(qualifiedName: R_QualifiedName, e: FcCycleException): Nothing {
        var cycle = e.nodes.mapNotNull { it.attachment as? R_FullName }

        // Obtaining type cycle from a CfManager is not very straightforward, as not all futures have names
        // (attachments), the named future is not necessarily the first and the last one.
        // Not completely predictable, but fine for such error handling. Make sure first and last names are the same.
        if (cycle.isEmpty()) {
            val fullName = R_FullName(tables.moduleName, qualifiedName)
            cycle = listOf(fullName, fullName)
        } else if (cycle.last() != cycle.first()) {
            cycle = cycle + listOf(cycle.first())
        }

        val names = cycle.map { it.str() }
        val cycleCode = names.joinToString(",")
        val cycleMsg = names.joinToString(", ")
        throw Ld_Exception("type_cycle:$cycleCode", "Type recursion: $cycleMsg")
    }

    private fun <T> checkTypeAmbiguity(qualifiedName: R_QualifiedName, types: List<T>, moduleGetter: (T) -> String) {
        Ld_Exception.check(types.size <= 1) {
            val mods = types.map { moduleGetter(it) }.sorted()
            "type_ambiguous:$qualifiedName:${mods.joinToString(",")}" to
                    "Type name $qualifiedName is ambiguous, defined in modules: ${mods.joinToString()}"
        }
    }

    fun getNamespaceMembers(qualifiedName: R_QualifiedName, errPos: Exception): List<L_NamespaceMember> {
        val future = tables.members[qualifiedName]
        return future?.getResult() ?: immListOf()
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
        if (fullName.moduleName == null && fullName.qualifiedName.size() == 1) {
            val mType = typeParams[fullName.qualifiedName.last]
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
