/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.module

import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.compiler.base.def.*
import net.postchain.rell.base.compiler.base.namespace.C_Namespace
import net.postchain.rell.base.compiler.base.namespace.C_NsAsm_Module
import net.postchain.rell.base.compiler.base.utils.C_Constants
import net.postchain.rell.base.compiler.base.utils.C_LateGetter
import net.postchain.rell.base.compiler.base.utils.C_SourcePath
import net.postchain.rell.base.model.*
import net.postchain.rell.base.utils.*
import net.postchain.rell.base.utils.doc.DocSymbol
import java.util.*

class C_ModuleHeader(
    val mountName: R_MountName,
    val abstract: Boolean,
    val external: Boolean,
    val test: Boolean,
    val docSymbol: DocSymbol?,
)

class C_CompiledRellFile(
        val path: C_SourcePath,
        val mntTables: C_MountTables,
        val importsDescriptor: C_FileImportsDescriptor
) {
    override fun toString() = path.str()

    companion object {
        fun empty(path: C_SourcePath): C_CompiledRellFile =
                C_CompiledRellFile(path, C_MountTables.EMPTY, C_FileImportsDescriptor.EMPTY)
    }
}

class C_ImportDescriptor(val pos: S_Pos, val module: C_ModuleDescriptor)

class C_ModuleImportsDescriptor(val key: C_ContainerKey, val name: R_ModuleName, files: List<C_FileImportsDescriptor>) {
    val files = files.toImmList()

    companion object {
        fun empty(moduleKey: C_ModuleKey) = C_ModuleImportsDescriptor(
                C_ModuleContainerKey.of(moduleKey),
                moduleKey.name,
                listOf()
        )
    }
}

class C_FileImportsDescriptor(
        imports: List<C_ImportDescriptor>,
        abstracts: List<C_AbstractFunctionDescriptor>,
        overrides: List<C_OverrideFunctionDescriptor>
) {
    val imports = imports.toImmList()
    val abstracts = abstracts.toImmList()
    val overrides = overrides.toImmList()

    companion object { val EMPTY = C_FileImportsDescriptor(listOf(), listOf(), listOf()) }
}

class C_ModuleKey(val name: R_ModuleName, val extChain: C_ExternalChain?) {
    fun keyStr() = R_ModuleKey.str(name, extChain?.name)
    override fun equals(other: Any?) = other is C_ModuleKey && name == other.name && extChain == other.extChain
    override fun hashCode() = Objects.hash(name, extChain)
    override fun toString() = "[${keyStr()}]"
}

sealed class C_ContainerKey {
    abstract fun defModuleName(): C_DefinitionModuleName
    fun keyStr(): String = defModuleName().str()
    final override fun toString() = defModuleName().str()
}

class C_ModuleContainerKey private constructor (val moduleKey: C_ModuleKey): C_ContainerKey() {
    override fun defModuleName() = C_DefinitionModuleName(moduleKey.name.str(), moduleKey.extChain?.name)
    override fun equals(other: Any?) = other is C_ModuleContainerKey && moduleKey == other.moduleKey
    override fun hashCode() = moduleKey.hashCode()

    companion object {
        fun of(moduleKey: C_ModuleKey): C_ContainerKey = C_ModuleContainerKey(moduleKey)
    }
}

object C_ReplContainerKey: C_ContainerKey() {
    override fun defModuleName() = C_DefinitionModuleName("<console>")
}

class C_ModuleDescriptor(
        val key: C_ModuleKey,
        val header: C_ModuleHeader,
        val directory: Boolean,
        private val importsDescriptorGetter: C_LateGetter<C_ModuleImportsDescriptor>
) {
    val name = key.name
    val extChain = key.extChain

    val containerKey = C_ModuleContainerKey.of(key)

    fun importsDescriptor() = importsDescriptorGetter.get()
}

class C_PrecompiledModule(val descriptor: C_ModuleDescriptor, val asmModule: C_NsAsm_Module)

class C_ModuleInternals(
        val contents: C_ModuleContents,
        val importsDescriptor: C_ModuleImportsDescriptor
) {
    companion object {
        fun empty(moduleKey: C_ModuleKey) = C_ModuleInternals(
                C_ModuleContents.EMPTY,
                C_ModuleImportsDescriptor.empty(moduleKey)
        )
    }
}

class C_Module(
        private val executor: C_CompilerExecutor,
        val descriptor: C_ModuleDescriptor,
        val parentKey: C_ModuleKey?,
        private val internalsGetter: C_LateGetter<C_ModuleInternals>
) {
    val key = descriptor.key
    val header = descriptor.header

    fun contents(): C_ModuleContents {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return internalsGetter.get().contents
    }
}

class C_CompiledModule(
        val rModule: R_Module,
        val contents: C_ModuleContents,
        val importsDescriptor: C_ModuleImportsDescriptor
)

