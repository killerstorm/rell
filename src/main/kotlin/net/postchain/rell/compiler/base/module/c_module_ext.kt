/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.module

import net.postchain.rell.compiler.ast.S_BasicDefinition
import net.postchain.rell.compiler.ast.S_ImportTarget
import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.modifier.C_MountAnnotationValue
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_LateInit
import net.postchain.rell.compiler.base.utils.C_RQualifiedName
import net.postchain.rell.compiler.base.utils.C_SourcePath
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_MountName
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

data class C_ExtChainName(val name: String) {
    fun toExtChain(appCtx: C_AppContext): C_ExternalChain = appCtx.addExternalChain(name)
}

class C_ExtModule(
        val midModule: C_MidModule,
        val chain: C_ExtChainName?,
        files: List<C_ExtModuleFile>
) {
    private val files = files.toImmList()

    fun compileFiles(modCtx: C_ModuleContext) = files.map { it.compile(modCtx) }
}

class C_ExtModuleFile(
        private val path: C_SourcePath,
        members: List<C_ExtModuleMember>,
        private val symCtx: C_SymbolContext
) {
    private val members = members.toImmList()

    fun compile(modCtx: C_ModuleContext): C_CompiledRellFile {
        modCtx.executor.checkPass(C_CompilerPass.DEFINITIONS)

        val actualSymCtx = if (modCtx.extChain != null) C_NopSymbolContext else symCtx
        val fileCtx = C_FileContext(modCtx, actualSymCtx)
        val mntCtx = fileCtx.createMountContext()

        compileMembers(mntCtx)

        val mntTables = fileCtx.mntBuilder.build()
        val fileContents = fileCtx.createContents()

        return C_CompiledRellFile(path, mntTables, fileContents)
    }

    private fun compileMembers(mntCtx: C_MountContext) {
        for (mem in members) {
            mem.compile(mntCtx)
        }
    }
}

sealed class C_ExtModuleMember {
    protected abstract fun compile0(mntCtx: C_MountContext)

    fun compile(mntCtx: C_MountContext) {
        mntCtx.msgCtx.consumeError {
            compile0(mntCtx)
        }
    }
}

class C_ExtModuleMember_Basic(private val def: S_BasicDefinition): C_ExtModuleMember() {
    override fun compile0(mntCtx: C_MountContext) {
        def.compileBasic(mntCtx)
    }
}

class C_ExtModuleMember_Import(
        private val importDef: C_ImportDefinition,
        private val target: S_ImportTarget,
        private val moduleName: R_ModuleName,
        private val extChainName: C_ExtChainName?
): C_ExtModuleMember() {
    override fun compile0(mntCtx: C_MountContext) {
        val pos = importDef.pos
        val extChain = extChainName?.toExtChain(mntCtx.appCtx)

        val module = mntCtx.modCtx.getModule(moduleName, extChain)
        if (module == null) {
            mntCtx.msgCtx.error(pos, C_Errors.msgModuleNotFound(moduleName))
            return
        }

        target.addToNamespace(mntCtx, importDef, module.key)

        if ((extChain != null || mntCtx.modCtx.external) && !module.header.external) {
            mntCtx.msgCtx.error(pos, "import:module_not_external:$moduleName", "Module '$moduleName' is not external")
        }

        if (module.header.test && !(mntCtx.modCtx.test || mntCtx.modCtx.repl)) {
            mntCtx.msgCtx.error(pos, "import:module_test:$moduleName",
                    "Cannot import a test module '$moduleName' from a non-test module")
        }

        mntCtx.fileCtx.addImport(C_ImportDescriptor(pos, module))
    }
}

class C_ExtModuleMember_Namespace(
        private val qualifiedName: C_QualifiedName?,
        members: List<C_ExtModuleMember>,
        private val mount: C_MountAnnotationValue?,
        private val extChainName: C_ExtChainName?
): C_ExtModuleMember() {
    private val members = members.toImmList()

    override fun compile0(mntCtx: C_MountContext) {
        val subMntCtx = createSubMountContext(mntCtx)
        for (member in members) {
            member.compile(subMntCtx)
        }
    }

    private fun createSubMountContext(mntCtx: C_MountContext): C_MountContext {
        val extChain = extChainName?.toExtChain(mntCtx.appCtx)
        val subMountName = mntCtx.mountName(mount, qualifiedName)

        if (qualifiedName == null) {
            return C_MountContext(mntCtx.fileCtx, mntCtx.nsCtx, extChain, mntCtx.nsBuilder, subMountName)
        }

        var nsBuilder = mntCtx.nsBuilder
        var nsCtx = mntCtx.nsCtx

        for (name in qualifiedName.parts) {
            nsBuilder = nsBuilder.addNamespace(name, true)
            val nsQualifiedName = nsCtx.namespaceName?.add(name.rName) ?: C_RQualifiedName.of(name.rName)
            val subScopeBuilder = nsCtx.scopeBuilder.nested(nsBuilder.futureNs())
            nsCtx = C_NamespaceContext(mntCtx.modCtx, mntCtx.symCtx, nsQualifiedName, subScopeBuilder)
        }

        return C_MountContext(mntCtx.fileCtx, nsCtx, extChain, nsBuilder, subMountName)
    }
}

