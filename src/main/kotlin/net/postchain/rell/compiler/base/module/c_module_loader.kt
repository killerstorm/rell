/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.module

import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.core.C_SymbolContextProvider
import net.postchain.rell.compiler.base.utils.C_CommonError
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.tools.api.IdeFilePath
import net.postchain.rell.utils.queueOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import net.postchain.rell.utils.toImmSet

class C_ModuleInfo(val idePath: IdeFilePath?)

sealed class C_ImportModuleLoader {
    abstract fun loadModule(name: R_ModuleName)

    // Load the specified module. If the module doesn't exist, loads the deepest existing parent module.
    // Returns info about all existing parent modules.
    abstract fun loadModuleEx(name: R_ModuleName): Map<R_ModuleName, C_ModuleInfo>
}

class C_ModuleLoader(
        msgCtx: C_MessageContext,
        symCtxProvider: C_SymbolContextProvider,
        sourceDir: C_SourceDir,
        preModuleNames: Set<R_ModuleName>
) {
    private val preModuleNames = preModuleNames.toImmSet()

    val readerCtx = C_ModuleReaderContext(S_AppContext(msgCtx, symCtxProvider, C_ImportModuleLoaderImpl()))
    private val moduleReader = C_ModuleReader(readerCtx, sourceDir)

    private val testLoader = C_TestModuleLoader(moduleReader, this::loadTestModule)

    private val loadedModules = mutableMapOf<R_ModuleName, C_ModuleState>()
    private val moduleQueue = queueOf<C_ModuleState>()
    private val selectedModules = mutableSetOf<R_ModuleName>()
    private var loadingTestDependencies = false // Looks like a hack (depends on methods invocation order), but fine.

    private val resultModules = mutableListOf<C_LoaderModule>()
    private var done = false

    fun finish(): List<C_MidModule> {
        check(!done)
        done = true
        val midModules = resultModules.map { it.toMidModule(it.moduleName in selectedModules) }
        return midModules.toImmList()
    }

    fun loadModule(name: R_ModuleName): Boolean {
        return loadModule0(name, true)
    }

    private fun loadModule0(name: R_ModuleName, select: Boolean): Boolean {
        check(!done)

        if (select) {
            selectedModules.add(name)
        }

        val loaded = getLoadedModule(name)
        if (loaded != null) {
            return true
        }

        val source = moduleReader.readModuleSource(name)
        source ?: return false

        addModule(name, source)
        loadQueuedModules()
        return true
    }

    private fun addModule(name: R_ModuleName, source: C_ModuleSource) {
        val state = C_ModuleState(name, source)
        loadedModules[name] = state
        moduleQueue.add(state)
    }

    fun loadTestModules(rootModule: R_ModuleName) {
        check(!done)
        loadingTestDependencies = true
        testLoader.compileTestModules(rootModule)
        loadQueuedModules()
    }

    private fun loadTestModule(source: C_ModuleSource) {
        val moduleName = source.moduleName
        val header = source.compileHeader()

        if (header != null && header.test) {
            selectedModules.add(moduleName)
            addModule(moduleName, source)
        }
    }

    private fun loadQueuedModules() {
        while (true) {
            val state = moduleQueue.poll()
            state ?: break
            state.load()
        }
    }

    private fun findParentModule(name: R_ModuleName): R_ModuleName? {
        // Need to check all parent modules, not just the direct (immediate) parent, because the direct parent
        // not necessarily exists.

        var curName = name

        while (!curName.isEmpty()) {
            curName = curName.parent()

            if (isModuleLoaded(curName)) {
                return curName
            }

            val source = try {
                moduleReader.readModuleSource(curName)
            } catch (e: C_CommonError) {
                // ignore
                null
            }

            if (source != null) {
                addModule(curName, source)
                return curName
            }
        }

        return null
    }

    private fun isModuleLoaded(name: R_ModuleName) = name in loadedModules || name in preModuleNames

    private fun getLoadedModule(name: R_ModuleName): C_ModuleInfo? {
        return if (name in preModuleNames) {
            C_ModuleInfo(null)
        } else {
            val state = loadedModules[name]
            if (state == null) null else C_ModuleInfo(state.idePath)
        }
    }

    private inner class C_ImportModuleLoaderImpl: C_ImportModuleLoader() {
        override fun loadModule(name: R_ModuleName) {
            loadModule0(name, false)
        }

        override fun loadModuleEx(name: R_ModuleName): Map<R_ModuleName, C_ModuleInfo> {
            var curName = name
            while (!loadModule0(curName, false) && !curName.isEmpty()) {
                curName = curName.parent()
            }

            val res = mutableMapOf<R_ModuleName, C_ModuleInfo>()
            while (true) {
                val state = loadedModules[curName]
                if (state != null) {
                    res[curName] = C_ModuleInfo(state.idePath)
                } else if (curName in preModuleNames) {
                    res[curName] = C_ModuleInfo(null)
                }
                if (curName.isEmpty()) break
                curName = curName.parent()
            }

            return res.toImmMap()
        }
    }

    private inner class C_ModuleState(
            val moduleName: R_ModuleName,
            source: C_ModuleSource
    ) {
        val idePath = source.idePath()

        private var modSource: C_ModuleSource? = source

        fun load() {
            val source = modSource!!
            modSource = null

            val header = source.compileHeader()
            val parentName = if (header != null && header.test) null else findParentModule(moduleName)

            val midFiles = source.compile()

            val ldrModule = C_LoaderModule(
                    moduleName,
                    parentName,
                    header,
                    midFiles,
                    isDirectory = source.isDirectory(),
                    isTestDependency = loadingTestDependencies
            )

            if (C_ModuleUtils.isAllowedModuleName(moduleName)) {
                resultModules.add(ldrModule)
            }

            if (parentName != null) {
                loadModule0(parentName, false)
            }
        }
    }

    private class C_LoaderModule(
        val moduleName: R_ModuleName,
        val parentName: R_ModuleName?,
        val header: C_MidModuleHeader?,
        val files: List<C_MidModuleFile>,
        val isDirectory: Boolean,
        val isTestDependency: Boolean
    ) {
        fun toMidModule(isSelected: Boolean): C_MidModule {
            return C_MidModule(
                moduleName = moduleName,
                parentName = parentName,
                header = header,
                files = files,
                isDirectory = isDirectory,
                isTestDependency = isTestDependency,
                isSelected = isSelected,
            )
        }
    }
}

