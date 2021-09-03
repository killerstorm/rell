/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.C_MountContext
import net.postchain.rell.compiler.base.module.*
import net.postchain.rell.compiler.base.namespace.C_UserNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_CommonError
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.tools.api.IdeOutlineNodeType
import net.postchain.rell.tools.api.IdeOutlineTreeBuilder

class S_RelativeImportModulePath(val pos: S_Pos, val ups: Int)

class S_ImportModulePath(val relative: S_RelativeImportModulePath?, val path: List<S_Name>) {
    fun compile(importPos: S_Pos, currentModule: R_ModuleName): R_ModuleName {
        val rPath = path.map { it.rName }

        if (relative == null) {
            C_Errors.check(path.isNotEmpty(), importPos, "import:no_path", "Module not specified")
            return R_ModuleName(rPath)
        }

        C_Errors.check(relative.ups <= currentModule.parts.size, relative.pos) {
            "import:up:${currentModule.parts.size}:${relative.ups}" to
                    "Cannot go up by ${relative.ups}, current module is '${currentModule}'"
        }

        val base = currentModule.parts.subList(0, currentModule.parts.size - relative.ups)
        val full = base + rPath
        return R_ModuleName(full)
    }

    fun getAlias(): S_Name? {
        return if (path.isEmpty()) null else path[path.size - 1]
    }
}

sealed class S_ImportTarget {
    abstract fun addToNamespace(ctx: C_MountContext, def: C_ImportDefinition, module: C_ModuleKey)

    protected fun getNsBuilder(ctx: C_MountContext, alias: S_Name?): C_UserNsProtoBuilder {
        return if (alias == null) ctx.nsBuilder else ctx.nsBuilder.addNamespace(alias, false)
    }
}

class S_DefaultImportTarget: S_ImportTarget() {
    override fun addToNamespace(ctx: C_MountContext, def: C_ImportDefinition, module: C_ModuleKey) {
        val alias = def.alias ?: def.implicitAlias
        if (alias == null) {
            ctx.msgCtx.error(def.pos, "import:no_alias", "Cannot infer an alias, specify import alias explicitly")
            return
        }
        ctx.nsBuilder.addModuleImport(alias, module)
    }
}

class S_ExactImportTargetItem(val alias: S_Name?, val name: List<S_Name>, val wildcard: Boolean) {
    fun addToNamespace(nsBuilder: C_UserNsProtoBuilder, module: C_ModuleKey) {
        check(name.isNotEmpty())
        if (wildcard) {
            val nsBuilder2 = if (alias == null) nsBuilder else nsBuilder.addNamespace(alias, false)
            nsBuilder2.addWildcardImport(module, name)
        } else {
            val lastName = name.last()
            val realAlias = alias ?: lastName
            nsBuilder.addExactImport(realAlias, module, name.subList(0, name.size - 1), lastName)
        }
    }
}

class S_ExactImportTarget(val items: List<S_ExactImportTargetItem>): S_ImportTarget() {
    override fun addToNamespace(ctx: C_MountContext, def: C_ImportDefinition, module: C_ModuleKey) {
        val nsBuilder = getNsBuilder(ctx, def.alias)
        for (item in items) {
            item.addToNamespace(nsBuilder, module)
        }
    }
}

class S_WildcardImportTarget: S_ImportTarget() {
    override fun addToNamespace(ctx: C_MountContext, def: C_ImportDefinition, module: C_ModuleKey) {
        val nsBuilder = getNsBuilder(ctx, def.alias)
        nsBuilder.addWildcardImport(module, listOf())
    }
}

class S_ImportDefinition(
        pos: S_Pos,
        modifiers: S_Modifiers,
        val alias: S_Name?,
        val modulePath: S_ImportModulePath,
        val target: S_ImportTarget
): S_Definition(pos, modifiers) {
    override fun compile(ctx: C_ModuleSourceContext): C_MidModuleMember? {
        val moduleName = ctx.msgCtx.consumeError { modulePath.compile(kwPos, ctx.moduleName) }
        moduleName ?: return null

        try {
            ctx.loadModule(moduleName)
        } catch (e: C_CommonError) {
            ctx.msgCtx.error(kwPos, e.code, e.msg)
            return null
        }

        val implicitAlias = modulePath.getAlias()
        val importDef = C_ImportDefinition(kwPos, alias, implicitAlias)

        return C_MidModuleMember_Import(modifiers, importDef, target, moduleName)
    }

    override fun ideGetImportedModules(moduleName: R_ModuleName, res: MutableSet<R_ModuleName>) {
        val impModuleName = try {
            modulePath.compile(kwPos, moduleName)
        } catch (e: C_Error) {
            return
        }
        res.add(impModuleName)
    }

    override fun ideBuildOutlineTree(b: IdeOutlineTreeBuilder) {
        val alias = getActualAlias()
        if (alias != null) {
            b.node(this, alias, IdeOutlineNodeType.IMPORT)
        }
    }

    private fun getActualAlias() = alias ?: modulePath.getAlias()
}
