package net.postchain.rell.parser

import com.google.common.collect.ImmutableMap
import net.postchain.rell.Setter
import net.postchain.rell.model.*
import net.postchain.rell.toImmList
import net.postchain.rell.toImmMap
import java.util.*

private const val FILE_SUFFIX = ".rell"
private const val MODULE_FILE = "module" + FILE_SUFFIX

private class C_ParsedRellFile(val path: C_SourcePath, private val ast: S_RellFile?) {
    fun isModuleMainFile() = ast?.header != null

    fun compileHeader(globalCtx: C_GlobalContext, parentMountName: R_MountName): R_MountName {
        val res = ast?.compileHeader(globalCtx, parentMountName)
        return res ?: parentMountName
    }

    fun compile(ctx: C_ModuleSourceContext): C_CompiledRellFile {
        val res = ast?.compile(ctx.modCtx) ?: C_CompiledRellFile.EMPTY
        return res
    }
}

class C_CompiledRellFile(val nsProto: C_UserNsProto, val mntTables: C_MountTables, val innerNsSetter: Setter<C_Namespace>) {
    companion object {
        val EMPTY = C_CompiledRellFile(C_UserNsProto.EMPTY, C_MountTables.EMPTY, {})
    }
}

class C_ModuleSourceContext(val modCtx: C_ModuleContext)

abstract class C_ModuleSource {
    abstract fun files(): List<C_SourcePath>
    abstract fun compileMountName(globalCtx: C_GlobalContext, parentMountName: R_MountName): R_MountName
    abstract fun compile(ctx: C_ModuleSourceContext): List<C_CompiledRellFile>
}

private class C_FileModuleSource(private val file: C_ParsedRellFile): C_ModuleSource() {
    override fun files() = listOf(file.path)

    override fun compileMountName(globalCtx: C_GlobalContext, parentMountName: R_MountName): R_MountName {
        return file.compileHeader(globalCtx, parentMountName)
    }

    override fun compile(ctx: C_ModuleSourceContext): List<C_CompiledRellFile> {
        val compiled = file.compile(ctx)
        return listOf(compiled)
    }
}

private class C_DirModuleSource(private val files: List<C_ParsedRellFile>): C_ModuleSource() {
    override fun files() = files.map { it.path }

