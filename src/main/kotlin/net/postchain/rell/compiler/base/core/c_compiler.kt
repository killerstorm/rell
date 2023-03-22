/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.core

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.def.C_MountTables
import net.postchain.rell.compiler.base.def.C_MountTablesBuilder
import net.postchain.rell.compiler.base.module.*
import net.postchain.rell.compiler.base.namespace.C_Namespace
import net.postchain.rell.compiler.base.namespace.C_SysNsProto
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.lib.C_Lib_SysQueries
import net.postchain.rell.lib.C_SystemLibraryProvider
import net.postchain.rell.model.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.*

class C_Entity(val defPos: S_Pos?, val entity: R_EntityDefinition)

enum class C_CompilerPass {
    DEFINITIONS,
    NAMESPACES,
    MODULES,
    MEMBERS,
    ABSTRACT,
    APPDEFS,
    EXPRESSIONS,
    FRAMES,
    VALIDATION,
    APPLICATION,
    FINISH
    ;

    fun prev(): C_CompilerPass {
        return VALUES[ordinal - 1]
    }

    fun next(): C_CompilerPass {
        return VALUES[ordinal + 1]
    }

    companion object {
        private val VALUES = values()

        val LAST = VALUES[VALUES.size - 1]
    }
}

class C_StatementVars(declared: Set<R_Name>, modified: Set<R_Name>) {
    val declared = declared.toImmSet()
    val modified = modified.toImmSet()

    companion object {
        val EMPTY = C_StatementVars(immSetOf(), immSetOf())
    }
}

class C_StatementVarsBlock {
    private val declared = mutableSetOf<R_Name>()
    private val modified = mutableSetOf<R_Name>()

    fun declared(names: Set<R_Name>) {
        declared.addAll(names)
    }

    fun modified(names: Set<R_Name>) {
        for (name in names) {
            if (name !in declared) {
                modified.add(name)
            }
        }
    }

    fun modified() = modified.toSet()
}

class C_SystemDefs private constructor(
        val nsProto: C_SysNsProto,
        val appNs: C_Namespace,
        val testNs: C_Namespace,
        val blockEntity: R_EntityDefinition,
        val transactionEntity: R_EntityDefinition,
        val mntTables: C_MountTables,
        entities: List<R_EntityDefinition>,
        queries: List<R_QueryDefinition>
) {
    val entities = entities.toImmList()
    val queries = queries.toImmList()

    companion object {
        fun create(appCtx: C_AppContext, stamp: R_AppUid): C_SystemDefs {
            val blockEntity = C_Utils.createBlockEntity(appCtx, null)
            val transactionEntity = C_Utils.createTransactionEntity(appCtx, null, blockEntity)
            val queries = C_Lib_SysQueries.createQueries(appCtx.executor)
            return create(appCtx.globalCtx, stamp, blockEntity, transactionEntity, queries)
        }

        fun create(
                globalCtx: C_GlobalContext,
                stamp: R_AppUid,
                blockEntity: R_EntityDefinition,
                transactionEntity: R_EntityDefinition,
                queries: List<R_QueryDefinition>
        ): C_SystemDefs {
            val sysEntities = listOf(blockEntity, transactionEntity)

            val nsProto = createNsProto(globalCtx, sysEntities, false)
            val appNs = nsProto.toNamespace()
            val testNsProto = createNsProto(globalCtx, sysEntities, true)
            val testNs = testNsProto.toNamespace()

            val mntBuilder = C_MountTablesBuilder(stamp)
            for (entity in sysEntities) mntBuilder.addSysEntity(entity)
            for (query in queries) mntBuilder.addQuery(query)
            val mntTables = mntBuilder.build()

            return C_SystemDefs(nsProto, appNs, testNs, blockEntity, transactionEntity, mntTables, sysEntities, queries)
        }

        private fun createNsProto(
                globalCtx: C_GlobalContext,
                sysEntities: List<R_EntityDefinition>,
                test: Boolean
        ): C_SysNsProto {
            val libNs = C_SystemLibraryProvider.getNsProto(test, globalCtx.compilerOptions.hiddenLib)

            val nsBuilder = C_SysNsProtoBuilder(libNs.basePath)
            nsBuilder.addAll(libNs)

            for (entity in sysEntities) {
                nsBuilder.addEntity(entity.rName, entity, IdeSymbolInfo.get(IdeSymbolKind.DEF_ENTITY))
            }

            return nsBuilder.build()
        }
    }
}

enum class C_AtAttrShadowing {
    FULL,
    PARTIAL,
    NONE,
    ;

    companion object {
        val DEFAULT = FULL
    }
}

