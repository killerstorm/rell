/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.core.C_ExtReplCommand
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.core.C_SymbolContextManager
import net.postchain.rell.compiler.base.module.C_MidModuleCompiler
import net.postchain.rell.compiler.base.module.C_ModuleKey
import net.postchain.rell.compiler.base.module.C_ModuleLoader
import net.postchain.rell.compiler.base.module.C_PrecompiledModule
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.compiler.base.utils.C_SourcePath
import net.postchain.rell.compiler.base.utils.IdeSourcePathFilePath
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmSet

class S_ReplCommand(steps: List<S_ReplStep>, expr: S_Expr?) {
    private val defs = steps.mapNotNull { it.definition() }.toImmList()
    private val stmts = (steps.mapNotNull { it.statement() } + listOfNotNull(expr).map { S_ExprStatement(it) }).toImmList()

    fun compile(
            msgCtx: C_MessageContext,
            sourceDir: C_SourceDir,
            currentModuleName: R_ModuleName?,
            preModules: Map<C_ModuleKey, C_PrecompiledModule>
    ): C_ExtReplCommand {
        val symCtxManager = C_SymbolContextManager(C_CompilerOptions.DEFAULT)
        val preModuleNames = preModules.map { it.key.name }.toImmSet()
        val modLdr = C_ModuleLoader(msgCtx, symCtxManager.provider, sourceDir, preModuleNames)

        if (currentModuleName != null) {
            modLdr.loadModule(currentModuleName)
        }

        val midMembers = modLdr.readerCtx.appCtx.withModuleContext(currentModuleName ?: R_ModuleName.EMPTY) { modCtx ->
            val sourcePath = C_SourcePath.EMPTY
            val idePath = IdeSourcePathFilePath(sourcePath)
            val fileCtx = modCtx.createFileContext(sourcePath, idePath)
            val defCtx = fileCtx.createDefinitionContext()
            defs.mapNotNull { it.compile(defCtx) }
        }

        val midModules = modLdr.finish()

        val midCompiler = C_MidModuleCompiler(msgCtx, midModules)
        if (currentModuleName != null) {
            midCompiler.compileModule(currentModuleName, null)
        }

        val extMembers = midCompiler.compileReplMembers(midMembers)
        val extModules = midCompiler.getExtModules()

        return C_ExtReplCommand(extModules, extMembers, currentModuleName, stmts, preModules)
    }
}

sealed class S_ReplStep {
    abstract fun definition(): S_Definition?
    abstract fun statement(): S_Statement?
}

class S_DefinitionReplStep(val def: S_Definition): S_ReplStep() {
    override fun definition() = def
    override fun statement() = null
}

class S_StatementReplStep(val stmt: S_Statement): S_ReplStep() {
    override fun definition() = null
    override fun statement() = stmt
}