    override fun compileMountName(globalCtx: C_GlobalContext, parentMountName: R_MountName): R_MountName {
        for (file in files) {
            if (file.isModuleMainFile()) {
                return file.compileHeader(globalCtx, parentMountName)
            }
        }
        return parentMountName
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

class C_Module(
        val name: R_ModuleName,
        private val parentModule: C_Module?,
        private val source: C_ModuleSource,
        private val globalCtx: C_GlobalContext,
        private val executor: C_CompilerExecutor
) {
    private val content = C_LateInit(C_CompilerPass.NAMESPACES, C_ModuleContent.EMPTY)
    private var mountName: R_MountName? = null

    fun mountName(): R_MountName {
        var res = mountName
        if (res == null) {
            val parentMountName = parentModule?.mountName() ?: R_MountName.EMPTY
            res = source.compileMountName(globalCtx, parentMountName)
            mountName = res
        }
        return res
    }

    fun compile(appCtx: C_AppContext, modMgr: C_ModuleManager) {
        val nsLate = C_LateInit(C_CompilerPass.NAMESPACES, C_Namespace.EMPTY)
        val modCtx = C_ModuleContext(this, appCtx, modMgr, nsLate.getter)

        val ctx = C_ModuleSourceContext(modCtx)
        val compiledFiles = source.compile(ctx)

        modCtx.executor.onPass(C_CompilerPass.NAMESPACES) {
            val compiled = C_ModuleCompiler.compile(appCtx, name, compiledFiles, nsLate.setter)
            content.set(compiled.content)
            appCtx.addModule(compiled)
        }
    }

    fun content(): C_ModuleContent {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return content.get()
    }

    fun files() = source.files()
}

class C_CompiledModule(val rModule: R_Module, val content: C_ModuleContent)

class C_ModuleCompiler private constructor(private val appCtx: C_AppContext) {
    companion object {
        fun compile(
                appCtx: C_AppContext,
                modName: R_ModuleName,
                files: List<C_CompiledRellFile>,
                innerNsSetter: Setter<C_Namespace>
        ): C_CompiledModule {
            val modCompiler = C_ModuleCompiler(appCtx)
            return modCompiler.compile0(modName, files, innerNsSetter)
        }
    }

    private val globalCtx = appCtx.globalCtx
    private val errorEntries = mutableSetOf<C_NsEntry>()

    private fun compile0(
            modName: R_ModuleName,
            files: List<C_CompiledRellFile>,
            innerNsSetter: Setter<C_Namespace>
    ): C_CompiledModule {
        val resFiles = files.map { processFile(it) }

        val modNames = processModuleNames(resFiles)
        innerNsSetter(modNames.innerNs)

        val modMounts = processModuleMounts(resFiles)

        val defs = modNames.defs
        val moduleArgs = processModuleArgs(defs)

        val rModule = R_Module(
                modName,
                entities = defs.entities,
                objects = defs.objects,
                structs = defs.structs.mapValues { (_, v) -> v.struct },
                operations = defs.operations,
                queries = defs.queries,
                functions = defs.functions,
                moduleArgs = moduleArgs?.struct
        )

        val content = C_ModuleContent(modNames.outerNs, modMounts, defs)
        return C_CompiledModule(rModule, content)
    }

    private fun processModuleArgs(defs: C_ModuleDefs): C_Struct? {
        val moduleArgs = defs.structs[C_Constants.MODULE_ARGS_STRUCT]

        if (moduleArgs != null) {
            appCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
                if (!moduleArgs.struct.flags.typeFlags.gtv.fromGtv) {
                    throw C_Error(moduleArgs.name.pos, "module_args_nogtv",
                            "Struct '${moduleArgs.struct.moduleLevelName}' is not Gtv-compatible")
                }
            }
        }

        return moduleArgs
    }

    private fun processFile(file: C_CompiledRellFile): FileDefs {
        val fileEntries = file.nsProto.makeEntries(globalCtx)
        val goodEntries = C_NsEntry.processNameConflicts(globalCtx, fileEntries, errorEntries)
        val ns = C_NsEntry.createNamespace(goodEntries)
        file.innerNsSetter(ns)

        val mntTables = C_MntEntry.processMountConflicts(globalCtx, file.mntTables)
        return FileDefs(goodEntries, mntTables)
    }

    private fun processModuleNames(resFiles: List<FileDefs>): ModuleNs {
        val publicEntries = resFiles.flatMap { it.nsEntries }.filter { !it.privateAccess }
        val innerModuleEntries = appCtx.sysDefs.nsProto.entries + publicEntries
        val goodInnerModuleEntries = C_NsEntry.processNameConflicts(globalCtx, innerModuleEntries, errorEntries)

        processFileVsModuleNameConflicts(resFiles, goodInnerModuleEntries)

        val innerNs = C_NsEntry.createNamespace(goodInnerModuleEntries)

        val outerModuleEntries = goodInnerModuleEntries.filter { it.sName != null }
        val outerNs = C_NsEntry.createNamespace(outerModuleEntries)

        val defs = C_NsEntry.createModuleDefs(outerModuleEntries)
        return ModuleNs(innerNs, outerNs, defs)
    }

    private fun processFileVsModuleNameConflicts(resFiles: List<FileDefs>, goodInnerModuleEntries: List<C_NsEntry>) {
        for (fileRes in resFiles) {
            val privateFileEntries = fileRes.nsEntries.filter { it.privateAccess }
            val combinedFileEntries = goodInnerModuleEntries + privateFileEntries
            C_NsEntry.processNameConflicts(appCtx.globalCtx, combinedFileEntries, errorEntries)
        }
    }

    private fun processModuleMounts(resFiles: List<FileDefs>): C_MountTables {
        val builder = C_MountTablesBuilder()
        for (f in resFiles) builder.add(f.mntTables)
        val allTables = builder.build()
        val resTables = C_MntEntry.processMountConflicts(globalCtx, allTables)
        return resTables
    }

    private class FileDefs(val nsEntries: List<C_NsEntry>, val mntTables: C_MountTables)
    private class ModuleNs(val innerNs: C_Namespace, val outerNs: C_Namespace, val defs: C_ModuleDefs)
}

class C_ModuleDefsBuilder {
    val entities = C_ModuleDefTableBuilder<R_Entity>()
    val objects = C_ModuleDefTableBuilder<R_Object>()
    val structs = C_ModuleDefTableBuilder<C_Struct>()
    val functions = C_ModuleDefTableBuilder<R_Function>()
    val operations = C_ModuleDefTableBuilder<R_Operation>()
    val queries = C_ModuleDefTableBuilder<R_Query>()

    fun addDefs(defs: C_ModuleDefs) {
        entities.add(defs.entities)
        objects.add(defs.objects)
        structs.add(defs.structs)
        functions.add(defs.functions)
        operations.add(defs.operations)
        queries.add(defs.queries)
    }