// Instantiated in Eclipse IDE, change parameters carefully.
class C_CompilerOptions(
        val compatibility: R_LangVersion?,
        val gtv: Boolean,
        val deprecatedError: Boolean,
        val ide: Boolean,
        val blockCheck: Boolean,
        val atAttrShadowing: C_AtAttrShadowing,
        val testLib: Boolean,
        val hiddenLib: Boolean,
        val allowDbModificationsInObjectExprs: Boolean,
        val symbolInfoFile: C_SourcePath?,
        val complexWhatEnabled: Boolean,
        val ideDefIdConflictError: Boolean,
) {
    fun toPojoMap(): Map<String, Any> {
        val map = mutableMapOf(
                "gtv"  to gtv,
                "deprecatedError" to deprecatedError,
                "ide" to ide,
                "atAttrShadowing" to atAttrShadowing.name,
                "testLib" to testLib,
                "hiddenLib" to hiddenLib,
                "allowDbModificationsInObjectExprs" to allowDbModificationsInObjectExprs,
                "complexWhatEnabled" to complexWhatEnabled,
        )
        if (symbolInfoFile != null) map["symbolInfoFile"] = symbolInfoFile.str()
        if (ideDefIdConflictError != DEFAULT.ideDefIdConflictError) map["ideDefIdConflictError"] = ideDefIdConflictError
        if (compatibility != null) map["compatibility"] = compatibility.str()
        return map.toImmMap()
    }

    companion object {
        @JvmField val DEFAULT = C_CompilerOptions(
                compatibility = null,
                gtv = true,
                deprecatedError = false,
                ide = false,
                blockCheck = false,
                atAttrShadowing = C_AtAttrShadowing.DEFAULT,
                testLib = false,
                hiddenLib = false,
                allowDbModificationsInObjectExprs = true,
                symbolInfoFile = null,
                complexWhatEnabled = true,
                ideDefIdConflictError = false,
        )

        @JvmStatic fun builder() = Builder()

        @JvmStatic fun builder(options: C_CompilerOptions) = Builder(options)

        @JvmStatic fun fromPojoMap(map: Map<String, Any>): C_CompilerOptions {
            return C_CompilerOptions(
                    compatibility = (map["compatibility"] as String?)?.let { R_LangVersion.of(it) },
                    blockCheck = true,
                    gtv = map.getValue("gtv") as Boolean,
                    deprecatedError = map.getValue("deprecatedError") as Boolean,
                    atAttrShadowing = (map["atAttrShadowing"] as String?)
                            ?.let { C_AtAttrShadowing.valueOf(it) } ?: DEFAULT.atAttrShadowing,
                    ide = getBoolOpt(map, "ide", DEFAULT.ide),
                    testLib = getBoolOpt(map, "testLib", DEFAULT.testLib),
                    hiddenLib = getBoolOpt(map, "hiddenLib", DEFAULT.hiddenLib),
                    allowDbModificationsInObjectExprs =
                            getBoolOpt(map, "allowDbModificationsInObjectExprs", DEFAULT.allowDbModificationsInObjectExprs),
                    symbolInfoFile = (map["symbolInfoFile"] as String?)?.let { C_SourcePath.parse(it) },
                    complexWhatEnabled = getBoolOpt(map, "complexWhatEnabled", DEFAULT.complexWhatEnabled),
                    ideDefIdConflictError = getBoolOpt(map, "ideDefIdConflictError", DEFAULT.ideDefIdConflictError),
            )
        }

        fun forLangVersion(version: R_LangVersion): C_CompilerOptions {
            return Builder().compatibility(version).build()
        }

        private fun getBoolOpt(map: Map<String, Any>, key: String, def: Boolean): Boolean = (map[key] as Boolean?) ?: def
    }

    class Builder(proto: C_CompilerOptions = DEFAULT) {
        private var compatibility = proto.compatibility
        private var gtv = proto.gtv
        private var deprecatedError = proto.deprecatedError
        private var ide = proto.ide
        private var blockCheck = proto.blockCheck
        private var atAttrShadowing = proto.atAttrShadowing
        private var testLib = proto.testLib
        private var hiddenLib = proto.hiddenLib
        private var allowDbModificationsInObjectExprs = proto.allowDbModificationsInObjectExprs
        private var symbolInfoFile = proto.symbolInfoFile
        private var complexWhatEnabled = proto.complexWhatEnabled
        private var ideDefIdConflictError = proto.ideDefIdConflictError

        @Suppress("UNUSED") fun compatibility(v: R_LangVersion): Builder {
            compatibility = v
            return this
        }

        @Suppress("UNUSED") fun gtv(v: Boolean): Builder {
            gtv = v
            return this
        }

        @Suppress("UNUSED") fun deprecatedError(v: Boolean): Builder {
            deprecatedError = v
            return this
        }

        @Suppress("UNUSED") fun ide(v: Boolean): Builder {
            ide = v
            return this
        }

        @Suppress("UNUSED") fun blockCheck(v: Boolean): Builder {
            blockCheck = v
            return this
        }

        @Suppress("UNUSED") fun atAttrShadowing(v: C_AtAttrShadowing): Builder {
            atAttrShadowing = v
            return this
        }

        @Suppress("UNUSED") fun hiddenLib(v: Boolean): Builder {
            hiddenLib = v
            return this
        }

        @Suppress("UNUSED") fun allowDbModificationsInObjectExprs(v: Boolean): Builder {
            allowDbModificationsInObjectExprs = v
            return this
        }

        @Suppress("UNUSED") fun symbolInfoFile(v: C_SourcePath?): Builder {
            symbolInfoFile = v
            return this
        }

        @Suppress("UNUSED") fun complexWhatEnabled(v: Boolean): Builder {
            complexWhatEnabled = v
            return this
        }

        @Suppress("UNUSED") fun ideDefIdConflictError(v: Boolean): Builder {
            ideDefIdConflictError = v
            return this
        }

        fun build() = C_CompilerOptions(
                compatibility = compatibility,
                gtv = gtv,
                deprecatedError = deprecatedError,
                ide = ide,
                blockCheck = blockCheck,
                atAttrShadowing = atAttrShadowing,
                testLib = testLib,
                hiddenLib = hiddenLib,
                allowDbModificationsInObjectExprs = allowDbModificationsInObjectExprs,
                symbolInfoFile = symbolInfoFile,
                complexWhatEnabled = complexWhatEnabled,
                ideDefIdConflictError = ideDefIdConflictError,
        )
    }
}

