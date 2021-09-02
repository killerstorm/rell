package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_RellFile
import net.postchain.rell.compiler.ast.S_Statement
import net.postchain.rell.model.*
import net.postchain.rell.repl.*
import net.postchain.rell.utils.*

class C_ExtReplCommand(
        extModules: List<C_ExtModule>,
        extMembers: List<C_ExtModuleMember>,
        private val currentModuleName: R_ModuleName?,
        statements: List<S_Statement>,
        preModules: Map<C_ModuleKey, C_PrecompiledModule>
) {
    private val extModules = extModules.toImmList()
    private val extMembers = extMembers.toImmList()
    private val statements = statements.toImmList()
    private val preModules = preModules.toImmMap()

    fun compile(appCtx: C_AppContext, codeState: ReplCodeState): C_LateGetter<ReplCode> {
        val extCompiler = C_ExtModuleCompiler(appCtx, extModules, preModules)
        extCompiler.compileModules()

        if (currentModuleName != null) {
            val md = extCompiler.modProvider.getModule(currentModuleName, null)
            md ?: throw C_CommonError(C_Errors.msgModuleNotFound(currentModuleName))
        }

        val mntCtx = createMountContext(appCtx, extCompiler.modProvider)
        extMembers.forEach { it.compile(mntCtx) }

        val replCtx = createReplContext(mntCtx, codeState)
        compileStatements(replCtx)

        return replCtx.commandGetter
    }

    private fun createMountContext(appCtx: C_AppContext, modProvider: C_ModuleProvider): C_MountContext {
        val currentModuleKey = currentModuleName?.let { C_ModuleKey(it, null) }
        val replNsAssembler = appCtx.createReplNsAssembler(currentModuleKey)
        val componentNsAssembler = replNsAssembler.addComponent()

        val modCtx = C_ReplModuleContext(
                appCtx,
                modProvider,
                currentModuleName ?: R_ModuleName.EMPTY,
                replNsAssembler.futureNs(),
                componentNsAssembler.futureNs()
        )

        val fileCtx = C_FileContext(modCtx)

        appCtx.executor.onPass(C_CompilerPass.MODULES) {
            val mntTables = fileCtx.mntBuilder.build()
            appCtx.addExtraMountTables(mntTables)
        }

        return S_RellFile.createMountContext(fileCtx, R_MountName.EMPTY, componentNsAssembler)
    }

    private fun createReplContext(mntCtx: C_MountContext, codeState: ReplCodeState): C_ReplCommandContext {
        val stmtVars = discoverStatementVars()
        val defId = R_DefinitionId("", "<REPL>")
        val defCtx = C_DefinitionContext(mntCtx, C_DefinitionType.REPL, defId)
        val fnCtx = C_FunctionContext(defCtx, "<REPL>", null, stmtVars)
        val frameCtx = C_FrameContext.create(fnCtx, codeState.cState.frameProto)
        return C_ReplCommandContext(frameCtx, codeState)
    }

    private fun discoverStatementVars(): TypedKeyMap {
        val map = MutableTypedKeyMap()
        for (stmt in statements) {
            stmt.discoverVars(map)
        }
        return map.immutableCopy()
    }

    private fun compileStatements(ctx: C_ReplCommandContext) {
        val stmtCtx = C_StmtContext.createRoot(ctx.frameCtx.rootBlkCtx)

        ctx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            val builder = C_BlockCodeBuilder(stmtCtx, true, false, ctx.codeState.cState.blockCodeProto)

            for (stmt in statements) {
                builder.add(stmt)
            }

            val blockCode = builder.build()
            val replCode = createReplCode(ctx, blockCode)
            ctx.setCommand(replCode)
        }
    }

    private fun createReplCode(ctx: C_ReplCommandContext, blockCode: C_BlockCode): ReplCode {
        val callFrame = ctx.frameCtx.makeCallFrame(false)
        val rCommand = R_ReplCode(callFrame.rFrame, blockCode.rStmts)
        val blockCodeProto = blockCode.createProto()
        val cState = C_ReplCodeState(callFrame.proto, blockCodeProto)
        return ReplCode(rCommand, cState, ctx.codeState.rtState)
    }
}

object C_ReplCompiler {
    fun compile(
            sourceDir: C_SourceDir,
            currentModuleName: R_ModuleName?,
            code: String,
            globalCtx: C_GlobalContext,
            oldDefsState: C_ReplDefsState,
            oldCodeState: ReplCodeState
    ): C_ReplResult {
        val msgCtx = C_MessageContext(globalCtx)

        val extCommand = msgCtx.consumeError {
            val ast = C_Parser.parseRepl(code)
            ast.compile(msgCtx, sourceDir, currentModuleName, oldDefsState.appState.modules)
        }
        extCommand ?: return C_ReplResult(null, msgCtx.messages())

        val controller = C_CompilerController(msgCtx)
        val res = C_LateInit.context(controller.executor) {
            compile0(msgCtx, controller, extCommand, oldDefsState, oldCodeState)
        }

        return res
    }

    private fun compile0(
            msgCtx: C_MessageContext,
            controller: C_CompilerController,
            extCommand: C_ExtReplCommand,
            oldDefsState: C_ReplDefsState,
            oldCodeState: ReplCodeState
    ): C_ReplResult {
        val executor = controller.executor
        val appCtx = C_AppContext(msgCtx, executor, true, oldDefsState.appState)

        val codeGetter = msgCtx.consumeError {
            extCommand.compile(appCtx, oldCodeState)
        }

        controller.run()

        val app = appCtx.getApp()
        val messages = CommonUtils.sortedByCopy(msgCtx.messages()) { C_ComparablePos(it.pos) }
        val errors = messages.filter { it.type == C_MessageType.ERROR }

        val success = if (app == null || codeGetter == null || errors.isNotEmpty()) null else {
            val cCode = codeGetter.get()
            val newAppState = appCtx.getNewReplState()
            val newState = C_ReplDefsState(newAppState)
            C_ReplSuccess(app, newState, cCode)
        }

        return C_ReplResult(success, messages)
    }
}

class C_ReplAppState(
        val nsAsmState: C_NsAsm_ReplState,
        modules: Map<C_ModuleKey, C_PrecompiledModule>,
        val sysDefs: C_SystemDefs?,
        val sqlDefs: R_AppSqlDefs,
        val mntTables: C_MountTables,
        constants: List<R_GlobalConstantDefinition>
) {
    val modules = modules.toImmMap()
    val constants = constants.toImmList()

    companion object {
        val EMPTY = C_ReplAppState(
                C_NsAsm_ReplState.EMPTY,
                mapOf(),
                null,
                R_AppSqlDefs.EMPTY,
                C_MountTables.EMPTY,
                listOf()
        )
    }
}

class C_ReplDefsState(val appState: C_ReplAppState) {
    companion object {
        val EMPTY = C_ReplDefsState(C_ReplAppState.EMPTY)
    }
}

class C_ReplSuccess(val app: R_App, val defsState: C_ReplDefsState, val code: ReplCode)
class C_ReplResult(val success: C_ReplSuccess?, messages: List<C_Message>): C_AbstractResult(messages)
