/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.module

import com.google.common.collect.Multimap
import net.postchain.rell.compiler.ast.S_RellFile
import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.modifier.C_ModifierContext
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_QualifiedName
import net.postchain.rell.tools.api.*
import net.postchain.rell.utils.*

object C_ModuleUtils {
    const val FILE_SUFFIX = ".rell"
    const val MODULE_FILE = "module$FILE_SUFFIX"

    private val RELL_MODULE = R_ModuleName.of("rell")

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

        val moduleName = R_ModuleName.of(rNames)
        return Pair(moduleName, directory)
    }

    fun isAllowedModuleName(name: R_ModuleName) = !name.startsWith(RELL_MODULE)
}

class C_ModuleReaderContext(val appCtx: S_AppContext) {
    val msgCtx = appCtx.msgCtx
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
        private val sourceDir: C_SourceDir,
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
                    val parsedFile = C_ParsedRellFile(fileNode.path, fileNode.idePath, ast)
                    modFiles.add(parsedFile)
                    if (isMainFile) {
                        mainFile = parsedFile
                    }
                }
            }

            return if (modFiles.isEmpty()) null else {
                C_DirModuleSource(readerCtx.appCtx, moduleName, path, modFiles.toImmList(), mainFile)
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
            private val sourceFile: C_SourceFile,
    ): TreeNode(path) {
        val idePath = sourceFile.idePath()

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
                sourceFile.readAst()
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
                C_FileModuleSource(readerCtx.appCtx, moduleName, C_ParsedRellFile(path, idePath, ast))
            }
        }
    }
}

class S_AppContext(
    val msgCtx: C_MessageContext,
    val symCtxProvider: C_SymbolContextProvider,
    val importLoader: C_ImportModuleLoader,
) {
    fun <T> withModuleContext(moduleName: R_ModuleName, code: (S_ModuleContext) -> T): T {
        val modCtx = S_PrivateModuleContext(this, moduleName)
        val res = code(modCtx)
        modCtx.finish()
        return res
    }
}

sealed class S_ModuleContext(val appCtx: S_AppContext, val moduleName: R_ModuleName) {
    val msgCtx = appCtx.msgCtx

    abstract fun createFileContext(path: C_SourcePath, idePath: IdeFilePath): S_FileContext
}

private class S_PrivateModuleContext(appCtx: S_AppContext, moduleName: R_ModuleName): S_ModuleContext(appCtx, moduleName) {
    private val files = mutableSetOf<C_SourcePath>()
    private val namespaces = mutableMultimapOf<R_QualifiedName, NamespaceNameInfoRec>()
    private var finished = false

    override fun createFileContext(path: C_SourcePath, idePath: IdeFilePath): S_FileContext {
        check(files.add(path)) { "$moduleName $path" }
        val symCtx = appCtx.symCtxProvider.getSymbolContext(path)
        return S_PrivateFileContext(this, symCtx, path, idePath, namespaces)
    }

    fun finish() {
        check(!finished)
        finished = true

        for (name in namespaces.keySet()) {
            val recs = namespaces[name].toList()
            for ((i, rec) in recs.withIndex()) {
                val link = if (recs.size == 1) null else recs[(i + 1) % recs.size].link
                val ideInfo = IdeSymbolInfo(IdeSymbolKind.DEF_NAMESPACE, defId = rec.defId, link = link)
                rec.nameHand.setIdeInfo(ideInfo)
            }
        }
    }
}

sealed class S_FileContext(
    val modCtx: S_ModuleContext,
    val symCtx: C_SymbolContext,
    val path: C_SourcePath,
    val idePath: IdeFilePath,
) {
    fun createDefinitionContext(): S_DefinitionContext {
        //val defIdeInfo = IdeSymbolInfo(IdeSymbolKind.DEF_NAMESPACE, defId = ideId)
        //nameHand.setIdeInfo(ideDef.defInfo)
        return S_DefinitionContext.root(this)
    }

    abstract fun addNamespaceName(nameHand: C_NameHandle, fullName: R_QualifiedName): IdeSymbolId
}

private class S_PrivateFileContext(
    modCtx: S_ModuleContext,
    symCtx: C_SymbolContext,
    path: C_SourcePath,
    idePath: IdeFilePath,
    private val modNamespaces: Multimap<R_QualifiedName, NamespaceNameInfoRec>,
): S_FileContext(modCtx, symCtx, path, idePath) {
    private val namespaces = mutableMultisetOf<R_QualifiedName>()

    override fun addNamespaceName(nameHand: C_NameHandle, fullName: R_QualifiedName): IdeSymbolId {
        val count = namespaces.count(fullName)
        val fullNameStr = fullName.str()
        val defName = if (count == 0) fullNameStr else "$fullNameStr:$count"
        val defId = IdeSymbolId(IdeSymbolCategory.NAMESPACE, defName, immListOf())
        val link = IdeGlobalSymbolLink(IdeSymbolGlobalId(nameHand.pos.idePath(), defId))
        val rec = NamespaceNameInfoRec(nameHand, defId, link)
        namespaces.add(fullName)
        modNamespaces.put(fullName, rec)
        return defId
    }
}

