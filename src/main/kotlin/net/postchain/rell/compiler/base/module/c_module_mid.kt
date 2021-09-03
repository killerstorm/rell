/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.module

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.utils.C_SourcePath
import net.postchain.rell.compiler.base.utils.C_ValidationExecutor
import net.postchain.rell.compiler.base.utils.C_ValidationManager
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_MountName
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.queueOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

class C_MidModuleContext(
        val msgCtx: C_MessageContext,
        valExec: C_ValidationExecutor,
        val modImporter: C_MidModuleImporter,
        val extChain: C_ExtChainName?
) {
    val modifierCtx = C_ModifierContext(msgCtx, valExec)
}

class C_MidMemberContext(val modCtx: C_MidModuleContext, val extChain: C_ExtChainName?)

class C_MidModuleHeader(
        val pos: S_Pos,
        val mount: C_MountAnnotation?,
        val abstract: S_Pos?,
        val external: Boolean,
        val test: Boolean
) {
    fun compile(msgCtx: C_MessageContext, parentMountName: R_MountName): C_ModuleHeader {
        val mountName = mount?.calculateMountName(msgCtx, parentMountName) ?: parentMountName
        return C_ModuleHeader(mountName, abstract = abstract != null, external = external, test = test)
    }
}

class C_MidModule(
        val moduleName: R_ModuleName,
        val parentName: R_ModuleName?,
        val header: C_MidModuleHeader?,
        files: List<C_MidModuleFile>
) {
    private val files = files.toImmList()

    val startPos = header?.pos ?: files.firstOrNull()?.startPos

    fun filePaths() = files.map { it.path }.toImmList()

    fun compileHeader(msgCtx: C_MessageContext, parentHeader: C_ModuleHeader?): C_ModuleHeader {
        val parentMountName = parentHeader?.mountName ?: R_MountName.EMPTY
        return header?.compile(msgCtx, parentMountName) ?: C_ModuleHeader.empty(parentMountName)
    }

    fun compile(ctx: C_MidModuleContext): C_ExtModule {
        val extFiles = files.map { it.compile(ctx) }
        return C_ExtModule(this, ctx.extChain, extFiles)
    }

    override fun toString() = moduleName.toString()
}

class C_MidModuleFile(
        val path: C_SourcePath,
        members: List<C_MidModuleMember>,
        val startPos: S_Pos?
) {
    val members = members.toImmList()

    fun compile(ctx: C_MidModuleContext): C_ExtModuleFile {
        val memCtx = C_MidMemberContext(ctx, ctx.extChain)
        val extMembers = members.map { it.compile(memCtx) }
        return C_ExtModuleFile(path, extMembers)
    }

    override fun toString() = path.toString()
}

sealed class C_MidModuleMember {
    abstract fun compile(ctx: C_MidMemberContext): C_ExtModuleMember
}

class C_MidModuleMember_Basic(private val def: S_BasicDefinition): C_MidModuleMember() {
    override fun compile(ctx: C_MidMemberContext): C_ExtModuleMember {
        return C_ExtModuleMember_Basic(def)
    }
}

class C_ImportDefinition(
        val pos: S_Pos,
        val alias: S_Name?,
        val implicitAlias: S_Name?
)

class C_MidModuleMember_Import(
        private val modifiers: S_Modifiers,
        private val importDef: C_ImportDefinition,
        private val target: S_ImportTarget,
        private val moduleName: R_ModuleName
): C_MidModuleMember() {
    override fun compile(ctx: C_MidMemberContext): C_ExtModuleMember {
        val modTarget = C_ModifierTarget(C_ModifierTargetType.IMPORT, null, externalChain = true)
        modifiers.compile(ctx.modCtx.modifierCtx, modTarget)

        val extChainName = modTarget.externalChain(ctx.extChain)
        ctx.modCtx.modImporter.importModule(moduleName, extChainName)

        return C_ExtModuleMember_Import(importDef, target, moduleName, extChainName)
    }
}

