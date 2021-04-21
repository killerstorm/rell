/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_RellFile
import net.postchain.rell.model.*
import net.postchain.rell.utils.immMapOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import java.util.*

private const val FILE_SUFFIX = ".rell"
private const val MODULE_FILE = "module" + FILE_SUFFIX

private class C_ParsedRellFile(val path: C_SourcePath, private val ast: S_RellFile?) {
    fun isModuleMainFile() = ast?.header != null

    fun compileHeader(appCtx: C_AppContext, parentMountName: R_MountName): C_ModuleHeader? {
        return ast?.compileHeader(appCtx, parentMountName)
    }

    fun compile(ctx: C_ModuleSourceContext): C_CompiledRellFile {
        return ast?.compile(path, ctx.modCtx) ?: C_CompiledRellFile.empty(path)
    }
}

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

class C_ModuleHeader(
        val mountName: R_MountName,
        val abstractPos: S_Pos?,
        val external: Boolean,
        val test: Boolean
) {
    val abstract = abstractPos != null
}

class C_ModuleSourceContext(val modCtx: C_ModuleContext)

sealed class C_ModuleSource {
    abstract fun files(): List<C_SourcePath>
    abstract fun compileHeader(appCtx: C_AppContext, parentMountName: R_MountName): C_ModuleHeader?
    abstract fun compile(ctx: C_ModuleSourceContext): List<C_CompiledRellFile>
}

private class C_FileModuleSource(val file: C_ParsedRellFile): C_ModuleSource() {
    override fun files() = listOf(file.path)

    override fun compileHeader(appCtx: C_AppContext, parentMountName: R_MountName): C_ModuleHeader? {
        return file.compileHeader(appCtx, parentMountName)
    }

    override fun compile(ctx: C_ModuleSourceContext): List<C_CompiledRellFile> {
        val compiled = file.compile(ctx)
        return listOf(compiled)
    }
}

private class C_DirModuleSource(val path: C_SourcePath, files: List<C_ParsedRellFile>): C_ModuleSource() {
    private val files = files.toImmList()

    override fun files() = files.map { it.path }

    override fun compileHeader(appCtx: C_AppContext, parentMountName: R_MountName): C_ModuleHeader? {
        for (file in files) {
            if (file.isModuleMainFile()) {
                return file.compileHeader(appCtx, parentMountName)
            }
        }
        return null
    }

    override fun compile(ctx: C_ModuleSourceContext): List<C_CompiledRellFile> {
        val res = mutableListOf<C_CompiledRellFile>()
        for (file in files.sortedBy { it.path }) {
            val compiled = file.compile(ctx)
            res.add(compiled)
        }
        return res.toImmList()
    }
}

class C_ImportDescriptor(val pos: S_Pos, val module: C_ModuleDescriptor)

class C_ModuleImportsDescriptor(val key: C_ContainerKey, val name: R_ModuleName, files: List<C_FileImportsDescriptor>) {
    val files = files.toImmList()

    companion object {
        fun empty(module: C_ModuleKey, name: R_ModuleName) = C_ModuleImportsDescriptor(C_ModuleContainerKey.of(module), name, listOf())
    }
}

class C_FileImportsDescriptor(
        imports: List<C_ImportDescriptor>,
        abstracts: List<C_AbstractDescriptor>,
        overrides: List<C_OverrideDescriptor>
) {
    val imports = imports.toImmList()
    val abstracts = abstracts.toImmList()
    val overrides = overrides.toImmList()

    companion object { val EMPTY = C_FileImportsDescriptor(listOf(), listOf(), listOf()) }
}

class C_ModuleKey(val name: R_ModuleName, val extChain: C_ExternalChain?) {
    fun keyStr(): String {
        val nameStr = name.str()
        return if (extChain == null) nameStr else "$nameStr[${extChain.name}]"
    }

    override fun equals(other: Any?) = other is C_ModuleKey && name == other.name && extChain == other.extChain
    override fun hashCode() = Objects.hash(name, extChain)
    override fun toString() = "[${keyStr()}]"
}

sealed class C_ContainerKey {
    abstract fun keyStr(): String
    final override fun toString() = keyStr()
}

