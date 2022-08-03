/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.module

import net.postchain.rell.compiler.ast.S_RellFile
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.core.C_NopSymbolContext
import net.postchain.rell.compiler.base.core.C_SymbolContext
import net.postchain.rell.compiler.base.core.C_SymbolContextProvider
import net.postchain.rell.compiler.base.modifier.C_ModifierContext
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_QualifiedName
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList

object C_ModuleUtils {
    const val FILE_SUFFIX = ".rell"
    const val MODULE_FILE = "module$FILE_SUFFIX"

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

        val rNames = nameParts.mapNotNull { R_Name.ofOpt(it) }
        if (rNames.size != nameParts.size) {
            return Pair(null, false)
        }

        val moduleName = R_ModuleName(rNames)
        return Pair(moduleName, directory)
    }
}

class C_ModuleReaderContext(
        val msgCtx: C_MessageContext,
        private val symCtxProvider: C_SymbolContextProvider,
        private val importLoader: C_ImportModuleLoader
) {
    fun createModuleSourceContext(moduleName: R_ModuleName): C_ModuleSourceContext {
        return C_ModuleSourceContext(msgCtx, symCtxProvider, importLoader, moduleName)
    }
}

class C_ModuleReader(
        readerCtx: C_ModuleReaderContext,
        sourceDir: C_SourceDir
) {
    private val dirTree = C_ModuleDirTree(readerCtx, sourceDir)
    private val cache = mutableMapOf<R_ModuleName, CacheEntry>()

    fun dirExists(moduleName: R_ModuleName) = dirTree.dirExists(moduleName)
    fun fileSubModules(moduleName: R_ModuleName) = dirTree.fileSubModules(moduleName)
    fun dirSubModules(moduleName: R_ModuleName) = dirTree.dirSubModules(moduleName)

    fun readModuleSource(name: R_ModuleName): C_ModuleSource? {
        val entry = cache.computeIfAbsent(name) {
            try {
                val source = dirTree.readModuleSource(name)
                CacheEntry_OK(source)
            } catch (e: C_CommonError) {
                CacheEntry_Error(C_CodeMsg(e.code, e.msg))
            }
        }
        return entry.getValue()
    }

    private abstract class CacheEntry {
        abstract fun getValue(): C_ModuleSource?
    }

    private class CacheEntry_OK(val source: C_ModuleSource?): CacheEntry() {
        override fun getValue() = source
    }

    private class CacheEntry_Error(val codeMsg: C_CodeMsg): CacheEntry() {
        override fun getValue() = throw C_CommonError(codeMsg)
    }
}