class C_MidModuleMember_Namespace(
        private val modifiers: S_Modifiers,
        fullName: List<S_Name>,
        members: List<C_MidModuleMember>
): C_MidModuleMember() {
    private val fullName = fullName.toImmList()
    private val members = members.toImmList()

    override fun compile(ctx: C_MidMemberContext): C_ExtModuleMember {
        val modTarget = C_ModifierTarget(
                C_ModifierTargetType.NAMESPACE,
                name = fullName.lastOrNull(),
                externalChain = true,
                mount = true,
                emptyMountAllowed = true
        )

        modifiers.compile(ctx.modCtx.modifierCtx, modTarget)

        val mount = modTarget.mount?.get()
        val extChainName = modTarget.externalChain(ctx.extChain)

        val subCtx = C_MidMemberContext(ctx.modCtx, extChainName)
        val subMembers = members.map { it.compile(subCtx) }

        return C_ExtModuleMember_Namespace(fullName, subMembers, mount, extChainName)
    }
}

class C_MidModuleCompiler(
        private val msgCtx: C_MessageContext,
        midModules: List<C_MidModule>
) {
    private val valMgr = C_ValidationManager(msgCtx)
    private val valExec = valMgr.executor

    private val midModulesMap = midModules.associateBy { it.moduleName }.toImmMap()

    private val modImporter = C_MidModuleImporter_Impl()

    private var done = false
    private val compiledModules = mutableSetOf<C_ExtModuleKey>()
    private val moduleQueue = queueOf<Pair<C_MidModule, C_ExtChainName?>>()
    private val extModules = mutableListOf<C_ExtModule>()

    fun compileModule(name: R_ModuleName, extChain: C_ExtChainName?) {
        check(!done)
        importModule(name, extChain)
        processQueue()
    }

    fun compileMembers(members: List<C_MidModuleMember>): List<C_ExtModuleMember> {
        check(!done)

        val modCtx = C_MidModuleContext(msgCtx, valExec, modImporter, null)
        val memCtx = C_MidMemberContext(modCtx, null)
        val res = members.map { it.compile(memCtx) }.toImmList()

        processQueue()
        return res
    }

    private fun importModule(name: R_ModuleName, extChain: C_ExtChainName?) {
        check(!done)

        val midModule = midModulesMap[name]
        midModule ?: return

        processParentModules(midModule)
        addModuleToQueue(midModule, extChain)
    }

    private fun processParentModules(midModule: C_MidModule) {
        val modules = CommonUtils.chainToList(midModule) {
            if (it.parentName == null) null else midModulesMap[it.parentName]
        }

        for (parent in modules.subList(1, modules.size).asReversed()) {
            addModuleToQueue(parent, null)
        }
    }

    private fun addModuleToQueue(midModule: C_MidModule, extChain: C_ExtChainName?) {
        val key = C_ExtModuleKey(midModule.moduleName, extChain)
        if (compiledModules.add(key)) {
            moduleQueue.add(midModule to extChain)
        }
    }

    private fun processQueue() {
        while (moduleQueue.isNotEmpty()) {
            val (midModule, extChain) = moduleQueue.remove()
            processModule(midModule, extChain)
        }
    }

    private fun processModule(midModule: C_MidModule, extChain: C_ExtChainName?) {
        val midCtx = C_MidModuleContext(msgCtx, valExec, modImporter, extChain)
        val extModule = midModule.compile(midCtx)
        extModules.add(extModule)
    }

    fun getExtModules(): List<C_ExtModule> {
        if (!done) {
            done = true
            valMgr.execute()
        }
        return extModules.toImmList()
    }

    private inner class C_MidModuleImporter_Impl: C_MidModuleImporter() {
        override fun importModule(name: R_ModuleName, extChainName: C_ExtChainName?) {
            this@C_MidModuleCompiler.importModule(name, extChainName)
        }
    }

    private data class C_ExtModuleKey(val name: R_ModuleName, val chain: C_ExtChainName?)
}

abstract class C_MidModuleImporter {
    abstract fun importModule(name: R_ModuleName, extChainName: C_ExtChainName?)
}
