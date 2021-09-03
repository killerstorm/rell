/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.gtv.Gtv
import net.postchain.rell.compiler.base.utils.C_LateGetter
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.runtime.toGtv
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

class R_DefinitionNames(val module: String, val namespace: String?, val simpleName: String) {
    val qualifiedName = if (namespace == null) simpleName else "$namespace.$simpleName"
    val appLevelName = if (module.isEmpty()) qualifiedName else R_DefinitionId.appLevelName(module, qualifiedName)
    val defId = R_DefinitionId(module, qualifiedName)

    override fun toString() = appLevelName
}

abstract class R_Definition(val names: R_DefinitionNames, val initFrameGetter: C_LateGetter<R_CallFrame>) {
    val simpleName = names.simpleName
    val moduleLevelName = names.qualifiedName
    val appLevelName = names.appLevelName
    val defId = names.defId

    abstract fun toMetaGtv(): Gtv

    final override fun toString() = "${javaClass.simpleName}[$appLevelName]"
}

class R_ExternalChainsRoot
class R_ExternalChainRef(val root: R_ExternalChainsRoot, val name: String, val index: Int)

data class R_AppUid(val id: Long) {
    override fun toString() = "App[$id]"
}

data class R_ContainerUid(val id: Long, val name: String, val app: R_AppUid) {
    override fun toString(): String {
        val params = listOf(id.toString(), name).filter { it.isNotEmpty() }.joinToString(",")
        return "$app/Container[$params]"
    }
}

data class R_FnUid(val id: Long, val name: String, val container: R_ContainerUid) {
    override fun toString() = "$container/Fn[$id,$name]"
}

data class R_FrameBlockUid(val id: Long, val name: String, val fn: R_FnUid) {
    override fun toString() = "$fn/Block[$id,$name]"
}

data class R_AtExprId(val id: Long) {
    fun toRawString() = "$id"
    override fun toString() = "AtExpr[$id]"
}

data class R_AtEntityId(val exprId: R_AtExprId, val id: Long) {
    override fun toString() = "AtEntity[${exprId.toRawString()}:$id]"
}

class R_DefaultValue(val rExpr: R_Expr, val isDbModification: Boolean)

class R_Attribute(
        val index: Int,
        val name: String,
        val type: R_Type,
        val mutable: Boolean,
        val canSetInCreate: Boolean = true,
        val sqlMapping: String = name,
        private val exprGetter: C_LateGetter<R_DefaultValue>?
) {
    val expr: R_Expr? get() = exprGetter?.get()?.rExpr
    val isExprDbModification: Boolean get() = exprGetter?.get()?.isDbModification ?: false

    val hasExpr: Boolean get() = exprGetter != null

    fun toMetaGtv(): Gtv {
        return mapOf(
                "type" to type.toMetaGtv(),
                "mutable" to mutable.toGtv()
        ).toGtv()
    }

    fun copy(mutable: Boolean): R_Attribute {
        return R_Attribute(
                index = index,
                name = name,
                type = type,
                mutable = mutable,
                canSetInCreate = true,
                sqlMapping = sqlMapping,
                exprGetter = if (canSetInCreate) exprGetter else null // Not copying default value e. g. for "transaction".
        )
    }

    override fun toString() = name
}

data class R_ModuleKey(val name: R_ModuleName, val externalChain: String?) {
    override fun toString() = if (externalChain == null) name.toString() else "$name[$externalChain]"
}

class R_Module(
        val name: R_ModuleName,
        val abstract: Boolean,
        val external: Boolean,
        val externalChain: String?,
        val test: Boolean,
        val entities: Map<String, R_EntityDefinition>,
        val objects: Map<String, R_ObjectDefinition>,
        val structs: Map<String, R_StructDefinition>,
        val enums: Map<String, R_EnumDefinition>,
        val operations: Map<String, R_OperationDefinition>,
        val queries: Map<String, R_QueryDefinition>,
        val functions: Map<String, R_FunctionDefinition>,
        val constants: Map<String, R_GlobalConstantDefinition>,
        val moduleArgs: R_StructDefinition?
) {
    val key = R_ModuleKey(name, externalChain)

    override fun toString() = name.toString()

    fun toMetaGtv(): Gtv {
        val map = mutableMapOf(
                "name" to name.str().toGtv()
        )

        if (abstract) map["abstract"] = abstract.toGtv()
        if (external) map["external"] = external.toGtv()
        if (externalChain != null) map["externalChain"] = externalChain.toGtv()

        addGtvDefs(map, "entities", entities)
        addGtvDefs(map, "objects", objects)
        addGtvDefs(map, "structs", structs)
        addGtvDefs(map, "enums", enums)
        addGtvDefs(map, "operations", operations)
        addGtvDefs(map, "queries", queries)
        addGtvDefs(map, "functions", functions)
        addGtvDefs(map, "constants", constants)

        return map.toGtv()
    }

    private fun addGtvDefs(map: MutableMap<String, Gtv>, key: String, defs: Map<String, R_Definition>) {
        if (defs.isNotEmpty()) {
            map[key] = defs.keys.sorted().map { it to defs.getValue(it).toMetaGtv() }.toMap().toGtv()
        }
    }
}

class R_AppSqlDefs(
        entities: List<R_EntityDefinition>,
        objects: List<R_ObjectDefinition>,
        topologicalEntities: List<R_EntityDefinition>
) {
    val entities = entities.toImmList()
    val objects = objects.toImmList()
    val topologicalEntities = topologicalEntities.toImmList()

    init {
        checkEquals(this.topologicalEntities.size, this.entities.size)
    }

    fun same(other: R_AppSqlDefs): Boolean {
        return entities == other.entities
                && objects == other.objects
                && topologicalEntities == other.topologicalEntities
    }

    companion object {
        val EMPTY = R_AppSqlDefs(listOf(), listOf(), listOf())
    }
}

class R_App(
        val valid: Boolean,
        val uid: R_AppUid,
        modules: List<R_Module>,
        operations: Map<R_MountName, R_OperationDefinition>,
        queries: Map<R_MountName, R_QueryDefinition>,
        constants: List<R_GlobalConstantDefinition>,
        val externalChainsRoot: R_ExternalChainsRoot,
        externalChains: List<R_ExternalChainRef>,
        val sqlDefs: R_AppSqlDefs
) {
    val modules = modules.toImmList()
    val operations = operations.toImmMap()
    val queries = queries.toImmMap()
    val constants = constants.toImmList()
    val externalChains = externalChains.toImmList()

    val moduleMap = this.modules.map { it.name to it }.toMap().toImmMap()

    init {
        for ((i, c) in this.constants.withIndex()) {
            checkEquals(c.constId.index, i)
        }

        for ((i, c) in this.externalChains.withIndex()) {
            check(c.root === externalChainsRoot)
            checkEquals(c.index, i)
        }
    }

    fun toMetaGtv(): Gtv {
        return mapOf(
                "modules" to modules.map {
                    val name = it.name.str()
                    val fullName = if (it.externalChain == null) name else "$name[${it.externalChain}]"
                    fullName to it.toMetaGtv()
                }.toMap().toGtv()
        ).toGtv()
    }
}
