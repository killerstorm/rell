package net.postchain.rell.compiler

import net.postchain.rell.CommonUtils
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.toImmList
import java.util.*

class C_Entity(val defPos: S_Pos?, val entity: R_Entity)
class C_Struct(val name: S_Name, val struct: R_Struct)

enum class C_CompilerPass {
    DEFINITIONS,
    NAMESPACES,
    MODULES,
    MEMBERS,
    ABSTRACT,
    STRUCTS,
    EXPRESSIONS,
    VALIDATION,
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
        val ns: C_Namespace,
        val blockEntity: R_Entity,
        val transactionEntity: R_Entity,
        val mntTables: C_MountTables
) {
    companion object {
        private val SYSTEM_NAMESPACES = C_LibFunctions.getSystemNamespaces()
        private val SYSTEM_FUNCTIONS = C_LibFunctions.getGlobalFunctions()

        private val SYSTEM_TYPES = mapOf(
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

        fun create(executor: C_CompilerExecutor, appDefsBuilder: C_AppDefsBuilder): C_SystemDefs {
            val blockEntity = C_Utils.createBlockEntity(executor, null)
            val transactionEntity = C_Utils.createTransactionEntity(executor, null, blockEntity)

            val queries = listOf(
                    C_Utils.createSysQuery(executor, "get_rell_version", R_TextType, R_SysFn_Rell.GetRellVersion),
                    C_Utils.createSysQuery(executor, "get_postchain_version", R_TextType, R_SysFn_Rell.GetPostchainVersion),
                    C_Utils.createSysQuery(executor, "get_build", R_TextType, R_SysFn_Rell.GetBuild),
                    C_Utils.createSysQuery(executor, "get_build_details", R_SysFn_Rell.GetBuildDetails.TYPE, R_SysFn_Rell.GetBuildDetails),
                    C_Utils.createSysQuery(executor, "get_app_structure", R_GtvType, R_SysFn_Rell.GetAppStructure)
            )

            return create(appDefsBuilder, blockEntity, transactionEntity, queries)
        }

        fun create(
                appDefsBuilder: C_AppDefsBuilder,
                blockEntity: R_Entity,
                transactionEntity: R_Entity,
                queries: List<R_Query>
        ): C_SystemDefs {
            val sysEntities = listOf(blockEntity, transactionEntity)
            val nsProto = createNsProto(sysEntities)
            val ns = C_NsEntry.createNamespace(nsProto.entries)

            val mntBuilder = C_MountTablesBuilder()

            for (entity in sysEntities) {
                appDefsBuilder.entities.add(C_Entity(null, entity))
                mntBuilder.addEntity(null, entity)
            }

            for (query in queries) {
                appDefsBuilder.queries.add(query)
                mntBuilder.addQuery(query)
            }

            val mntTables = mntBuilder.build()
            return C_SystemDefs(nsProto, ns, blockEntity, transactionEntity, mntTables)
        }

        private fun createNsProto(sysEntities: List<R_Entity>): C_SysNsProto {
            val sysNamespaces = SYSTEM_NAMESPACES
            val sysTypes = SYSTEM_TYPES
            val sysFunctions = SYSTEM_FUNCTIONS

            val nsBuilder = C_SysNsProtoBuilder()

            for ((name, type) in sysTypes) nsBuilder.addType(name, type)
            for (entity in sysEntities) nsBuilder.addEntity(entity.simpleName, entity)
            for ((name, fn) in sysFunctions) nsBuilder.addFunction(name, fn)
            for ((name, ns) in sysNamespaces) nsBuilder.addNamespace(name, ns)

            return nsBuilder.build()
        }

        private fun typeRef(type: R_Type, deprecated: C_Deprecated? = null): C_DefProxy<R_Type> {
            return C_DefProxy.create(C_DeclarationType.TYPE, deprecated, type)
        }
    }
}

// Instantiated in Eclipse IDE, change parameters carefully.
class C_CompilerOptions(val gtv: Boolean, val deprecatedError: Boolean, val ide: Boolean) {
    class Builder {
        private var gtv = DEFAULT.gtv
        private var deprecatedError = DEFAULT.deprecatedError
        private var ide = DEFAULT.ide

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

        fun build() = C_CompilerOptions(gtv = gtv, deprecatedError = deprecatedError, ide = ide)
    }

    companion object {
        @JvmField val DEFAULT = C_CompilerOptions(gtv = true, deprecatedError = false, ide = false)

        @JvmStatic fun builder() = Builder()
    }
}

object C_Compiler {
    fun compile(
            sourceDir: C_SourceDir,
            modules: List<R_ModuleName>,
            options: C_CompilerOptions = C_CompilerOptions.DEFAULT
    ): C_CompilationResult {
        val globalCtx = C_GlobalContext(options)
        val controller = C_CompilerController(globalCtx)

        val res = C_LateInit.context(controller.executor) {
            compile0(sourceDir, globalCtx, controller, modules)
        }

        return res
    }

    private fun compile0(
            sourceDir: C_SourceDir,
            globalCtx: C_GlobalContext,
            controller: C_CompilerController,
            modules: List<R_ModuleName>
    ): C_CompilationResult {
        val appCtx = C_AppContext(globalCtx, controller)
        val modMgr = C_ModuleManager(appCtx, sourceDir, appCtx.executor)

        var app: R_App? = null

        try {
            for (moduleName in modules) {
                val module = modMgr.linkModule(moduleName, null)

                val header = module.header()
                if (header.abstract != null && !globalCtx.compilerOptions.ide) {
                    appCtx.globalCtx.error(header.abstract, "module:main_abstract:$moduleName",
                            "Module '${moduleName.str()}' is abstract, cannot be used as a main module")
                }
            }
            app = appCtx.createApp()
        } catch (e: C_Error) {
            appCtx.globalCtx.error(e)
        }

        val messages = CommonUtils.sortedByCopy(appCtx.globalCtx.messages()) { ComparablePos(it.pos) }
        val errors = messages.filter { it.type == C_MessageType.ERROR }

        val files = modMgr.moduleFiles()

        val resApp = if (errors.isEmpty()) app else null
        return C_CompilationResult(resApp, messages, files)
    }

    private class ComparablePos(sPos: S_Pos): Comparable<ComparablePos> {
        private val path: C_SourcePath = sPos.path()
        private val pos = sPos.pos()

        override fun compareTo(other: ComparablePos): Int {
            var d = path.compareTo(other.path)
            if (d == 0) d = pos.compareTo(other.pos)
            return d
        }
    }
}

class C_CompilationResult(val app: R_App?, messages: List<C_Message>, files: List<C_SourcePath>) {
    val messages = messages.toImmList()
    val warnings = this.messages.filter { it.type == C_MessageType.WARNING }.toImmList()
    val errors = this.messages.filter { it.type == C_MessageType.ERROR }.toImmList()
    val files = files.toImmList()
}

abstract class C_CompilerExecutor {
    abstract fun checkPass(minPass: C_CompilerPass?, maxPass: C_CompilerPass?)
    fun checkPass(pass: C_CompilerPass) = checkPass(pass, pass)

    abstract fun onPass(pass: C_CompilerPass, soft: Boolean = false, code: () -> Unit)

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

class C_CompilerController(private val globalCtx: C_GlobalContext) {
    val executor: C_CompilerExecutor = ExecutorImpl()

    private val passes = C_CompilerPass.values().map { Pair(it, ArrayDeque<C_PassTask>() as Queue<C_PassTask>) }.toMap()
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

    private fun onPass0(pass: C_CompilerPass, soft: Boolean, code: () -> Unit) {
        val valid = if (soft) currentPass <= pass else currentPass < pass
        check(valid) { "currentPass: $currentPass targetPass: $pass" }

        val nextPass = currentPass.next()

        if (pass == currentPass || pass == nextPass) {
            val task = C_PassTask(code)
            passes.getValue(pass).add(task)
        } else {
            // Extra code is needed to maintain execution order:
            // - entity 0 adds code to pass A, that code adds code to pass B
            // - entity 1 adds code to pass B directly
            // -> on pass B entity 0 must be executed before entity 1
            val task = C_PassTask { executor.onPass(pass, false, code) }
            passes.getValue(nextPass).add(task)
        }
    }

    private inner class C_PassTask(private val code: () -> Unit) {
        fun execute() {
            globalCtx.consumeError(code)
        }
    }

    private inner class ExecutorImpl: C_CompilerExecutor() {
        override fun checkPass(minPass: C_CompilerPass?, maxPass: C_CompilerPass?) {
            checkPass(currentPass, minPass, maxPass)
        }

        override fun onPass(pass: C_CompilerPass, soft: Boolean, code: () -> Unit) {
            onPass0(pass, soft, code)
        }
    }
}
