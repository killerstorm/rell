/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.module

import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.utils.C_CommonError
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.compiler.base.utils.C_ValidationManager
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.utils.queueOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmSet

class C_ImportModuleLoader(private val loader: C_ModuleLoader) {
    fun loadModule(name: R_ModuleName) {
        loader.loadModule(name)
    }
}

class C_ModuleLoader(
        msgCtx: C_MessageContext,
        sourceDir: C_SourceDir,
        preModuleNames: Set<R_ModuleName>
) {
    private val preModuleNames = preModuleNames.toImmSet()

    private val valMgr = C_ValidationManager(msgCtx)

    val readerCtx = C_ModuleReaderContext(msgCtx, valMgr.executor, C_ImportModuleLoader(this))
    private val moduleReader = C_ModuleReader(readerCtx, sourceDir)

    private val testLoader = C_TestModuleLoader(moduleReader, this::loadTestModule)

    private val loadedModules = mutableMapOf<R_ModuleName, C_ModuleState>()
    private val moduleQueue = queueOf<C_ModuleState>()

    private val midModules = mutableListOf<C_MidModule>()
    private var done = false

    fun getLoadedModules(): List<C_MidModule> {
        check(!done)
        done = true
        valMgr.execute()
        return midModules.toImmList()
    }

    fun loadModule(name: R_ModuleName) {
        check(!done)

        if (isModuleLoaded(name)) {
            return
        }

        val source = moduleReader.readModuleSource(name)
        source ?: return

        addModule(name, source)
        loadQueuedModules()
    }

    private fun addModule(name: R_ModuleName, source: C_ModuleSource) {
        val state = C_ModuleState(name, source)
        loadedModules[name] = state
        moduleQueue.add(state)
    }

    fun loadTestModules(rootModule: R_ModuleName) {
        check(!done)
        testLoader.compileTestModules(rootModule)
        loadQueuedModules()
    }

    private fun loadTestModule(source: C_ModuleSource) {
        val moduleName = source.moduleName
        if (isModuleLoaded(moduleName)) {
            return
        }

        val header = source.compileHeader()

        if (header != null && header.test) {
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

    private inner class C_ModuleState(
            val moduleName: R_ModuleName,
            source: C_ModuleSource
    ) {
        private var modSource: C_ModuleSource? = source

        fun load() {
            val source = modSource!!
            modSource = null

            val header = source.compileHeader()
            val parentName = if (header != null && header.test) null else findParentModule(moduleName)

            val midFiles = source.compile()
            val midModule = C_MidModule(moduleName, parentName, header, midFiles)

            midModules.add(midModule)

            if (parentName != null) {
                loadModule(parentName)
            }
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
