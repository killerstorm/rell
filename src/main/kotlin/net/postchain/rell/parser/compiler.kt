package net.postchain.rell.parser

import net.postchain.rell.CommonUtils
import net.postchain.rell.model.*
import net.postchain.rell.toImmList
import java.util.*

class C_Entity(val defPos: S_Pos?, val cls: R_Entity)
class C_Struct(val name: S_Name, val struct: R_Struct)

enum class C_CompilerPass {
    DEFINITIONS,
    NAMESPACES,
    MEMBERS,
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
        val blockEntity: R_Entity,
        val transactionEntity: R_Entity,
        val nsProto: C_SysNsProto,
        val mntTables: C_MountTables
) {
    companion object {
        private val SYSTEM_TYPES = mapOf(
                "boolean" to C_TypeDef(R_BooleanType),
                "text" to C_TypeDef(R_TextType),
                "byte_array" to C_TypeDef(R_ByteArrayType),
                "integer" to C_TypeDef(R_IntegerType),
                "decimal" to C_TypeDef(R_DecimalType),
                "rowid" to C_TypeDef(R_RowidType),
                "pubkey" to C_TypeDef(R_ByteArrayType),
                "name" to C_TypeDef(R_TextType),
                "timestamp" to C_TypeDef(R_IntegerType),
                "signer" to C_TypeDef(R_SignerType),
                "guid" to C_TypeDef(R_GUIDType),
                "tuid" to C_TypeDef(R_TextType),
                "json" to C_TypeDef(R_JsonType),
                "range" to C_TypeDef(R_RangeType),
                "GTXValue" to C_TypeDef(R_GtvType, C_Deprecated("gtv", error = true)),
                "gtv" to C_TypeDef(R_GtvType)
        )

        fun create(executor: C_CompilerExecutor, appDefsBuilder: C_AppDefsBuilder): C_SystemDefs {
            val blockEntity = C_Utils.createBlockEntity(executor, null)
            val transactionEntity = C_Utils.createTransactionEntity(executor, null, blockEntity)

            val sysNamespaces = C_LibFunctions.getSystemNamespaces()
            val sysTypes = SYSTEM_TYPES
            val sysEntities =  listOf(blockEntity, transactionEntity)
            val sysFunctions = C_LibFunctions.getGlobalFunctions()

            val nsBuilder = C_SysNsProtoBuilder()
            val mntBuilder = C_MountTablesBuilder()

            for ((name, type) in sysTypes) nsBuilder.addType(name, type)
            for (cls in sysEntities) nsBuilder.addEntity(cls.simpleName, cls)
            for ((name, fn) in sysFunctions) nsBuilder.addFunction(name, fn)
            for ((name, ns) in sysNamespaces) nsBuilder.addNamespace(name, ns)

            for (cls in sysEntities) {
                appDefsBuilder.entities.add(C_Entity(null, cls))
                mntBuilder.addEntity(null, cls)
            }

            val nsProto = nsBuilder.build()
            val mntTables = mntBuilder.build()
            return C_SystemDefs(blockEntity, transactionEntity, nsProto, mntTables)
        }
    }
}

// Instantiated in Eclipse IDE, change parameters carefully.
class C_CompilerOptions(val gtv: Boolean, val deprecatedError: Boolean) {
    class Builder {
        private var gtv = DEFAULT.gtv
        private var deprecatedError = DEFAULT.deprecatedError

        fun gtv(v: Boolean): Builder {
            gtv = v
            return this
        }

        fun deprecatedError(v: Boolean): Builder {
            deprecatedError = v
            return this
        }

        fun build() = C_CompilerOptions(gtv = gtv, deprecatedError = deprecatedError)
    }

    companion object {
        @JvmField val DEFAULT = C_CompilerOptions(gtv = true, deprecatedError = false)

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
        var error: C_Error? = null

        try {
            for (module in modules) {
                modMgr.linkModule(module)
            }
            app = appCtx.createApp()
        } catch (e: C_Error) {
            appCtx.globalCtx.error(e)
            error = e
        }

        val messages = CommonUtils.sortedByCopy(appCtx.globalCtx.messages()) { ComparablePos(it.pos) }

        val errorMessage = messages.firstOrNull { it.type == C_MessageType.ERROR }
        if (error == null && errorMessage != null) {
            error = C_Error(errorMessage.pos, errorMessage.code, errorMessage.text)
        }

        val files = modMgr.moduleFiles()

        val resApp = if (error == null) app else null
        return C_CompilationResult(resApp, error, messages, files)
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

class C_CompilationResult(val app: R_App?, val error: C_Error?, messages: List<C_Message>, files: List<C_SourcePath>) {
    val messages = messages.toImmList()
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
