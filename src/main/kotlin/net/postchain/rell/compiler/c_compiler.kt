/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.lib.C_Lib_OpContext
import net.postchain.rell.model.*
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

class C_StatementVars(val declared: Set<String>, val modified: Set<String>) {
    companion object {
        val EMPTY = C_StatementVars(setOf(), setOf())
    }
}

class C_StatementVarsBlock {
    private val declared = mutableSetOf<String>()
    private val modified = mutableSetOf<String>()

    fun declared(names: Set<String>) {
        declared.addAll(names)
    }

    fun modified(names: Set<String>) {
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
        private val SYSTEM_TYPES = immMapOf(
                "unit" to typeRef(R_UnitType),
                "boolean" to typeRef(R_BooleanType),
                "text" to typeRef(R_TextType),
                "byte_array" to typeRef(R_ByteArrayType),
                "integer" to typeRef(R_IntegerType),
                "decimal" to typeRef(R_DecimalType),
                "rowid" to typeRef(R_RowidType),
                "pubkey" to typeRef(R_ByteArrayType),
                "name" to typeRef(R_TextType),
                "timestamp" to typeRef(R_IntegerType),
                "signer" to typeRef(R_SignerType),
                "guid" to typeRef(R_GUIDType),
                "tuid" to typeRef(R_TextType),
                "json" to typeRef(R_JsonType),
                "range" to typeRef(R_RangeType),
                "GTXValue" to typeRef(R_GtvType, C_Deprecated("gtv", error = true)),
                "gtv" to typeRef(R_GtvType)
        )

        private val SYSTEM_STRUCTS = C_Lib_OpContext.GLOBAL_STRUCTS

        fun create(appCtx: C_AppContext, stamp: R_AppUid): C_SystemDefs {
            val blockEntity = C_Utils.createBlockEntity(appCtx, null)
            val transactionEntity = C_Utils.createTransactionEntity(appCtx, null, blockEntity)

            val executor = appCtx.executor
            val queries = listOf(
                    C_Utils.createSysQuery(executor, "get_rell_version", R_TextType, R_SysFn_Rell.GetRellVersion),
                    C_Utils.createSysQuery(executor, "get_postchain_version", R_TextType, R_SysFn_Rell.GetPostchainVersion),
                    C_Utils.createSysQuery(executor, "get_build", R_TextType, R_SysFn_Rell.GetBuild),
                    C_Utils.createSysQuery(executor, "get_build_details", R_SysFn_Rell.GetBuildDetails.TYPE, R_SysFn_Rell.GetBuildDetails),
                    C_Utils.createSysQuery(executor, "get_app_structure", R_GtvType, R_SysFn_Rell.GetAppStructure)
            )

            return create(stamp, blockEntity, transactionEntity, queries)
        }

        fun create(
                stamp: R_AppUid,
                blockEntity: R_EntityDefinition,
                transactionEntity: R_EntityDefinition,
                queries: List<R_QueryDefinition>
        ): C_SystemDefs {
            val sysEntities = listOf(blockEntity, transactionEntity)

            val nsProto = createNsProto(sysEntities, false)
            val appNs = C_NsEntry.createNamespace(nsProto.entries)
            val testNsProto = createNsProto(sysEntities, true)
            val testNs = C_NsEntry.createNamespace(testNsProto.entries)

            val mntBuilder = C_MountTablesBuilder(stamp)
            for (entity in sysEntities) mntBuilder.addEntity(null, entity)
            for (query in queries) mntBuilder.addQuery(query)
            val mntTables = mntBuilder.build()

            return C_SystemDefs(nsProto, appNs, testNs, blockEntity, transactionEntity, mntTables, sysEntities, queries)
        }

        private fun createNsProto(sysEntities: List<R_EntityDefinition>, test: Boolean): C_SysNsProto {
            val sysNamespaces = if (test) C_LibFunctions.TEST_NAMESPACES else C_LibFunctions.APP_NAMESPACES
            val sysFunctions = if (test) C_LibFunctions.TEST_GLOBAL_FNS else C_LibFunctions.APP_GLOBAL_FNS
            val sysTypes = SYSTEM_TYPES
            val sysStructs = SYSTEM_STRUCTS

            val nsBuilder = C_SysNsProtoBuilder()

            for ((name, type) in sysTypes) nsBuilder.addType(name, type)
            for (entity in sysEntities) nsBuilder.addEntity(entity.simpleName, entity)
            for (struct in sysStructs) nsBuilder.addStruct(struct.name, struct)
            for ((name, fn) in sysFunctions) nsBuilder.addFunction(name, fn)
            for ((name, ns) in sysNamespaces) nsBuilder.addNamespace(name, ns)

            return nsBuilder.build()
        }

        private fun typeRef(type: R_Type, deprecated: C_Deprecated? = null): C_DefProxy<R_Type> {
            return C_DefProxy.create(type, C_DeclarationType.TYPE, deprecated)
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
        val gtv: Boolean,
        val deprecatedError: Boolean,
        val ide: Boolean,
        val blockCheck: Boolean,
        val atAttrShadowing: C_AtAttrShadowing,
        val testLib: Boolean,
        val allowDbModificationsInObjectExprs: Boolean,
        val compatibility: R_LangVersion?
) {
    fun toPojoMap(): Map<String, Any> {
        val map = mutableMapOf(
                "gtv"  to gtv,
                "deprecatedError" to deprecatedError,
                "ide" to ide,
                "atAttrShadowing" to atAttrShadowing.name,
                "testLib" to testLib,
                "allowDbModificationsInObjectExprs" to allowDbModificationsInObjectExprs
        )
        if (compatibility != null) {
            map["compatibility"] = compatibility.str()
        }
        return map.toImmMap()
    }

    companion object {
        @JvmField val DEFAULT = C_CompilerOptions(
                gtv = true,
                deprecatedError = false,
                ide = false,
                blockCheck = false,
                atAttrShadowing = C_AtAttrShadowing.DEFAULT,
                testLib = false,
                allowDbModificationsInObjectExprs = true,
                compatibility = null
        )

        @JvmStatic fun builder() = Builder()

        @JvmStatic fun fromPojoMap(map: Map<String, Any>): C_CompilerOptions {
            return C_CompilerOptions(
                    blockCheck = true,
                    gtv = map.getValue("gtv") as Boolean,
                    deprecatedError = map.getValue("deprecatedError") as Boolean,
                    atAttrShadowing = (map["atAttrShadowing"] as String?)
                            ?.let { C_AtAttrShadowing.valueOf(it) } ?: DEFAULT.atAttrShadowing,
                    ide = getBoolOpt(map, "ide", DEFAULT.ide),
                    testLib = getBoolOpt(map, "testLib", DEFAULT.testLib),
                    allowDbModificationsInObjectExprs =
                            getBoolOpt(map, "allowDbModificationsInObjectExprs", DEFAULT.allowDbModificationsInObjectExprs),
                    compatibility = (map["compatibility"] as String?)?.let { R_LangVersion.of(it) }
            )
        }

        fun forLangVersion(version: R_LangVersion): C_CompilerOptions {
            return Builder().compatibility(version).build()
        }

        private fun getBoolOpt(map: Map<String, Any>, key: String, def: Boolean): Boolean = (map[key] as Boolean?) ?: def
    }

    class Builder(proto: C_CompilerOptions = DEFAULT) {
        private var gtv = proto.gtv
        private var deprecatedError = proto.deprecatedError
        private var ide = proto.ide
        private var blockCheck = proto.blockCheck
        private var atAttrShadowing = proto.atAttrShadowing
        private var testLib = proto.testLib
        private var allowDbModificationsInObjectExprs = proto.allowDbModificationsInObjectExprs
        private var compatibility = proto.compatibility

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

        @Suppress("UNUSED") fun allowDbModificationsInObjectExprs(v: Boolean): Builder {
            allowDbModificationsInObjectExprs = v
            return this
        }

        @Suppress("UNUSED") fun compatibility(v: R_LangVersion): Builder {
            compatibility = v
            return this
        }

        fun build() = C_CompilerOptions(
                gtv = gtv,
                deprecatedError = deprecatedError,
                ide = ide,
                blockCheck = blockCheck,
                atAttrShadowing = atAttrShadowing,
                testLib = testLib,
                allowDbModificationsInObjectExprs = allowDbModificationsInObjectExprs,
                compatibility = compatibility
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
        val extModules = msgCtx.consumeError {
            compileMidModules(msgCtx, sourceDir, moduleSelection)
        } ?: immListOf()

        val appCtx = C_AppContext(msgCtx, controller.executor, false, C_ReplAppState.EMPTY)

        msgCtx.consumeError {
            val extCompiler = C_ExtModuleCompiler(appCtx, extModules, immMapOf())
            extCompiler.compileModules()
        }

        val files = extModules.flatMap { it.midModule.filePaths() }.toImmList()

        controller.run()

        val app = appCtx.getApp()

        val messages = CommonUtils.sortedByCopy(msgCtx.messages()) { C_ComparablePos(it.pos) }
        val errors = messages.filter { it.type == C_MessageType.ERROR }

        val rApp = if (errors.isEmpty()) app else null
        return C_CompilationResult(rApp, messages, files)
    }

    private fun compileMidModules(
            msgCtx: C_MessageContext,
            sourceDir: C_SourceDir,
            modSel: C_CompilerModuleSelection
    ): List<C_ExtModule> {
        val midModules = loadMidModules(msgCtx, sourceDir, modSel)

        checkMainModules(msgCtx, modSel, midModules)

        val testModules = midModules.filter { it.header != null && it.header.test }
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
            modSel: C_CompilerModuleSelection
    ): List<C_MidModule> {
        val modLdr = C_ModuleLoader(msgCtx, sourceDir, immSetOf())

        for (moduleName in modSel.testRootModules) {
            modLdr.loadTestModules(moduleName)
        }

        for (moduleName in modSel.modules) {
            modLdr.loadModule(moduleName)
        }

        return modLdr.getLoadedModules()
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
    private val pos = sPos.pos()

    override fun compareTo(other: C_ComparablePos): Int {
        var d = path.compareTo(other.path)
        if (d == 0) d = pos.compareTo(other.pos)
        return d
    }
}

abstract class C_AbstractResult(messages: List<C_Message>) {
    val messages = messages.toImmList()
    val warnings = this.messages.filter { it.type == C_MessageType.WARNING }.toImmList()
    val errors = this.messages.filter { it.type == C_MessageType.ERROR }.toImmList()
}

class C_CompilationResult(val app: R_App?, messages: List<C_Message>, files: List<C_SourcePath>): C_AbstractResult(messages) {
    val files = files.toImmList()
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