private class NamespaceNameInfoRec(val nameHand: C_NameHandle, val defId: IdeSymbolId, val link: IdeSymbolLink)

class S_DefinitionContext private constructor(val fileCtx: S_FileContext, val namespacePath: C_RNamePath) {
    val modCtx = fileCtx.modCtx
    val appCtx = modCtx.appCtx
    val msgCtx = modCtx.msgCtx
    val symCtx = fileCtx.symCtx

    val moduleName = modCtx.moduleName

    fun namespace(path: C_RNamePath): S_DefinitionContext {
        val subNamespacePath = namespacePath.child(path.parts)
        return S_DefinitionContext(fileCtx, subNamespacePath)
    }

    companion object {
        fun root(fileCtx: S_FileContext): S_DefinitionContext {
            return S_DefinitionContext(fileCtx, C_RNamePath.EMPTY)
        }
    }
}

sealed class C_ModuleSource(protected val appCtx: S_AppContext, val moduleName: R_ModuleName) {
    private val compiledHeader by lazy {
        compileHeader0()
    }

    private val compiledFiles by lazy {
        appCtx.withModuleContext(moduleName) { modCtx ->
            compile0(modCtx)
        }
    }

    abstract fun isDirectory(): Boolean
    abstract fun idePath(): IdeFilePath

    protected abstract fun compileHeader0(): C_MidModuleHeader?
    protected abstract fun compile0(modCtx: S_ModuleContext): List<C_MidModuleFile>

    fun compileHeader(): C_MidModuleHeader? = compiledHeader
    fun compile(): List<C_MidModuleFile> = compiledFiles
}

class C_FileModuleSource(
        appCtx: S_AppContext,
        moduleName: R_ModuleName,
        val file: C_ParsedRellFile,
): C_ModuleSource(appCtx, moduleName) {
    override fun isDirectory() = false
    override fun idePath() = file.idePath

    override fun compileHeader0(): C_MidModuleHeader? {
        val symCtx = appCtx.symCtxProvider.getSymbolContext(file.path)
        val modifierCtx = C_ModifierContext(appCtx.msgCtx, symCtx)
        return file.compileHeader(modifierCtx)
    }

    override fun compile0(modCtx: S_ModuleContext): List<C_MidModuleFile> {
        val compiled = file.compile(modCtx)
        return listOf(compiled)
    }
}

class C_DirModuleSource(
        appCtx: S_AppContext,
        moduleName: R_ModuleName,
        val path: C_SourcePath,
        files: List<C_ParsedRellFile>,
        private val mainFile: C_ParsedRellFile?,
): C_ModuleSource(appCtx, moduleName) {
    private val files = files.sortedBy { it.path }.toImmList()

    init {
        check(this.files.isNotEmpty())
    }

    private val idePath = mainFile?.idePath ?: this.files.first().idePath

    override fun isDirectory() = true
    override fun idePath() = idePath

    override fun compileHeader0(): C_MidModuleHeader? {
        mainFile ?: return null
        val symCtx = appCtx.symCtxProvider.getSymbolContext(mainFile.path)
        val modifierCtx = C_ModifierContext(appCtx.msgCtx, symCtx)
        return mainFile.compileHeader(modifierCtx)
    }

    override fun compile0(modCtx: S_ModuleContext): List<C_MidModuleFile> {
        return files.map { it.compile(modCtx) }.toImmList()
    }
}

class C_ParsedRellFile(
    val path: C_SourcePath,
    val idePath: IdeFilePath,
    private val ast: S_RellFile?,
) {
    fun compileHeader(modifierCtx: C_ModifierContext): C_MidModuleHeader? {
        return ast?.compileHeader(modifierCtx)
    }

    fun compile(modCtx: S_ModuleContext): C_MidModuleFile {
        val moduleName = modCtx.moduleName
        val pos = ast?.startPos
        if (!C_ModuleUtils.isAllowedModuleName(moduleName) && pos != null) {
            modCtx.msgCtx.error(pos, "module:reserved_name:${moduleName}", "Defining a module called '${moduleName}' is not allowed")
        }

        ast ?: return C_MidModuleFile(path, immListOf(), null, C_NopSymbolContext)

        val fileCtx = modCtx.createFileContext(path, idePath)
        return ast.compile(fileCtx)
    }

    override fun toString() = path.toString()
}