class C_ModuleContainerKey private constructor (val moduleKey: C_ModuleKey): C_ContainerKey() {
    override fun keyStr() = moduleKey.keyStr()
    override fun equals(other: Any?) = other is C_ModuleContainerKey && moduleKey == other.moduleKey
    override fun hashCode() = moduleKey.hashCode()

    companion object {
        fun of(moduleKey: C_ModuleKey): C_ContainerKey = C_ModuleContainerKey(moduleKey)
    }
}

object C_ReplContainerKey: C_ContainerKey() {
    override fun keyStr() = "<console>"
}

class C_ModuleDescriptor(
        val key: C_ModuleKey,
        val header: C_ModuleHeader,
        private val importsDescriptorGetter: C_LateGetter<C_ModuleImportsDescriptor>
) {
    val name = key.name
    val extChain = key.extChain

    val containerKey = C_ModuleContainerKey.of(key)

    fun importsDescriptor() = importsDescriptorGetter.get()
}

class C_PrecompiledModule(val descriptor: C_ModuleDescriptor, val asmModule: C_NsAsm_Module)

class C_Module(
        val key: C_ModuleKey,
        val header: C_ModuleHeader,
        private val parentKey: C_ModuleKey?,
        private val source: C_ModuleSource,
        private val internalModMgr: C_InternalModuleManager
) {
    private val contentsLate = C_LateInit(C_CompilerPass.MODULES, C_ModuleContents.EMPTY)
    private val importsDescriptorLate = C_LateInit(C_CompilerPass.MODULES, C_ModuleImportsDescriptor.empty(key, key.name))
    private var compiled = false

    val descriptor = C_ModuleDescriptor(key, header, importsDescriptorLate.getter)

    private val appCtx = internalModMgr.appCtx
    private val executor = appCtx.executor

    fun compile() {
        if (compiled) {
            return
        }
        compiled = true

        if (parentKey != null && !header.test) {
            internalModMgr.compileModule(parentKey)
        }

        executor.onPass(C_CompilerPass.DEFINITIONS, soft = true) {
            val modCtx = C_RegularModuleContext(internalModMgr.facadeMgr, this)
            val ctx = C_ModuleSourceContext(modCtx)
            val compiledFiles = source.compile(ctx)

            executor.onPass(C_CompilerPass.MODULES) {
                val compiled = C_ModuleCompiler.compile(modCtx, compiledFiles)
                contentsLate.set(compiled.contents)
                importsDescriptorLate.set(compiled.importsDescriptor)
                appCtx.addModule(descriptor, compiled)
            }
        }
    }

    fun contents(): C_ModuleContents {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return contentsLate.get()
    }

    fun files() = source.files()
}

class C_CompiledModule(
        val rModule: R_Module,
        val contents: C_ModuleContents,
        val importsDescriptor: C_ModuleImportsDescriptor
)

class C_ModuleCompiler private constructor(private val modCtx: C_ModuleContext) {
    companion object {
        fun compile(modCtx: C_ModuleContext, files: List<C_CompiledRellFile>): C_CompiledModule {
            val modCompiler = C_ModuleCompiler(modCtx)
            return modCompiler.compile0(files)
        }
    }

    private val msgCtx = modCtx.msgCtx

    private fun compile0(files: List<C_CompiledRellFile>): C_CompiledModule {
        val defs = modCtx.getModuleDefs()

        val modMounts = processModuleMounts(files)
        val moduleArgs = processModuleArgs(defs)

        val modName = modCtx.moduleName

        val rModule = R_Module(
                modName,
                abstract = modCtx.abstract,
                external = modCtx.external,
                externalChain = modCtx.extChain?.name,
                test = modCtx.test,
                entities = defs.entities,
                objects = defs.objects,
                structs = defs.structs.mapValues { (_, v) -> v.structDef },
                enums = defs.enums,
                operations = defs.operations,
                queries = defs.queries,
                functions = defs.functions,
                moduleArgs = moduleArgs?.structDef
        )

        val fileImports = files.map { it.importsDescriptor }
        val moduleImports = C_ModuleImportsDescriptor(modCtx.containerKey, modName, fileImports)

        val contents = C_ModuleContents(modMounts, defs)
        return C_CompiledModule(rModule, contents, moduleImports)
    }