class C_ExtModuleCompiler(
        private val appCtx: C_AppContext,
        extModules: List<C_ExtModule>,
        preModules: Map<C_ModuleKey, C_PrecompiledModule>
) {
    private val extModules = extModules.toImmList()

    private val headers = compileHeaders()
    private val bases = extModules.map { compileModuleBasis(it, headers) }

    val modProvider = C_ModuleProvider(bases.associate { it.module.key to it.module }, preModules)

    private var done = false

    fun compileModules() {
        appCtx.executor.checkPass(C_CompilerPass.DEFINITIONS)
        check(!done)
        done = true

        for (base in bases) {
            base.compile(appCtx, modProvider)
        }
    }

    private fun compileHeaders(): Map<R_ModuleName, C_ModuleHeader> {
        val headers = mutableMapOf<R_ModuleName, C_ModuleHeader>()

        val sortedMidModules = extModules.map { it.midModule }.sortedBy { it.moduleName.size() }.toImmList()

        for (midModule in sortedMidModules) {
            val parentHeader = if (midModule.parentName == null) null else headers[midModule.parentName]
            headers[midModule.moduleName] = midModule.compileHeader(appCtx.msgCtx, parentHeader)
        }

        return headers.toImmMap()
    }

    private fun compileModuleBasis(extModule: C_ExtModule, headers: Map<R_ModuleName, C_ModuleHeader>): C_ModuleBasis {
        val midModule = extModule.midModule

        val parentName = midModule.parentName
        val parentModuleKey = if (parentName == null) null else C_ModuleKey(parentName, null)

        val parentHeader = headers[parentName]
        val parentMountName = parentHeader?.mountName ?: R_MountName.EMPTY

        val header = headers[midModule.moduleName] ?: C_ModuleHeader.empty(parentMountName)

        val extChain = extModule.chain?.toExtChain(appCtx)
        val moduleKey = C_ModuleKey(midModule.moduleName, extChain)

        val internalsLate = C_LateInit(C_CompilerPass.MODULES, C_ModuleInternals.empty(moduleKey))
        val importsGetter = internalsLate.getter.transform { it.importsDescriptor }
        val descriptor = C_ModuleDescriptor(moduleKey, header, midModule.isDirectory, importsGetter)

        val module = C_Module(
                appCtx.executor,
                descriptor,
                parentModuleKey,
                internalsLate.getter
        )

        return C_ModuleBasis(extModule, module, internalsLate)
    }
}

private class C_ModuleBasis(
        val extModule: C_ExtModule,
        val module: C_Module,
        private val internalsLate: C_LateInit<C_ModuleInternals>
) {
    fun compile(appCtx: C_AppContext, modProvider: C_ModuleProvider) {
        checkParentModule(appCtx.msgCtx, modProvider)

        val modCtx = C_RegularModuleContext(
                appCtx,
                modProvider,
                module,
                isTestDependency = extModule.midModule.isTestDependency
        )

        val compiledFiles = extModule.compileFiles(modCtx)

        appCtx.executor.onPass(C_CompilerPass.MODULES) {
            val compiled = C_ModuleCompiler.compile(modCtx, compiledFiles)
            internalsLate.set(C_ModuleInternals(compiled.contents, compiled.importsDescriptor))
            appCtx.addModule(module.descriptor, compiled)
        }
    }

    private fun checkParentModule(msgCtx: C_MessageContext, modProvider: C_ModuleProvider) {
        val midModule = extModule.midModule
        val startPos = midModule.startPos
        val header = module.header
        val parentHeader = module.parentKey?.let { modProvider.getModule(it.name, it.extChain)?.header }

        if (startPos != null && !header.test && parentHeader != null && parentHeader.test) {
            val name = midModule.moduleName
            val parentName = midModule.parentName
            msgCtx.error(startPos, "module:parent_is_test:$name:$parentName",
                    "Parent module of '$name' is a test module '$parentName'")
        }
    }
}