private class C_ModuleDirTree(
        private val readerCtx: C_ModuleReaderContext,
        private val sourceDir: C_SourceDir
) {
    private val rootDir = DirNode(C_SourcePath.EMPTY, R_ModuleName.EMPTY)

    fun dirExists(moduleName: R_ModuleName): Boolean {
        val dirNode = getDirNode(moduleName.parts)
        return dirNode != null
    }

    fun fileSubModules(moduleName: R_ModuleName): List<C_ModuleSource> {
        val dirNode = getDirNode(moduleName.parts)
        return dirNode?.fileSubModules() ?: immListOf()
    }

    fun dirSubModules(moduleName: R_ModuleName): List<R_ModuleName> {
        val dirNode = getDirNode(moduleName.parts)
        return dirNode?.dirSubModules() ?: immListOf()
    }

    fun readModuleSource(name: R_ModuleName): C_ModuleSource? {
        val (rawFile, rawDir) = if (name.isEmpty()) {
            null to readDirModule(rootDir)
        } else {
            val headPath = name.parts.subList(0, name.parts.size - 1)
            val tailName = name.parts.last()

            val dirNode = getDirNode(headPath)
            dirNode ?: return null

            val rawf = readFileModule(dirNode.subFile(tailName))
            val rawd = readDirModule(dirNode.subDir(tailName))

            rawf to rawd
        }

        // When checking for a conflict, only consider if a file or directory exists (validity does not matter).
        if (rawFile != null && rawDir != null) {
            throw C_CommonError("import:file_dir:$name", "Module '$name' is a file and a directory at the same time")
        }

        // When trying to use a module, it must be valid.
        val source = rawFile?.source ?: rawDir?.source
        return source
    }

    private fun getDirNode(path: List<R_Name>): DirNode? {
        var res = rootDir
        for (name in path) {
            val sub = res.subDir(name)
            sub ?: return null
            res = sub
        }
        return res
    }

    private fun readFileModule(fileNode: FileNode?): C_RawModule? {
        fileNode ?: return null
        val source = fileNode.getModuleSource()
        return C_RawModule(source)
    }

    private fun readDirModule(dirNode: DirNode?): C_RawModule? {
        dirNode ?: return null

        if (!dirNode.hasRellFiles()) {
            return null
        }

        val source = dirNode.getModuleSource()
        return C_RawModule(source)
    }

    private class C_RawModule(val source: C_ModuleSource?)

    private abstract inner class TreeNode(val path: C_SourcePath)

    private inner class DirNode(
            path: C_SourcePath,
            val moduleName: R_ModuleName
    ): TreeNode(path) {
        private val subDirs = mutableMapOf<R_Name, DirNode>()
        private val subFiles = mutableMapOf<String, FileNode>()

        private val rellFilesLazy: List<String> by lazy {
            sourceDir.files(path).filter { it.endsWith(C_ModuleUtils.FILE_SUFFIX) }.sorted()
        }

        private val loadAllFileNodesLazy by lazy {
            calcAllFileNodes()
        }

        private val moduleSourceLazy: C_ModuleSource? by lazy {
            calcModuleSource()
        }

        private val dirSubModulesLazy: List<R_ModuleName> by lazy {
            calcDirSubModules()
        }

        private val fileSubModulesLazy: List<C_ModuleSource> by lazy {
            calcFileSubModules()
        }

        fun hasRellFiles() = rellFilesLazy.isNotEmpty()
        fun getModuleSource() = moduleSourceLazy

        fun dirSubModules() = dirSubModulesLazy
        fun fileSubModules() = fileSubModulesLazy

        fun subDir(name: R_Name): DirNode? {
            return subNode(name, subDirs) {
                val subModule = moduleName.child(name)
                val subPath = path.add(name.str)
                val subExists = sourceDir.dir(subPath)
                if (subExists) DirNode(subPath, subModule) else null
            }
        }

        fun subFile(name: R_Name): FileNode? {
            val fileName = "${name.str}${C_ModuleUtils.FILE_SUFFIX}"
            return subNode(fileName, subFiles) {
                makeFileNode(fileName, name)
            }
        }

        private fun <K, V> subNode(
                name: K,
                map: MutableMap<K, V>,
                makeNode: () -> V?
        ): V? {
            val oldNode = map[name]
            if (oldNode != null) {
                return oldNode
            }

            val newNode = makeNode()
            if (newNode != null) {
                map[name] = newNode
            }

            return newNode
        }

        private fun calcAllFileNodes() {
            for (file in rellFilesLazy) {
                if (file !in subFiles) {
                    val rName = R_Name.ofOpt(file.removeSuffix(C_ModuleUtils.FILE_SUFFIX))
                    val fileNode = makeFileNode(file, rName)
                    if (fileNode != null) {
                        subFiles[file] = fileNode
                    }
                }
            }
        }

        private fun calcModuleSource(): C_ModuleSource? {
            loadAllFileNodesLazy

            val modFiles = mutableListOf<C_ParsedRellFile>()
            var mainFile: C_ParsedRellFile? = null

            for (fileNode in subFiles.values) {
                val ast = fileNode.getAst()
                val isMainFile = fileNode.fileName == C_ModuleUtils.MODULE_FILE
                if (isMainFile || ast == null || ast.header == null) {
                    val parsedFile = C_ParsedRellFile(fileNode.path, ast)
                    modFiles.add(parsedFile)
                    if (isMainFile) {
                        mainFile = parsedFile
                    }
                }
            }

            return if (modFiles.isEmpty()) null else {
                val srcCtx = readerCtx.createModuleSourceContext(moduleName)
                C_DirModuleSource(srcCtx, path, modFiles.toImmList(), mainFile)
            }
        }

        private fun calcDirSubModules(): List<R_ModuleName> {
            val dirs = sourceDir.dirs(path).sorted()

            for (dir in dirs) {
                val rName = R_Name.ofOpt(dir)
                if (rName == null || rName in subDirs) continue

                val subModule = moduleName.child(rName)
                val subPath = path.add(rName.str)
                subDirs[rName] = DirNode(subPath, subModule)
            }

            return subDirs.values.map { it.moduleName }.sorted().toImmList()
        }

        private fun calcFileSubModules(): List<C_ModuleSource> {
            loadAllFileNodesLazy
            return subFiles.values
                    .mapNotNull { it.getModuleSource() }
                    .sortedBy { it.moduleName }
                    .toImmList()
        }

        private fun makeFileNode(name: String, rName: R_Name?): FileNode? {
            val subPath = path.add(name)
            val sourceFile = sourceDir.file(subPath)
            return if (sourceFile == null) null else FileNode(subPath, moduleName, name, rName, sourceFile)
        }
    }

    private inner class FileNode(
            path: C_SourcePath,
            private val dirModuleName: R_ModuleName,
            val fileName: String,
            private val rName: R_Name?,
            private val sourceFile: C_SourceFile?
    ): TreeNode(path) {
        private val astLazy: S_RellFile? by lazy {
            calcAst()
        }

        private val moduleSourceLazy: C_ModuleSource? by lazy {
            calcModuleSource()
        }

        fun getAst() = astLazy
        fun getModuleSource() = moduleSourceLazy

        private fun calcAst(): S_RellFile? {
            return try {
                sourceFile?.readAst()
            } catch (e: C_Error) {
                readerCtx.msgCtx.error(e)
                null
            } catch (e: Throwable) {
                if (CommonUtils.IS_UNIT_TEST) throw e else null
                // This code might be called from IDE, for which reason it's better to suppress an error.
                // TODO Suppress the error only when called from IDE
            }
        }

        private fun calcModuleSource(): C_ModuleSource? {
            if (rName == null || fileName == C_ModuleUtils.MODULE_FILE) {
                return null
            }

            val ast = astLazy

            return if (ast != null && ast.header == null) null else {
                val moduleName = dirModuleName.child(rName)
                val srcCtx = readerCtx.createModuleSourceContext(moduleName)
                C_FileModuleSource(srcCtx, C_ParsedRellFile(path, ast))
            }
        }
    }
}