    private fun processModuleArgs(defs: C_ModuleDefs): C_Struct? {
        val moduleArgs = defs.structs[C_Constants.MODULE_ARGS_STRUCT]

        if (moduleArgs != null) {
            modCtx.appCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                if (!moduleArgs.structDef.struct.flags.typeFlags.gtv.fromGtv) {
                    throw C_Error.more(moduleArgs.name.pos, "module_args_nogtv",
                            "Struct '${moduleArgs.structDef.moduleLevelName}' is not Gtv-compatible")
                }
            }
        }

        return moduleArgs
    }

    private fun processModuleMounts(files: List<C_CompiledRellFile>): C_MountTables {
        val stamp = modCtx.appCtx.appUid

        val b = C_MountTablesBuilder(stamp)
        for (f in files) {
            val mntTables = C_MntEntry.processMountConflicts(msgCtx, stamp, f.mntTables)
            b.add(mntTables)
        }

        val allTables = b.build()
        val resTables = C_MntEntry.processMountConflicts(msgCtx, stamp, allTables)
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

    fun build(): C_ModuleDefs {
        return C_ModuleDefs(
                entities = entities.build(),
                objects = objects.build(),
                structs = structs.build(),
                enums = enums.build(),
                functions = functions.build(),
                operations = operations.build(),
                queries = queries.build()
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
        val queries: Map<String, R_QueryDefinition>
){
    companion object {
        val EMPTY = C_ModuleDefs(
                entities = immMapOf(),
                objects = immMapOf(),
                structs = immMapOf(),
                enums = immMapOf(),
                functions = immMapOf(),
                operations = immMapOf(),
                queries = immMapOf()
        )
    }
}

class C_ModuleDefTableBuilder<T> {
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

class C_InternalModuleManager(
        val appCtx: C_AppContext,
        sourceDir0: C_SourceDir,
        private val precompiledModules: Map<C_ModuleKey, C_PrecompiledModule>
) {
    val facadeMgr = C_ModuleManager(this)

    private val modSourceDir = C_ModuleManagerDir(appCtx.msgCtx, sourceDir0)

    private val modules = mutableMapOf<C_ModuleKey, C_Module>()

    fun moduleFiles() = modules.values.flatMap { it.files() }.toSet().toList()

    fun compileModule(key: C_ModuleKey): C_ModuleDescriptor {
        return linkModule(key, true)
    }

    fun linkModule(key: C_ModuleKey, compile: Boolean): C_ModuleDescriptor {
        val precompiled = precompiledModules[key]
        if (precompiled != null) {
            return precompiled.descriptor
        }

        var module = modules[key]
        if (module == null) {
            module = linkModule0(key)
        }

        if (compile) {
            module.compile()
        }

        return module.descriptor
    }

    private fun linkModule0(key: C_ModuleKey): C_Module {
        val name = key.name
        val parentDescriptor = linkParentModule(name)

        val source = getModuleSource(name)
        source ?: throw errModuleNotFound(name)

        val module = processModuleSource(key, parentDescriptor, source)

        if (!module.header.test && parentDescriptor != null && parentDescriptor.header.test) {
            throw C_CommonError("module:parent_is_test:$name:${parentDescriptor.name}",
                    "Parent module of '$name' is a test module '${parentDescriptor.name}'")
        }

        return module
    }

    private fun getModuleSource(name: R_ModuleName): C_ModuleSource? {
        val rawFile = readFileModule(name)
        val rawDir = readDirModule(name)

        // When checking for a conflict, only consider if a file or directory exists (validity does not matter).
        if (rawFile != null && rawDir != null) {
            throw C_CommonError("import:file_dir:$name", "Module '$name' is a file and a directory at the same time")
        }

        // When trying to use a module, it must be valid.
        return rawFile?.source ?: rawDir?.source
    }

    private fun processModuleSource(
            key: C_ModuleKey,
            parentDescriptor: C_ModuleDescriptor?,
            source: C_ModuleSource
    ): C_Module {
        var module = modules[key]
        if (module == null) {
            val parentMountName = parentDescriptor?.header?.mountName ?: R_MountName.EMPTY
            val header = source.compileHeader(appCtx, parentMountName)
                    ?: C_ModuleHeader(parentMountName, null, external = false, test = false)
            module = C_Module(key, header, parentDescriptor?.key, source, this)
            modules[key] = module
        }
        return module
    }

    private fun linkParentModule(name: R_ModuleName): C_ModuleDescriptor? {
        // Need to check all parent modules, not just the direct (immediate) parent, because the direct parent
        // not necessarily exists.

        var curName = name
        while (!curName.isEmpty()) {
            curName = curName.parent()
            try {
                val key = C_ModuleKey(curName, null)
                val descriptor = linkModule(key, false)
                return descriptor
            } catch (e: C_CommonError) {
                // ignore
            }
        }

        return null
    }

    fun compileTestModules(rootModules: List<R_ModuleName>) {
        for (rootModule in rootModules) {
            compileTestModules(rootModule)
        }
    }

    private fun compileTestModules(rootModule: R_ModuleName) {
        val source = getModuleSource(rootModule)
        if (source == null) {
            val path = moduleNameToDirPath(rootModule)
            if (!modSourceDir.dir(path)) {
                throw errModuleNotFound(rootModule)
            }
            compileTestModulesTree(rootModule, path)
            return
        }

        compileTestModule(rootModule, source)
        return when (source) {
            is C_FileModuleSource -> {
                // Do nothing.
            }
            is C_DirModuleSource -> {
                compileTestModulesTree(rootModule, source.path)
            }
        }
    }

    private fun compileTestModulesTree(moduleName: R_ModuleName, path: C_SourcePath) {
        val fileModFiles = mutableListOf<Pair<R_ModuleName, C_ParsedRellFile>>()
        val dirModFiles = mutableListOf<C_ParsedRellFile>()

        for (name in modSourceDir.files(path)) {
            val rName = fileNameToRName(name)
            rName ?: continue

            val subPath = path.add(name)
            val ast = modSourceDir.file(subPath)?.orElse(null)
            ast ?: continue

            val parsed = C_ParsedRellFile(subPath, ast)

            if (name == MODULE_FILE || ast.header == null) {
                dirModFiles.add(parsed)
            } else {
                val fileModuleName = moduleName.child(rName)
                fileModFiles.add(fileModuleName to parsed)
            }
        }

        if (dirModFiles.isNotEmpty()) {
            val source = C_DirModuleSource(path, dirModFiles)
            compileTestModule(moduleName, source)
        }

        for ((fileModuleName, file) in fileModFiles) {
            val source = C_FileModuleSource(file)
            compileTestModule(fileModuleName, source)
        }

        for (name in modSourceDir.dirs(path)) {
            val rName = R_Name.ofOpt(name)
            rName ?: continue
            val subModuleName = moduleName.child(rName)
            val subPath = path.add(name)
            compileTestModulesTree(subModuleName, subPath)
        }
    }

    private fun compileTestModule(moduleName: R_ModuleName, source: C_ModuleSource) {
        val key = C_ModuleKey(moduleName, null)
        val parentDescriptor = linkParentModule(moduleName)
        val module = processModuleSource(key, parentDescriptor, source)
        if (module.header.test) {
            module.compile()
        }
    }

    private fun fileNameToRName(name: String): R_Name? {
        if (!name.endsWith(FILE_SUFFIX)) return null
        val baseName = name.substring(0, name.length - FILE_SUFFIX.length)
        return R_Name.ofOpt(baseName)
    }

    private fun readFileModule(name: R_ModuleName): C_RawModule? {
        val n = name.parts.size
        if (n == 0) {
            return null
        }

        val dirParts = name.parts.subList(0, n - 1).map { it.str }
        val fileName = name.parts[n - 1].str + FILE_SUFFIX
        val path = C_SourcePath.of(dirParts + fileName)

        val file = modSourceDir.file(path)
        if (file == null) {
            return null
        }

        val ast = file.orElse(null)
        val source = if (ast != null && ast.header == null) null else C_FileModuleSource(C_ParsedRellFile(path, ast))
        return C_RawModule(source)
    }

    private fun readDirModule(name: R_ModuleName): C_RawModule? {
        val path = moduleNameToDirPath(name)
        val dir = modSourceDir.dir(path)
        if (!dir) {
            return null
        }

        val files = modSourceDir.files(path).filter { it.endsWith(FILE_SUFFIX) }.sorted()
        if (files.isEmpty()) {
            return null
        }

        val modFiles = mutableListOf<C_ParsedRellFile>()

        for (file in files) {
            val filePath = path.add(file)
            val opt = modSourceDir.file(filePath)
            if (opt != null) {
                val ast = opt.orElse(null)
                if (file == MODULE_FILE || ast == null || ast.header == null) {
                    modFiles.add(C_ParsedRellFile(filePath, ast))
                }
            }
        }

        val source = if (modFiles.isEmpty()) null else C_DirModuleSource(path, modFiles.toImmList())
        return C_RawModule(source)
    }

    private fun moduleNameToDirPath(name: R_ModuleName): C_SourcePath {
        val parts = name.parts.map { it.str }
        return C_SourcePath.of(parts)
    }

    private fun errModuleNotFound(name: R_ModuleName): C_CommonError {
        return C_CommonError("import:not_found:$name", "Module '$name' not found")
    }

    private class C_RawModule(val source: C_ModuleSource?)
}

class C_ModuleManager(private val internalMgr: C_InternalModuleManager) {
    val appCtx = internalMgr.appCtx

    fun linkModule(name: R_ModuleName, extChain: C_ExternalChain?): C_ModuleDescriptor {
        val key = C_ModuleKey(name, extChain)
        return internalMgr.linkModule(key, true)
    }

    companion object {
        fun getModuleInfo(path: C_SourcePath, ast: S_RellFile): Pair<R_ModuleName?, Boolean> {
            val parts = path.parts
            val n = parts.size
            if (n == 0) {
                return Pair(null, false)
            }

            val tail = parts[n - 1]
            if (!tail.endsWith(FILE_SUFFIX)) {
                return Pair(null, false)
            }

            var nameParts = parts.subList(0, n - 1)
            var directory = false

            if (tail == MODULE_FILE || ast.header == null) {
                directory = true
            } else {
                val tailName = tail.substring(0, tail.length - FILE_SUFFIX.length)
                nameParts = nameParts + listOf(tailName)
            }

            val rNames = nameParts.map { R_Name.ofOpt(it) }.filterNotNull()
            if (rNames.size != nameParts.size) {
                return Pair(null, false)
            }

            val moduleName = R_ModuleName(rNames)
            return Pair(moduleName, directory)
        }
    }
}

private class C_ModuleManagerDir(private val msgCtx: C_MessageContext, private val sourceDir: C_SourceDir) {
    private val fileCache = mutableMapOf<C_SourcePath, CacheEntry>()

    fun dir(path: C_SourcePath): Boolean {
        return sourceDir.dir(path)
    }

    fun dirs(path: C_SourcePath): List<String> {
        return sourceDir.dirs(path)
    }

    fun files(path: C_SourcePath): List<String> {
        return sourceDir.files(path)
    }

    fun file(path: C_SourcePath): Optional<S_RellFile>? {
        var cache = fileCache[path]
        if (cache != null) {
            return cache.toResult()
        }

        cache = file0(path)
        fileCache[path] = cache

        return cache.toResult()
    }

    private fun file0(path: C_SourcePath): CacheEntry {
        val file = sourceDir.file(path)
        if (file == null) {
            return CacheEntry(false, null)
        }

        var ast: S_RellFile? = null

        try {
            ast = file.readAst()
        } catch (e: C_Error) {
            msgCtx.error(e)
        } catch (e: Throwable) {
            // The file may be provided also by IDE, so all kinds of errors shall be handled.
            return CacheEntry(false, null)
        }

        return CacheEntry(true, ast)
    }

    private class CacheEntry(val exists: Boolean, val ast: S_RellFile?) {
        fun toResult(): Optional<S_RellFile>? = if (exists) Optional.ofNullable(ast) else null
    }
}
