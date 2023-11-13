/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.model

import net.postchain.gtv.Gtv
import net.postchain.rell.base.compiler.base.core.C_DefinitionName
import net.postchain.rell.base.compiler.base.core.C_IdeSymbolInfo
import net.postchain.rell.base.compiler.base.namespace.C_Namespace
import net.postchain.rell.base.compiler.base.utils.C_LateGetter
import net.postchain.rell.base.model.expr.R_Expr
import net.postchain.rell.base.model.expr.R_FunctionExtensionsTable
import net.postchain.rell.base.runtime.utils.toGtv
import net.postchain.rell.base.utils.*
import net.postchain.rell.base.utils.doc.DocDefinition
import net.postchain.rell.base.utils.doc.DocSymbol

class R_DefinitionName(
    val module: String,
    val qualifiedName: String,
    val simpleName: String,
) {
    val appLevelName = appLevelName(module, qualifiedName)

    val strictAppLevelName: String by lazy {
        if (module.isEmpty()) ":$appLevelName" else appLevelName
    }

    override fun toString() = appLevelName

    companion object {
        fun appLevelName(module: String, qualifiedName: String): String {
            return if (module.isEmpty()) qualifiedName else R_DefinitionId.str(module, qualifiedName)
        }
    }
}

class R_DefinitionBase(
    val defId: R_DefinitionId,
    val defName: R_DefinitionName,
    val cDefName: C_DefinitionName,
    val initFrameGetter: C_LateGetter<R_CallFrame>,
    val docGetter: C_LateGetter<DocSymbol>,
)

abstract class R_Definition(base: R_DefinitionBase): DocDefinition {
    val defId = base.defId
    val defName = base.defName
    val cDefName = base.cDefName
    val initFrameGetter = base.initFrameGetter
    private val docGetter = base.docGetter

    val simpleName = defName.simpleName
    val moduleLevelName = defName.qualifiedName
    val appLevelName = defName.appLevelName

    final override val docSymbol: DocSymbol get() = docGetter.get()

    abstract fun toMetaGtv(): Gtv

    final override fun toString() = "${javaClass.simpleName}[$appLevelName]"
}

class R_DefinitionMeta(
    defName: R_DefinitionName,
    val mountName: R_MountName,
    val externalChain: Nullable<String>? = null,
) {
    val fullName: String = defName.strictAppLevelName
    val simpleName: String = defName.simpleName
    val moduleName: String = defName.module
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

enum class R_KeyIndexKind(val code: String) {
    KEY("key"),
    INDEX("index"),
    ;

    val nameMsg = MsgString(code)
}

class R_Attribute(
    val index: Int,
    val rName: R_Name,
    val type: R_Type,
    val mutable: Boolean,
    val keyIndexKind: R_KeyIndexKind?,
    val ideInfo: C_IdeSymbolInfo,
    val canSetInCreate: Boolean = true,
    val sqlMapping: String = rName.str,
    private val exprGetter: C_LateGetter<R_DefaultValue>?,
): DocDefinition {
    val ideName = R_IdeName(rName, ideInfo)
    val name = rName.str

    val expr: R_Expr? get() = exprGetter?.get()?.rExpr
    val isExprDbModification: Boolean get() = exprGetter?.get()?.isDbModification ?: false

    val hasExpr: Boolean get() = exprGetter != null

    override val docSymbol: DocSymbol get() = ideInfo.getIdeInfo().doc ?: DocSymbol.NONE

    fun toMetaGtv(): Gtv {
        return mapOf(
            "name" to name.toGtv(),
            "type" to type.toMetaGtv(),
            "mutable" to mutable.toGtv(),
        ).toGtv()
    }

    fun copy(mutable: Boolean, ideInfo: C_IdeSymbolInfo): R_Attribute {
        return R_Attribute(
                index = index,
                rName = rName,
                type = type,
                mutable = mutable,
                keyIndexKind = keyIndexKind,
                ideInfo = ideInfo,
                canSetInCreate = true,
                sqlMapping = sqlMapping,
                exprGetter = if (canSetInCreate) exprGetter else null // Not copying default value e. g. for "transaction".
        )
    }

    override fun toString() = name
}

data class R_ModuleKey(val name: R_ModuleName, val externalChain: String?) {
    fun str() = str(name, externalChain)
    override fun toString() = str()

    companion object {
        val EMPTY = R_ModuleKey(R_ModuleName.EMPTY, null)

        fun str(name: R_ModuleName, externalChain: String?): String {
            return if (externalChain == null) name.toString() else "$name[$externalChain]"
        }
    }
}

class R_Module(
    val name: R_ModuleName,
    val directory: Boolean,
    val abstract: Boolean,
    val external: Boolean,
    val externalChain: String?,
    val test: Boolean,
    val selected: Boolean,
    val entities: Map<String, R_EntityDefinition>,
    val objects: Map<String, R_ObjectDefinition>,
    val structs: Map<String, R_StructDefinition>,
    val enums: Map<String, R_EnumDefinition>,
    val operations: Map<String, R_OperationDefinition>,
    val queries: Map<String, R_QueryDefinition>,
    val functions: Map<String, R_FunctionDefinition>,
    val constants: Map<String, R_GlobalConstantDefinition>,
    val imports: Set<R_ModuleName>,
    val moduleArgs: R_StructDefinition?,
    override val docSymbol: DocSymbol,
    private val nsGetter: Getter<C_Namespace>,
): DocDefinition {
    val key = R_ModuleKey(name, externalChain)

    private val nsLazy: C_Namespace by lazy { nsGetter() }

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

    override fun getDocMember(name: String): DocDefinition? {
        val elem = nsLazy.getElement(R_Name.of(name))
        return elem?.item
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
        val functionExtensions: R_FunctionExtensionsTable,
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