    fun build(): C_ModuleDefs {
        return C_ModuleDefs(
                entities = entities.build(),
                objects = objects.build(),
                structs = structs.build(),
                functions = functions.build(),
                operations = operations.build(),
                queries = queries.build()
        )
    }
}

class C_ModuleDefs(
        val entities: Map<String, R_Entity>,
        val objects: Map<String, R_Object>,
        val structs: Map<String, C_Struct>,
        val functions: Map<String, R_Function>,
        val operations: Map<String, R_Operation>,
        val queries: Map<String, R_Query>
){
    companion object {
        val EMPTY = C_ModuleDefs(
                entities = ImmutableMap.of(),
                objects = ImmutableMap.of(),
                structs = ImmutableMap.of(),
                functions = ImmutableMap.of(),
                operations = ImmutableMap.of(),
                queries = ImmutableMap.of()
        )
    }
}

class C_ModuleDefTableBuilder<T> {
    private val map = mutableMapOf<String, T>()

    fun add(name: String, def: T) {
        check(name !in map) { "Name conflict: '$name'" }
        map[name] = def
    }

    fun add(defs: Map<String, T>) {
        for ((name, def) in defs) {
            add(name, def)
        }
    }

    fun build() = map.toImmMap()
}

class C_ModuleContent(val namespace: C_Namespace, val mntTables: C_MountTables, val defs: C_ModuleDefs) {
    companion object {
        val EMPTY = C_ModuleContent(C_Namespace.EMPTY, C_MountTables.EMPTY, C_ModuleDefs.EMPTY)
    }
}

class C_ModuleManager(
        private val appCtx: C_AppContext,
        sourceDir0: C_SourceDir,
        private val executor: C_CompilerExecutor
) {
    private val modSourceDir = C_ModuleManagerDir(appCtx.globalCtx, sourceDir0)

    private val modules = mutableMapOf<R_ModuleName, C_Module>()

    fun moduleFiles() = modules.values.flatMap { it.files() }.toSet().toList()

    fun linkModule(name: R_ModuleName): C_Module {
        val linked = modules[name]
        if (linked != null) {
            return linked
        }

        val parentModule = linkParentModule(name)

        val rawFile = readFileModule(name)
        val rawDir = readDirModule(name)

        // When checking for a conflict, only consider if a file or directory exists (validity does not matter).
        if (rawFile != null && rawDir != null) {
            throw C_CommonError("import:file_dir:$name", "Module '$name' is a file a directory at the same time")
        }

        // When trying to use a module, it must be valid.
        val source = rawFile?.source ?: rawDir?.source
        if (source == null) {
            throw C_CommonError("import:not_found:$name", "Module '$name' not found")
        }

        val module = C_Module(name, parentModule, source, appCtx.globalCtx, executor)

        executor.onPass(C_CompilerPass.DEFINITIONS, soft = true) {
            module.compile(appCtx, this)
        }

        modules[name] = module
        return module
    }

    private fun linkParentModule(name: R_ModuleName): C_Module? {
        // Need to check all parent modules, not just the direct (immediate) parent, because it's possible that
        // the direct parent module does not exist.

        var curName = name
        while (!curName.isEmpty()) {
            curName = curName.parent()
            try {
                val module = linkModule(curName)
                return module
            } catch (e: C_CommonError) {
                // ignore
            }
        }

        return null
    }

    private fun readFileModule(name: R_ModuleName): C_RawModule? {
        val n = name.parts.size
        if (n == 0) {
            return null
        }

        val dirParts = name.parts.subList(0, n - 1).map { it.str }
        val fileName = name.parts[n - 1].str + FILE_SUFFIX
        val path = C_SourcePath(dirParts + fileName)

        val file = modSourceDir.file(path)
        if (file == null) {
            return null
        }

        val ast = file.orElse(null)
        val source = if (ast != null && ast.header == null) null else C_FileModuleSource(C_ParsedRellFile(path, ast))
        return C_RawModule(source)
    }

    private fun readDirModule(name: R_ModuleName): C_RawModule? {
        val parts = name.parts.map { it.str }
        val path = C_SourcePath(parts)
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

        val source = if (modFiles.isEmpty()) null else C_DirModuleSource(modFiles.toImmList())
        return C_RawModule(source)
    }

    private class C_RawModule(val source: C_ModuleSource?)

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

private class C_ModuleManagerDir(private val globalCtx: C_GlobalContext, private val sourceDir: C_SourceDir) {
    private val fileCache = mutableMapOf<C_SourcePath, CacheEntry>()

    fun dir(path: C_SourcePath): Boolean {
        return sourceDir.dir(path)
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
            globalCtx.error(e)
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
