/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.module

import net.postchain.rell.base.compiler.ast.C_ImportTarget
import net.postchain.rell.base.compiler.ast.S_BasicDefinition
import net.postchain.rell.base.compiler.ast.S_Modifiers
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.compiler.base.modifier.*
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceMemberBase
import net.postchain.rell.base.compiler.base.utils.C_SourcePath
import net.postchain.rell.base.model.R_EnumDefinition
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.model.R_MountName
import net.postchain.rell.base.utils.CommonUtils
import net.postchain.rell.base.utils.queueOf
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap

class C_MidModuleContext(
        val msgCtx: C_MessageContext,
        val modImporter: C_MidModuleImporter,
        val extChain: C_ExtChainName?
)

class C_MidMemberContext(
        val modCtx: C_MidModuleContext,
        val modifierCtx: C_ModifierContext,
        val extChain: C_ExtChainName?
) {
    fun externalChain(modExternal: C_ModifierValue<C_ExtChainName>): C_ExtChainName? {
        val v = modExternal.value()
        return v ?: extChain
    }
}

class C_MidModuleHeader(
        val pos: S_Pos,
        private val rawMount: C_RawMountAnnotationValue?,
        val abstract: S_Pos?,
        val external: Boolean,
        val test: Boolean
) {
    fun compile(msgCtx: C_MessageContext, parentMountName: R_MountName): C_ModuleHeader {
        val mount = rawMount?.process(true)
        val mountName = mount?.calculateMountName(msgCtx, parentMountName) ?: parentMountName
        return C_ModuleHeader(mountName, abstract = abstract != null, external = external, test = test)
    }
}

class C_MidModule(
        val moduleName: R_ModuleName,
        val parentName: R_ModuleName?,
        val header: C_MidModuleHeader?,
        files: List<C_MidModuleFile>,
        val isDirectory: Boolean,
        val isTestDependency: Boolean,
        val isSelected: Boolean,
) {
    private val files = files.toImmList()

    val startPos = header?.pos ?: files.firstOrNull()?.startPos
    val isTest = header?.test ?: false

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
    val startPos: S_Pos?,
    private val symCtx: C_SymbolContext,
) {
    val members = members.toImmList()

    fun compile(ctx: C_MidModuleContext): C_ExtModuleFile {
        val modifierCtx = C_ModifierContext(ctx.msgCtx, symCtx)
        val memCtx = C_MidMemberContext(ctx, modifierCtx, ctx.extChain)
        val extMembers = members.map { it.compile(memCtx) }
        return C_ExtModuleFile(path, extMembers, symCtx)
    }

    override fun toString() = path.toString()
}

sealed class C_MidModuleMember {
    abstract fun compile(ctx: C_MidMemberContext): C_ExtModuleMember
}

class C_MidModuleMember_Basic(
        private val def: S_BasicDefinition
): C_MidModuleMember() {
    override fun compile(ctx: C_MidMemberContext): C_ExtModuleMember {
        return C_ExtModuleMember_Basic(def)
    }
}

class C_MidModuleMember_Enum(
    private val cName: C_Name,
    private val rEnum: R_EnumDefinition,
    private val memBase: C_NamespaceMemberBase,
): C_MidModuleMember() {
    override fun compile(ctx: C_MidMemberContext): C_ExtModuleMember {
        return C_ExtModuleMember_Enum(cName, rEnum, memBase)
    }
}

class C_ImportDefinition(
        val pos: S_Pos,
        val alias: C_Name?,
        val implicitAlias: C_Name?
)

class C_MidModuleMember_Import(
    private val modifiers: S_Modifiers,
    private val importDef: C_ImportDefinition,
    private val target: C_ImportTarget,
    private val moduleName: R_ModuleName,
): C_MidModuleMember() {
    override fun compile(ctx: C_MidMemberContext): C_ExtModuleMember {
        val mods = C_ModifierValues(C_ModifierTargetType.IMPORT, null)
        val modExternal = mods.field(C_ModifierFields.EXTERNAL_CHAIN)
        modifiers.compile(ctx.modifierCtx, mods)

        val extChainName = ctx.externalChain(modExternal)
        ctx.modCtx.modImporter.importModule(moduleName, extChainName)

        return C_ExtModuleMember_Import(importDef, target, moduleName, extChainName)
    }
}

class C_MidModuleMember_Namespace(
    private val modifiers: S_Modifiers,
    private val qualifiedName: C_IdeQualifiedName?,
    members: List<C_MidModuleMember>,
): C_MidModuleMember() {
    private val members = members.toImmList()

    override fun compile(ctx: C_MidMemberContext): C_ExtModuleMember {
        val mods = C_ModifierValues(C_ModifierTargetType.NAMESPACE, name = qualifiedName?.last?.name)
        val modExternal = mods.field(C_ModifierFields.EXTERNAL_CHAIN)
        val modMount = mods.field(C_ModifierFields.MOUNT)
        val modDeprecated = if (qualifiedName == null) null else mods.field(C_ModifierFields.DEPRECATED)
        modifiers.compile(ctx.modifierCtx, mods)

        val mount = modMount.value()?.process(true)
        val extChainName = ctx.externalChain(modExternal)

        val subCtx = C_MidMemberContext(ctx.modCtx, ctx.modifierCtx, extChainName)
        val subMembers = members.map { it.compile(subCtx) }

        return C_ExtModuleMember_Namespace(qualifiedName, subMembers, mount, extChainName, modDeprecated?.value())
    }
}

class C_MidModuleCompiler(
        private val msgCtx: C_MessageContext,
        midModules: List<C_MidModule>
) {
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

    fun compileReplMembers(members: List<C_MidModuleMember>): List<C_ExtModuleMember> {
        check(!done)

        val moduleCtx = C_MidModuleContext(msgCtx, modImporter, null)
        val modifierCtx = C_ModifierContext(msgCtx, C_NopSymbolContext)
        val memberCtx = C_MidMemberContext(moduleCtx, modifierCtx, null)
        val res = members.map { it.compile(memberCtx) }.toImmList()

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
        val midCtx = C_MidModuleContext(msgCtx, modImporter, extChain)
        val extModule = midModule.compile(midCtx)
        extModules.add(extModule)
    }

    fun getExtModules(): List<C_ExtModule> {
        done = true
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