private class C_TestModuleLoader(
        private val moduleReader: C_ModuleReader,
        private val callback: (C_ModuleSource) -> Unit
) {
    fun compileTestModules(rootModule: R_ModuleName) {
        val source = moduleReader.readModuleSource(rootModule)

        if (source == null) {
            if (!moduleReader.dirExists(rootModule)) {
                throw C_CommonError(C_Errors.msgModuleNotFound(rootModule))
            }
            compileTestModulesTree(rootModule)
            return
        }

        compileTestModule(source)

        return when (source) {
            is C_FileModuleSource -> {
                // Do nothing.
            }
            is C_DirModuleSource -> {
                compileTestModulesTree(rootModule)
            }
        }
    }

    private fun compileTestModulesTree(moduleName: R_ModuleName) {
        val fileSources = moduleReader.fileSubModules(moduleName)

        for (source in fileSources) {
            compileTestModule(source)
        }

        val dirSubModules = moduleReader.dirSubModules(moduleName)

        for (subModuleName in dirSubModules) {
            val source = moduleReader.readModuleSource(subModuleName)
            if (source != null) {
                compileTestModule(source)
            }
            compileTestModulesTree(subModuleName)
        }
    }

    private fun compileTestModule(source: C_ModuleSource) {
        callback(source)
    }
}