class C_ModuleSourceContext(
        val msgCtx: C_MessageContext,
        val symCtxProvider: C_SymbolContextProvider,
        private val importLoader: C_ImportModuleLoader,
        val moduleName: R_ModuleName
) {
    fun createDefinitionContext(path: C_SourcePath): C_ModuleDefinitionContext {
        val symCtx = symCtxProvider.getSymbolContext(path)
        return C_ModuleDefinitionContext.root(msgCtx, importLoader, moduleName, symCtx)
    }
}

class C_ModuleDefinitionContext private constructor(
    val msgCtx: C_MessageContext,
    private val importLoader: C_ImportModuleLoader,
    val moduleName: R_ModuleName,
    val namespaceName: R_QualifiedName,
    val symCtx: C_SymbolContext
) {
    fun loadModule(name: R_ModuleName): Boolean {
        return importLoader.loadModule(name)
    }

    fun namespace(name: R_QualifiedName): C_ModuleDefinitionContext {
        val subNamespaceName = namespaceName.child(name)
        return C_ModuleDefinitionContext(msgCtx, importLoader, moduleName, subNamespaceName, symCtx)
    }

    companion object {
        fun root(
            msgCtx: C_MessageContext,
            importLoader: C_ImportModuleLoader,
            moduleName: R_ModuleName,
            symCtx: C_SymbolContext
        ): C_ModuleDefinitionContext {
            return C_ModuleDefinitionContext(msgCtx, importLoader, moduleName, R_QualifiedName.EMPTY, symCtx)
        }
    }
}

sealed class C_ModuleSource(protected val ctx: C_ModuleSourceContext) {
    val moduleName = ctx.moduleName

    private val compiledHeader by lazy {
        compileHeader0()
    }

    private val compiledFiles by lazy {
        compile0()
    }

    abstract fun isDirectory(): Boolean

    protected abstract fun compileHeader0(): C_MidModuleHeader?
    protected abstract fun compile0(): List<C_MidModuleFile>

    fun compileHeader(): C_MidModuleHeader? = compiledHeader
    fun compile(): List<C_MidModuleFile> = compiledFiles
}

class C_FileModuleSource(
        ctx: C_ModuleSourceContext,
        val file: C_ParsedRellFile
): C_ModuleSource(ctx) {
    override fun isDirectory() = false

    override fun compileHeader0(): C_MidModuleHeader? {
        val symCtx = ctx.symCtxProvider.getSymbolContext(file.path)
        val modifierCtx = C_ModifierContext(ctx.msgCtx, symCtx)
        return file.compileHeader(modifierCtx)
    }

    override fun compile0(): List<C_MidModuleFile> {
        val compiled = file.compile(ctx)
        return listOf(compiled)
    }
}

class C_DirModuleSource(
        ctx: C_ModuleSourceContext,
        val path: C_SourcePath,
        files: List<C_ParsedRellFile>,
        private val mainFile: C_ParsedRellFile?
): C_ModuleSource(ctx) {
    private val files = files.toImmList()

    override fun isDirectory() = true

    override fun compileHeader0(): C_MidModuleHeader? {
        mainFile ?: return null
        val symCtx = ctx.symCtxProvider.getSymbolContext(mainFile.path)
        val modifierCtx = C_ModifierContext(ctx.msgCtx, symCtx)
        return mainFile.compileHeader(modifierCtx)
    }

    override fun compile0(): List<C_MidModuleFile> {
        return files.sortedBy { it.path }
                .map { it.compile(ctx) }
                .toImmList()
    }
}

class C_ParsedRellFile(val path: C_SourcePath, private val ast: S_RellFile?) {
    fun compileHeader(modifierCtx: C_ModifierContext): C_MidModuleHeader? {
        return ast?.compileHeader(modifierCtx)
    }

    fun compile(ctx: C_ModuleSourceContext): C_MidModuleFile {
        return ast?.compile(ctx, path) ?: C_MidModuleFile(path, immListOf(), null, C_NopSymbolContext)
    }

    override fun toString() = path.toString()
}