object C_ModuleCompiler {
    fun compile(
        modCtx: C_ModuleContext,
        files: List<C_CompiledRellFile>,
        docSymbol: DocSymbol,
        nsGetter: Getter<C_Namespace>,
    ): C_CompiledModule {
        val defs = modCtx.getModuleDefs()

        val modMounts = processModuleMounts(modCtx.appCtx, files)
        val moduleArgs = processModuleArgs(modCtx.appCtx, defs)

        val modName = modCtx.moduleName

        val fileImports = files.map { it.importsDescriptor }
        val importedModules = fileImports.flatMap { it.imports.map { i -> i.module.name } }.toImmSet()
        val moduleImports = C_ModuleImportsDescriptor(modCtx.containerKey, modName, fileImports)

        val rModule = R_Module(
            modName,
            directory = modCtx.directory,
            abstract = modCtx.abstract,
            external = modCtx.external,
            externalChain = modCtx.extChain?.name,
            test = modCtx.test,
            selected = modCtx.selected,
            entities = defs.entities,
            objects = defs.objects,
            structs = defs.structs.mapValues { (_, v) -> v.structDef },
            enums = defs.enums,
            operations = defs.operations,
            queries = defs.queries,
            functions = defs.functions,
            constants = defs.constants,
            imports = importedModules,
            moduleArgs = moduleArgs?.structDef,
            docSymbol = docSymbol,
            nsGetter = nsGetter,
        )

        val contents = C_ModuleContents(modMounts, defs)
        return C_CompiledModule(rModule, contents, moduleImports)
    }

    private fun processModuleArgs(appCtx: C_AppContext, defs: C_ModuleDefs): C_Struct? {
        val moduleArgs = defs.structs[C_Constants.MODULE_ARGS_STRUCT]
        moduleArgs ?: return null

        appCtx.executor.onPass(C_CompilerPass.VALIDATION) {
            C_StructUtils.validateModuleArgs(appCtx.msgCtx, moduleArgs)
        }

        return moduleArgs
    }

    private fun processModuleMounts(appCtx: C_AppContext, files: List<C_CompiledRellFile>): C_MountTables {
        val stamp = appCtx.appUid

        val b = C_MountTablesBuilder(stamp)
        for (f in files) {
            val mntTables = C_MntEntry.processMountConflicts(appCtx.msgCtx, stamp, f.mntTables)
            b.add(mntTables)
        }

        val allTables = b.build()
        val resTables = C_MntEntry.processMountConflicts(appCtx.msgCtx, stamp, allTables)
        return resTables
    }
}

class C_ModuleDefsBuilder {
    val entities = C_ModuleDefTableBuilder<R_EntityDefinition>()
    val objects = C_ModuleDefTableBuilder<R_ObjectDefinition>()
    val structs = C_ModuleDefTableBuilder<C_Struct>()
    val enums = C_ModuleDefTableBuilder<R_EnumDefinition>()
    val functions = C_ModuleDefTableBuilder<R_FunctionDefinition>()
    val operations = C_ModuleDefTableBuilder<R_OperationDefinition>()
    val queries = C_ModuleDefTableBuilder<R_QueryDefinition>()
    val constants = C_ModuleDefTableBuilder<R_GlobalConstantDefinition>()

    fun build(): C_ModuleDefs {
        return C_ModuleDefs(
                entities = entities.build(),
                objects = objects.build(),
                structs = structs.build(),
                enums = enums.build(),
                functions = functions.build(),
                operations = operations.build(),
                queries = queries.build(),
                constants = constants.build()
        )
    }
}

class C_ModuleDefs(
        val entities: Map<String, R_EntityDefinition>,
        val objects: Map<String, R_ObjectDefinition>,
        val structs: Map<String, C_Struct>,
        val enums: Map<String, R_EnumDefinition>,
        val functions: Map<String, R_FunctionDefinition>,
        val operations: Map<String, R_OperationDefinition>,
        val queries: Map<String, R_QueryDefinition>,
        val constants: Map<String, R_GlobalConstantDefinition>
) {
    companion object {
        val EMPTY = C_ModuleDefs(
                entities = immMapOf(),
                objects = immMapOf(),
                structs = immMapOf(),
                enums = immMapOf(),
                functions = immMapOf(),
                operations = immMapOf(),
                queries = immMapOf(),
                constants = immMapOf()
        )
    }
}

class C_ModuleDefTableBuilder<T: Any> {
    private val map = mutableMapOf<String, T>()

    fun add(name: String, def: T) {
        if (name !in map) {
            map[name] = def
        }
    }

    fun add(defs: Map<String, T>) {
        for ((name, def) in defs) {
            add(name, def)
        }
    }

    fun build() = map.toImmMap()
}

class C_ModuleContents(
        val mntTables: C_MountTables,
        val defs: C_ModuleDefs
) {
    companion object { val EMPTY = C_ModuleContents(C_MountTables.EMPTY, C_ModuleDefs.EMPTY) }
}
