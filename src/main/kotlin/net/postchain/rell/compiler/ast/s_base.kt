/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.C_FileContext
import net.postchain.rell.compiler.base.core.C_MountContext
import net.postchain.rell.compiler.base.core.C_NamespaceContext
import net.postchain.rell.compiler.base.modifier.*
import net.postchain.rell.compiler.base.module.C_MidModuleFile
import net.postchain.rell.compiler.base.module.C_MidModuleHeader
import net.postchain.rell.compiler.base.module.C_ModuleSourceContext
import net.postchain.rell.compiler.base.module.C_ModuleUtils
import net.postchain.rell.compiler.base.namespace.C_NsAsm_ComponentAssembler
import net.postchain.rell.compiler.base.namespace.C_UserNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_SourcePath
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_MountName
import net.postchain.rell.tools.api.IdeModuleInfo
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import net.postchain.rell.utils.toImmSet

class S_ModuleHeader(val modifiers: S_Modifiers, val pos: S_Pos) {
    fun compile(ctx: C_ModifierContext): C_MidModuleHeader {
        val mods = C_ModifierValues(C_ModifierTargetType.MODULE, null)
        val modAbstract = mods.field(C_ModifierFields.ABSTRACT)
        val modExternal = mods.field(C_ModifierFields.EXTERNAL_MODULE)
        val modMount = mods.field(C_ModifierFields.MOUNT)
        val modTest = mods.field(C_ModifierFields.TEST)

        modifiers.compile(ctx, mods)
        C_AnnUtils.checkModsZeroOne(ctx.msgCtx, modAbstract, modTest)
        C_AnnUtils.checkModsZeroOne(ctx.msgCtx, modMount, modTest)

        val mount = modMount.value()
        val abstractPos = modAbstract.pos()
        val external = modExternal.hasValue()
        val test = modTest.hasValue()

        return C_MidModuleHeader(pos, mount, abstractPos, external, test)
    }

    fun ideIsTestFile(): Boolean {
        return modifiers.modifiers.any { it.ideIsTestFile() }
    }
}

class S_RellFile(val header: S_ModuleHeader?, val definitions: List<S_Definition>): S_Node() {
    val startPos = header?.pos ?: definitions.firstOrNull()?.startPos

    fun compileHeader(modifierCtx: C_ModifierContext): C_MidModuleHeader? {
        return header?.compile(modifierCtx)
    }

    fun compile(ctx: C_ModuleSourceContext, path: C_SourcePath): C_MidModuleFile {
        val defCtx = ctx.createDefinitionContext(path)
        val members = definitions.mapNotNull { it.compile(defCtx) }
        return C_MidModuleFile(path, members, startPos, defCtx.symCtx)
    }

    fun ideModuleInfo(path: C_SourcePath): IdeModuleInfo? {
        val (moduleName, directory) = C_ModuleUtils.getModuleInfo(path, this)
        moduleName ?: return null

        val imports = mutableSetOf<R_ModuleName>()
        for (def in definitions) {
            def.ideGetImportedModules(moduleName, imports)
        }

        val test = header != null && header.ideIsTestFile()

        return IdeModuleInfo(moduleName, directory, app = !test, test = test, imports = imports.toImmSet())
    }

    fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        for (def in definitions) {
            def.ideBuildOutlineTree(b)
        }
    }

    companion object {
        fun createMountContext(
                fileCtx: C_FileContext,
                mountName: R_MountName,
                nsAssembler: C_NsAsm_ComponentAssembler
        ): C_MountContext {
            val modCtx = fileCtx.modCtx
            val nsBuilder = C_UserNsProtoBuilder(nsAssembler)
            val fileScopeBuilder = modCtx.scopeBuilder.nested(nsAssembler.futureNs())
            val nsCtx = C_NamespaceContext(modCtx, fileCtx.symCtx, null, fileScopeBuilder)
            return C_MountContext(fileCtx, nsCtx, modCtx.extChain, nsBuilder, mountName)
        }
    }
}