class C_CompilerModuleSelection(
        modules: List<R_ModuleName>,
        testRootModules: List<R_ModuleName> = listOf()
) {
    val modules = modules.toImmList()
    val testRootModules = testRootModules.toImmList()
}

object C_Compiler {
    fun compile(
            sourceDir: C_SourceDir,
            modules: List<R_ModuleName>,
            options: C_CompilerOptions = C_CompilerOptions.DEFAULT
    ): C_CompilationResult {
        val modSel = C_CompilerModuleSelection(modules, listOf())
        return compile(sourceDir, modSel, options)
    }

    fun compile(
            sourceDir: C_SourceDir,
            moduleSelection: C_CompilerModuleSelection,
            options: C_CompilerOptions
    ): C_CompilationResult {
        val globalCtx = C_GlobalContext(options, sourceDir)
        val msgCtx = C_MessageContext(globalCtx)
        val controller = C_CompilerController(msgCtx)

        val res = C_LateInit.context(controller.executor) {
            compile0(sourceDir, msgCtx, controller, moduleSelection)
        }

        return res
    }

    private fun compile0(
            sourceDir: C_SourceDir,
            msgCtx: C_MessageContext,
            controller: C_CompilerController,
            moduleSelection: C_CompilerModuleSelection
    ): C_CompilationResult {
        val symCtxManager = C_SymbolContextManager(msgCtx.globalCtx.compilerOptions)
        val symCtxProvider = symCtxManager.provider

        val extModules = msgCtx.consumeError {
            compileMidModules(msgCtx, sourceDir, moduleSelection, symCtxProvider)
        } ?: immListOf()

        val appCtx = C_AppContext(msgCtx, controller.executor, false, C_ReplAppState.EMPTY)

        msgCtx.consumeError {
            val extCompiler = C_ExtModuleCompiler(appCtx, extModules, immMapOf())
            extCompiler.compileModules()
        }

        val files = extModules.flatMap { it.midModule.filePaths() }.toImmList()

        controller.run()
        val ideSymbolInfos = symCtxManager.finish()

        val app = appCtx.getApp()

        val messages = CommonUtils.sortedByCopy(msgCtx.messages()) { C_ComparablePos(it.pos) }
        val errors = messages.filter { it.type == C_MessageType.ERROR }

        val rApp = if (errors.isEmpty()) app else null
        return C_CompilationResult(rApp, messages, files, ideSymbolInfos)
    }

    private fun compileMidModules(
            msgCtx: C_MessageContext,
            sourceDir: C_SourceDir,
            modSel: C_CompilerModuleSelection,
            symCtxProvider: C_SymbolContextProvider
    ): List<C_ExtModule> {
        val midModules = loadMidModules(msgCtx, sourceDir, modSel, symCtxProvider)

        checkMainModules(msgCtx, modSel, midModules)

        val testModules = midModules.filter { it.header != null && it.header.test && it.isSelected }
        val selModules = (modSel.modules + testModules.map { it.moduleName })

        val midCompiler = C_MidModuleCompiler(msgCtx, midModules)
        for (moduleName in selModules) {
            midCompiler.compileModule(moduleName, null)
        }

        return midCompiler.getExtModules()
    }

