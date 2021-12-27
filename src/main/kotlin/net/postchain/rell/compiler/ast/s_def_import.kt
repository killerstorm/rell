/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.module.*
import net.postchain.rell.compiler.base.namespace.C_UserNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_CommonError
import net.postchain.rell.compiler.base.utils.C_MessageManager
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.immListOf

private val VALID_MODULE_NAME_INFO = IdeSymbolInfo(IdeSymbolKind.DEF_IMPORT_MODULE)
private val INVALID_MODULE_NAME_INFO = IdeSymbolInfo.UNKNOWN

class C_ImportModulePathHandle(
        val moduleName: R_ModuleName,
        val implicitAlias: C_Name?,
        private val nameHand: C_QualifiedNameHandle?
) {
    fun setValid(valid: Boolean) {
        val symInfo = if (valid) VALID_MODULE_NAME_INFO else INVALID_MODULE_NAME_INFO
        nameHand?.setIdeInfo(symInfo)
    }
}

class S_RelativeImportModulePath(val pos: S_Pos, val ups: Int)

class S_ImportModulePath(
        private val relative: S_RelativeImportModulePath?,
        private val moduleName: S_QualifiedName?
) {
    fun ideImplicitAlias() = moduleName?.last

    fun compile(
            msgMgr: C_MessageManager,
            symCtx: C_SymbolContext,
            importPos: S_Pos,
            currentModule: R_ModuleName
    ): C_ImportModulePathHandle? {
        val nameHand = moduleName?.compile(symCtx)

        val cModuleName = nameHand?.qName
        val rModuleName = compileModuleName(msgMgr, importPos, currentModule, cModuleName)

        return if (rModuleName == null) {
            nameHand?.setIdeInfo(INVALID_MODULE_NAME_INFO)
            null
        } else {
            val implicitAlias = cModuleName?.last
            C_ImportModulePathHandle(rModuleName, implicitAlias, nameHand)
        }
    }

    private fun compileModuleName(
            msgMgr: C_MessageManager,
            importPos: S_Pos,
            currentModule: R_ModuleName,
            cModuleName: C_QualifiedName?
    ): R_ModuleName? {
        val rPath = cModuleName?.parts?.map { it.rName } ?: immListOf()

        if (relative == null) {
            if (rPath.isEmpty()) {
                msgMgr.error(importPos, "import:no_path", "Module not specified")
                return null
            }
            return R_ModuleName(rPath)
        }

        if (relative.ups > currentModule.parts.size) {
            val code = "import:up:${currentModule.parts.size}:${relative.ups}"
            val msg = "Cannot go up by ${relative.ups}, current module is '${currentModule}'"
            msgMgr.error(relative.pos, code, msg)
            return null
        }

        val base = currentModule.parts.subList(0, currentModule.parts.size - relative.ups)
        val full = base + rPath
        return R_ModuleName(full)
    }
}

sealed class S_ImportTarget {
    abstract fun aliasIdeInfo(): IdeSymbolInfo
    abstract fun addToNamespace(ctx: C_MountContext, def: C_ImportDefinition, module: C_ModuleKey)

    protected fun getNsBuilder(ctx: C_MountContext, alias: C_Name?): C_UserNsProtoBuilder {
        return if (alias == null) ctx.nsBuilder else ctx.nsBuilder.addNamespace(alias, false)
    }
}

class S_DefaultImportTarget: S_ImportTarget() {
    override fun aliasIdeInfo() = IdeSymbolInfo(IdeSymbolKind.DEF_IMPORT_ALIAS)

    override fun addToNamespace(ctx: C_MountContext, def: C_ImportDefinition, module: C_ModuleKey) {
        val alias = def.alias ?: def.implicitAlias
        if (alias == null) {
            ctx.msgCtx.error(def.pos, "import:no_alias", "Cannot infer an alias, specify import alias explicitly")
            return
        }

        val ideKind = if (def.alias != null) IdeSymbolKind.EXPR_IMPORT_ALIAS else IdeSymbolKind.DEF_IMPORT_MODULE
        val ideInfo = IdeSymbolInfo(ideKind)

        ctx.nsBuilder.addModuleImport(alias, module, ideInfo)
    }
}

class S_ExactImportTargetItem(
        private val alias: S_Name?,
        private val name: S_QualifiedName,
        private val wildcard: Boolean
) {
    fun addToNamespace(symCtx: C_SymbolContext, nsBuilder: C_UserNsProtoBuilder, module: C_ModuleKey) {
        val aliasHand = alias?.compile(symCtx)
        val nameHand = name.compile(symCtx)

        if (wildcard) {
            val nsBuilder2 = if (aliasHand == null) nsBuilder else {
                aliasHand.setIdeInfo(IdeSymbolInfo(IdeSymbolKind.DEF_NAMESPACE))
                nsBuilder.addNamespace(aliasHand.name, false)
            }
            nsBuilder2.addWildcardImport(module, nameHand.parts)
        } else {
            val realAlias = aliasHand ?: nameHand.last
            nsBuilder.addExactImport(realAlias.name, module, nameHand, aliasHand)
        }
    }
}

class S_ExactImportTarget(val items: List<S_ExactImportTargetItem>): S_ImportTarget() {
    override fun aliasIdeInfo() = IdeSymbolInfo(IdeSymbolKind.DEF_NAMESPACE)

    override fun addToNamespace(ctx: C_MountContext, def: C_ImportDefinition, module: C_ModuleKey) {
        val nsBuilder = getNsBuilder(ctx, def.alias)
        for (item in items) {
            item.addToNamespace(ctx.symCtx, nsBuilder, module)
        }
    }
}

class S_WildcardImportTarget: S_ImportTarget() {
    override fun aliasIdeInfo() = IdeSymbolInfo(IdeSymbolKind.DEF_NAMESPACE)

    override fun addToNamespace(ctx: C_MountContext, def: C_ImportDefinition, module: C_ModuleKey) {
        val nsBuilder = getNsBuilder(ctx, def.alias)
        nsBuilder.addWildcardImport(module, listOf())
    }
}

class S_ImportDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        private val alias: S_Name?,
        private val modulePath: S_ImportModulePath,
        private val target: S_ImportTarget
): S_Definition(pos, modifiers) {
    override fun compile(ctx: C_ModuleDefinitionContext): C_MidModuleMember? {
        val cModulePath = modulePath.compile(ctx.msgCtx.msgMgr, ctx.symCtx, kwPos, ctx.moduleName)
        cModulePath ?: return null

        val validModule = try {
            ctx.loadModule(cModulePath.moduleName)
        } catch (e: C_CommonError) {
            ctx.msgCtx.error(kwPos, e.code, e.msg)
            cModulePath.setValid(false)
            return null
        }

        cModulePath.setValid(validModule)

        val aliasIdeInfo = target.aliasIdeInfo()
        val cAlias = alias?.compile(ctx.symCtx, aliasIdeInfo)

        val importDef = C_ImportDefinition(kwPos, cAlias, cModulePath.implicitAlias)

        return C_MidModuleMember_Import(modifiers, importDef, target, cModulePath.moduleName)
    }

    override fun ideGetImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        val msgMgr = C_MessageManager()
        val cModulePath = modulePath.compile(msgMgr, C_NopSymbolContext, kwPos, moduleName)
        if (cModulePath != null) {
            res.add(cModulePath.moduleName)
        }
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val alias = alias ?: modulePath.ideImplicitAlias()
        if (alias != null) {
            b.node(this, alias, IdeOutlineNodeType.IMPORT)
        }
    }
}
