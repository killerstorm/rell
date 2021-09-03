/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.module.C_MidModuleFile
import net.postchain.rell.compiler.base.module.C_MidModuleHeader
import net.postchain.rell.compiler.base.module.C_ModuleSourceContext
import net.postchain.rell.compiler.base.namespace.C_NsAsm_ComponentAssembler
import net.postchain.rell.compiler.base.namespace.C_UserNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_SourcePath
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_MountName
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder

sealed class S_Modifier(val pos: S_Pos) {
    abstract fun compile(ctx: C_ModifierContext, target: C_ModifierTarget)
    open fun ideIsTestFile(): Boolean = false
}

sealed class S_KeywordModifier(protected val kw: S_String): S_Modifier(kw.pos)

class S_KeywordModifier_Abstract(kw: S_String): S_KeywordModifier(kw) {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        C_Modifier.compileModifier(ctx, kw, target, target.abstract, true)
        target.checkAbstractTest(ctx.msgCtx, kw.pos, target.test)
    }
}

class S_KeywordModifier_Override(kw: S_String): S_KeywordModifier(kw) {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        C_Modifier.compileModifier(ctx, kw, target, target.override, true)
    }
}

class S_Annotation(val name: S_Name, val args: List<S_LiteralExpr>): S_Modifier(name.pos) {
    override fun compile(ctx: C_ModifierContext, target: C_ModifierTarget) {
        val argValues = args.map { it.value() }
        C_Modifier.compileAnnotation(ctx, name, argValues, target)
    }

    override fun ideIsTestFile() = name.str == C_Modifier.TEST
}

class S_Modifiers(val modifiers: List<S_Modifier>) {
    val pos = modifiers.firstOrNull()?.pos

    fun compile(modifierCtx: C_ModifierContext, target: C_ModifierTarget) {
        for (modifier in modifiers) {
            modifier.compile(modifierCtx, target)
        }
    }

    fun compile(ctx: C_MountContext, target: C_ModifierTarget) {
        val modifierCtx = C_ModifierContext(ctx.msgCtx, ctx.appCtx.valExec)
        compile(modifierCtx, target)
    }
}

class S_ModuleHeader(val modifiers: S_Modifiers, val pos: S_Pos) {
    fun compile(ctx: C_ModifierContext): C_MidModuleHeader {
        val modTarget = C_ModifierTarget(
                C_ModifierTargetType.MODULE,
                null,
                abstract = true,
                externalModule = true,
                mount = true,
                emptyMountAllowed = true,
                test = true
        )

        modifiers.compile(ctx, modTarget)

        val abstract = modTarget.abstract?.get() ?: false
        val abstractPos = if (abstract) pos else null

        val external = modTarget.externalModule?.get() ?: false
        val test = modTarget.test?.get() ?: false

        return C_MidModuleHeader(pos, modTarget.mount?.get(), abstractPos, external, test)
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
        val members = definitions.mapNotNull { it.compile(ctx) }
        return C_MidModuleFile(path, members, startPos)
    }

    fun ideGetImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        for (def in definitions) {
            def.ideGetImportedModules(moduleName, res)
        }
    }

    fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        for (def in definitions) {
            def.ideBuildOutlineTree(b)
        }
    }

    fun ideIsTestFile(): Boolean {
        return header != null && header.ideIsTestFile()
    }

    companion object {
        fun createMountContext(fileCtx: C_FileContext, mountName: R_MountName, nsAssembler: C_NsAsm_ComponentAssembler): C_MountContext {
            val modCtx = fileCtx.modCtx
            val nsBuilder = C_UserNsProtoBuilder(nsAssembler)
            val fileScopeBuilder = modCtx.scopeBuilder.nested(nsAssembler.futureNs())
            val nsCtx = C_NamespaceContext(modCtx, null, fileScopeBuilder)
            return C_MountContext(fileCtx, nsCtx, modCtx.extChain, nsBuilder, mountName)
        }
    }
}