    private fun loadMidModules(
            msgCtx: C_MessageContext,
            sourceDir: C_SourceDir,
            modSel: C_CompilerModuleSelection,
            symCtxProvider: C_SymbolContextProvider
    ): List<C_MidModule> {
        val modLdr = C_ModuleLoader(msgCtx, symCtxProvider, sourceDir, immSetOf())

        for (moduleName in modSel.modules) {
            modLdr.loadModule(moduleName)
        }

        for (moduleName in modSel.testRootModules) {
            modLdr.loadTestModules(moduleName)
        }

        return modLdr.finish()
    }

    private fun checkMainModules(
            msgCtx: C_MessageContext,
            modSel: C_CompilerModuleSelection,
            midModules: List<C_MidModule>
    ) {
        val midModulesMap = midModules.associateBy { it.moduleName }

        for (moduleName in modSel.modules) {
            val midModule = midModulesMap[moduleName]
            midModule ?: throw C_CommonError(C_Errors.msgModuleNotFound(moduleName))

            val absPos = midModule.header?.abstract
            if (absPos != null && !msgCtx.globalCtx.compilerOptions.ide) {
                msgCtx.error(absPos, "module:main_abstract:$moduleName",
                        "Module '${moduleName.str()}' is abstract, cannot be used as a main module")
            }
        }
    }
}

class C_ComparablePos(sPos: S_Pos): Comparable<C_ComparablePos> {
    private val path: C_SourcePath = sPos.path()
    private val line = sPos.line()
    private val column = sPos.column()

    override fun compareTo(other: C_ComparablePos): Int {
        var d = path.compareTo(other.path)
        if (d == 0) d = line.compareTo(other.line)
        if (d == 0) d = column.compareTo(other.column)
        return d
    }
}

abstract class C_AbstractResult(messages: List<C_Message>) {
    val messages = messages.toImmList()
    val warnings = this.messages.filter { it.type == C_MessageType.WARNING }.toImmList()
    val errors = this.messages.filter { it.type == C_MessageType.ERROR }.toImmList()
}

class C_CompilationResult(
        val app: R_App?,
        messages: List<C_Message>,
        files: List<C_SourcePath>,
        ideSymbolInfos: Map<S_Pos, IdeSymbolInfo>
): C_AbstractResult(messages) {
    val files = files.toImmList()
    val ideSymbolInfos = ideSymbolInfos.toImmMap()
}

abstract class C_CompilerExecutor {
    abstract fun checkPass(minPass: C_CompilerPass?, maxPass: C_CompilerPass?)
    fun checkPass(pass: C_CompilerPass) = checkPass(pass, pass)

    abstract fun onPass(pass: C_CompilerPass, code: () -> Unit)

    companion object {
        fun checkPass(currentPass: C_CompilerPass, minPass: C_CompilerPass?, maxPass: C_CompilerPass?) {
            if (minPass != null) {
                check(currentPass >= minPass) { "Expected pass >= $minPass, actual $currentPass" }
            }
            if (maxPass != null) {
                check(currentPass <= maxPass) { "Expected pass <= $maxPass, actual $currentPass" }
            }
        }
    }
}

class C_CompilerController(private val msgCtx: C_MessageContext) {
    val executor: C_CompilerExecutor = ExecutorImpl()

    private val passes = C_CompilerPass.values().map { it to queueOf<C_PassTask>() }.toMap()
    private var currentPass = C_CompilerPass.values()[0]

    private var runCalled = false

    fun run() {
        check(!runCalled)
        runCalled = true

        for (pass in C_CompilerPass.values()) {
            currentPass = pass
            val queue = passes.getValue(pass)
            while (!queue.isEmpty()) {
                val task = queue.remove()
                task.execute()
            }
        }
    }

    private fun onPass0(pass: C_CompilerPass, code: () -> Unit) {
        check(currentPass < pass) { "currentPass: $currentPass targetPass: $pass" }

        val nextPass = currentPass.next()

        if (pass == currentPass || pass == nextPass) {
            val task = C_PassTask(code)
            passes.getValue(pass).add(task)
        } else {
            // Extra code is needed to maintain execution order:
            // - entity 0 adds code to pass A, that code adds code to pass B
            // - entity 1 adds code to pass B directly
            // -> on pass B entity 0 must be executed before entity 1
            val task = C_PassTask { executor.onPass(pass, code) }
            passes.getValue(nextPass).add(task)
        }
    }

    private inner class C_PassTask(private val code: () -> Unit) {
        fun execute() {
            msgCtx.consumeError(code)
        }
    }

    private inner class ExecutorImpl: C_CompilerExecutor() {
        override fun checkPass(minPass: C_CompilerPass?, maxPass: C_CompilerPass?) {
            checkPass(currentPass, minPass, maxPass)
        }

        override fun onPass(pass: C_CompilerPass, code: () -> Unit) {
            onPass0(pass, code)
        }
    }
}
