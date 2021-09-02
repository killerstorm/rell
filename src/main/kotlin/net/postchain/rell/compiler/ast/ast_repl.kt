/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
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
        val preModuleNames = preModules.map { it.key.name }.toImmSet()
        val modLdr = C_ModuleLoader(msgCtx, sourceDir, preModuleNames)

        if (currentModuleName != null) {
            modLdr.loadModule(currentModuleName)
        }

        val srcCtx = modLdr.readerCtx.createModuleSourceContext(currentModuleName ?: R_ModuleName.EMPTY)
        val midMembers = defs.mapNotNull { it.compile(srcCtx) }
        val midModules = modLdr.getLoadedModules()

        val midCompiler = C_MidModuleCompiler(msgCtx, midModules)
        if (currentModuleName != null) {
            midCompiler.compileModule(currentModuleName, null)
        }

        val extMembers = midCompiler.compileMembers(midMembers)
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
