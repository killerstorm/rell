/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.utils.MutableTypedKeyMap
import net.postchain.rell.utils.TypedKeyMap
import net.postchain.rell.compiler.*
import net.postchain.rell.repl.*
import net.postchain.rell.utils.toImmList

class S_ReplCommand(steps: List<S_ReplStep>, expr: S_Expr?) {
    private val defs = steps.map { it.definition() }.filterNotNull().toImmList()

    private val stmts: List<S_Statement>

    init {
        val list = steps.map { it.statement() }.filterNotNull().toMutableList()
        if (expr != null) {
            list.add(S_ExprStatement(expr))
        }
        stmts = list.toImmList()
    }

    fun compile(mntCtx: C_MountContext, codeState: ReplCodeState): C_LateGetter<ReplCode> {
        val ctx = createContext(mntCtx, codeState)
        compileDefs(ctx)
        compileStatements(ctx)
        return ctx.commandGetter
    }

    private fun createContext(mntCtx: C_MountContext, codeState: ReplCodeState): C_ReplCommandContext {
        val stmtVars = discoverStatementVars()
        val defCtx = C_DefinitionContext(mntCtx, C_DefinitionType.REPL)
        val fnCtx = C_FunctionContext(defCtx, "<REPL>", null, stmtVars)
        val frameCtx = C_FrameContext.create(fnCtx, codeState.cState.frameProto)
        return C_ReplCommandContext(frameCtx, codeState)
    }

    private fun compileStatements(ctx: C_ReplCommandContext) {
        val stmtCtx = C_StmtContext.createRoot(ctx.frameCtx.rootBlkCtx)

        ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            val builder = C_BlockCodeBuilder(stmtCtx, true, ctx.codeState.cState.blockCodeProto)
            compileBlock(builder)
            val blockCode = builder.build()
            val replCode = createReplCode(ctx, blockCode)
            ctx.setCommand(replCode)
        }
    }

    private fun createReplCode(ctx: C_ReplCommandContext, blockCode: C_BlockCode): ReplCode {
        val callFrame = ctx.frameCtx.makeCallFrame()
        val rCommand = R_ReplCode(callFrame.rFrame, blockCode.rStmts)
        val blockCodeProto = blockCode.createProto()
        val cState = C_ReplCodeState(callFrame.proto, blockCodeProto)
        return ReplCode(rCommand, cState, ctx.codeState.rtState)
    }

    private fun discoverStatementVars(): TypedKeyMap {
        val map = MutableTypedKeyMap()
        for (stmt in stmts) {
            stmt.discoverVars(map)
        }
        return map.immutableCopy()
    }

    private fun compileDefs(ctx: C_ReplCommandContext) {
        for (def in defs) {
            def.compile(ctx.mntCtx)
        }
    }

    private fun compileBlock(builder: C_BlockCodeBuilder) {
        for (stmt in stmts) {
            builder.add(stmt)
        }
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
