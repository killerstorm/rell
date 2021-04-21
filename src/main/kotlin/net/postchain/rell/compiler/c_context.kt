/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import com.google.common.math.IntMath
import net.postchain.rell.compiler.ast.C_FormalParameters
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.utils.Getter
import net.postchain.rell.utils.TypedKeyMap
import net.postchain.rell.utils.toImmList
import org.apache.commons.lang3.StringUtils

data class C_VarUid(val id: Long, val name: String, val fn: R_FnUid)
data class C_LoopUid(val id: Long, val fn: R_FnUid)

class C_GlobalContext(val compilerOptions: C_CompilerOptions, val sourceDir: C_SourceDir) {
    companion object {
        private val appUidGen = C_UidGen { id, _ -> R_AppUid(id) }
        fun nextAppUid(): R_AppUid = synchronized(appUidGen) { appUidGen.next("") }
    }
}

class C_MessageContext(val globalCtx: C_GlobalContext) {
    private val messages = mutableListOf<C_Message>()
    private var errorCount = 0

    fun message(type: C_MessageType, pos: S_Pos, code: String, text: String) {
        messages.add(C_Message(type, pos, code, text))
        if (type == C_MessageType.ERROR) errorCount = IntMath.checkedAdd(errorCount, 1)
    }

    fun warning(pos: S_Pos, code: String, text: String) {
        message(C_MessageType.WARNING, pos, code, text)
    }

    fun error(pos: S_Pos, code: String, msg: String) {
        message(C_MessageType.ERROR, pos, code, msg)
    }

    fun error(error: C_Error) {
        error(error.pos, error.code, error.errMsg)
    }

    fun error(error: C_PosCodeMsg) {
        error(error.pos, error.code, error.msg)
    }

    fun messages() = messages.toImmList()

    fun <T> consumeError(code: () -> T): T? {
        try {
            return code()
        } catch (e: C_Error) {
            error(e)
            return null
        }
    }

    fun errorWatcher() = C_ErrorWatcher()

    inner class C_ErrorWatcher {
        private var lastErrorCount = errorCount

        fun hasNewErrors(): Boolean {
            val count = errorCount
            val res = count > lastErrorCount
            lastErrorCount = count
            return res
        }
    }
}

sealed class C_ModuleContext(val modMgr: C_ModuleManager) {
    val appCtx = modMgr.appCtx
    val globalCtx = appCtx.globalCtx
    val msgCtx = appCtx.msgCtx
    val executor = appCtx.executor

    abstract val moduleName: R_ModuleName
    abstract val containerKey: C_ContainerKey
    abstract val abstract: Boolean
    abstract val external: Boolean
    abstract val test: Boolean
    abstract val mountName: R_MountName
    abstract val extChain: C_ExternalChain?
    abstract val sysDefs: C_SystemDefs
    abstract val repl: Boolean

    abstract val scopeBuilder: C_ScopeBuilder

    private val containerUid: R_ContainerUid by lazy { appCtx.nextContainerUid(containerKey.keyStr()) }
    private val fnUidGen = C_UidGen { id, name -> R_FnUid(id, name, containerUid) }

    fun nextFnUid(name: String) = fnUidGen.next(name)

    abstract fun createFileNsAssembler(): C_NsAsm_ComponentAssembler
    abstract fun getModuleDefs(): C_ModuleDefs
    abstract fun getModuleArgsStruct(): R_Struct?
}

class C_RegularModuleContext(
        modMgr: C_ModuleManager,
        private val module: C_Module
): C_ModuleContext(modMgr) {
    private val descriptor = module.descriptor

    override val moduleName = descriptor.name
    override val containerKey = descriptor.containerKey
    override val abstract = descriptor.header.abstract
    override val external = descriptor.header.external
    override val test = descriptor.header.test
    override val mountName = descriptor.header.mountName
    override val extChain = descriptor.extChain
    override val sysDefs = extChain?.sysDefs ?: appCtx.sysDefs
    override val repl = false

    private val nsAssembler = appCtx.createModuleNsAssembler(descriptor.key, sysDefs, external)
    private val defsGetter: C_LateGetter<C_ModuleDefs>
    override val scopeBuilder: C_ScopeBuilder

    init {
        val rootScopeBuilder = C_ScopeBuilder(msgCtx)

        val sysNs = if (test || appCtx.globalCtx.compilerOptions.testLib) sysDefs.testNs else sysDefs.appNs
        val sysScopeBuilder = rootScopeBuilder.nested { sysNs }

        val nsGetter = nsAssembler.futureNs()
        defsGetter = nsAssembler.futureDefs()
        scopeBuilder = sysScopeBuilder.nested(nsGetter)
    }

    override fun createFileNsAssembler() = nsAssembler.addComponent()

    override fun getModuleDefs() = defsGetter.get()

    override fun getModuleArgsStruct(): R_Struct? {
        val contents = module.contents()
        val struct = contents.defs.structs[C_Constants.MODULE_ARGS_STRUCT]
        return struct?.structDef?.struct
    }
}

class C_ReplModuleContext(
        appCtx: C_AppContext,
        modMgr: C_ModuleManager,
        moduleName: R_ModuleName,
        replNsGetter: Getter<C_Namespace>,
        componentNsGetter: Getter<C_Namespace>
): C_ModuleContext(modMgr) {
    override val moduleName = moduleName
    override val containerKey = C_ReplContainerKey
    override val abstract = false
    override val external = false
    override val test = false
    override val mountName = R_MountName.EMPTY
    override val extChain = null
    override val sysDefs = appCtx.sysDefs
    override val repl = true

    override val scopeBuilder: C_ScopeBuilder

    init {
        var builder = C_ScopeBuilder(msgCtx)
        builder = builder.nested { sysDefs.testNs }
        builder = builder.nested(replNsGetter)
        scopeBuilder = builder.nested(componentNsGetter)
    }

    override fun createFileNsAssembler() = throw UnsupportedOperationException()
    override fun getModuleDefs() = C_ModuleDefs.EMPTY
    override fun getModuleArgsStruct() = null
}

class C_FileContext(val modCtx: C_ModuleContext) {
    val executor = modCtx.executor
    val appCtx = modCtx.appCtx

    val mntBuilder = C_MountTablesBuilder(appCtx.appUid)

    private val imports = C_ListBuilder<C_ImportDescriptor>()
    private val abstracts = C_ListBuilder<C_AbstractDescriptor>()
    private val overrides = C_ListBuilder<C_OverrideDescriptor>()

    fun addImport(d: C_ImportDescriptor) {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        imports.add(d)
    }

    fun addAbstractFunction(d: C_AbstractDescriptor) {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        abstracts.add(d)
    }

    fun addOverrideFunction(d: C_OverrideDescriptor) {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        overrides.add(d)
    }

    private var createContentsCalled = false

    fun createContents(): C_FileImportsDescriptor {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        check(!createContentsCalled)
        createContentsCalled = true
        return C_FileImportsDescriptor(imports.commit(), abstracts.commit(), overrides.commit())
    }
}

class C_NamespaceContext(val modCtx: C_ModuleContext, val namespacePath: String?, val scopeBuilder: C_ScopeBuilder) {
    val globalCtx = modCtx.globalCtx
    val msgCtx = modCtx.msgCtx
    val executor = modCtx.executor

    private val scope = scopeBuilder.scope()

    fun defNames(qualifiedName: List<String>, extChain: C_ExternalChain? = null): R_DefinitionNames {
        return C_Utils.createDefNames(modCtx.moduleName, extChain?.ref, namespacePath, qualifiedName)
    }

    fun defNames(simpleName: String, extChain: C_ExternalChain? = null): R_DefinitionNames {
        return defNames(listOf(simpleName), extChain)
    }

    fun getType(name: List<S_Name>): R_Type {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getType(name)
    }

    fun getTypeOpt(name: List<S_Name>): R_Type? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getTypeOpt(name)
    }

    fun getEntity(name: List<S_Name>): R_EntityDefinition {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getEntity(name)
    }

    fun getEntityOpt(name: List<S_Name>): R_EntityDefinition? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getEntityOpt(name)
    }

    fun getObjectOpt(name: List<S_Name>): R_ObjectDefinition? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getObjectOpt(name)
    }

    fun getValueOpt(name: S_Name): C_DefRef<C_NamespaceValue>? {
        executor.checkPass(C_CompilerPass.EXPRESSIONS, null)
        return scope.getValueOpt(name)
    }

    fun getFunctionOpt(qName: List<S_Name>): C_DefRef<C_GlobalFunction>? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getFunctionOpt(qName)
    }

    fun getOperationOpt(name: List<S_Name>): R_OperationDefinition? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getOperationOpt(name)
    }
}

class C_ExternalChain(
        val name: String,
        val ref: R_ExternalChainRef,
        val sysDefs: C_SystemDefs
)

class C_MountContext(
        val fileCtx: C_FileContext,
        val nsCtx: C_NamespaceContext,
        val extChain: C_ExternalChain?,
        val nsBuilder: C_UserNsProtoBuilder,
        val mountName: R_MountName
) {
    val modCtx = nsCtx.modCtx
    val appCtx = modCtx.appCtx
    val msgCtx = appCtx.msgCtx
    val globalCtx = msgCtx.globalCtx
    val executor = nsCtx.executor
    val mntBuilder = fileCtx.mntBuilder

    fun checkNotExternal(pos: S_Pos, decType: C_DeclarationType) {
        if (extChain != null) {
            failExternal(pos, decType, "namespace")
        } else if (modCtx.external) {
            failExternal(pos, decType, "module")
        }
    }

    private fun failExternal(pos: S_Pos, decType: C_DeclarationType, place: String) {
        val type = StringUtils.capitalize(decType.msg)
        throw C_Error.stop(pos, "def_external:$place:$decType", "$type not allowed in external $place")
    }

    fun checkNotReplOrTest(pos: S_Pos, decType: C_DeclarationType) {
        if (modCtx.repl) {
            msgCtx.error(pos, "def_repl:$decType", "Cannot declare ${decType.msg} in REPL")
        } else if (modCtx.test) {
            msgCtx.error(pos, "def_test:$decType", "Cannot declare ${decType.msg} in a test module")
        }
    }

    fun mountName(modTarget: C_ModifierTarget, simpleName: S_Name): R_MountName {
        return mountName(modTarget, listOf(simpleName))
    }

    fun mountName(modTarget: C_ModifierTarget, fullName: List<S_Name>): R_MountName {
        val explicit = modTarget.mount?.get()
        if (explicit != null) {
            return explicit
        }

        val path = mountName.parts + fullName.map { it.rName }
        return R_MountName(path)
    }
}

enum class C_DefinitionType {
    ENTITY,
    OBJECT,
    STRUCT,
    QUERY,
    OPERATION,
    FUNCTION,
    REPL,
    ;

    fun isEntityLike() = this == ENTITY || this == OBJECT
}

class C_DefinitionContext(val mntCtx: C_MountContext, val definitionType: C_DefinitionType, val defId: R_DefinitionId) {
    val nsCtx = mntCtx.nsCtx
    val modCtx = nsCtx.modCtx
    val appCtx = modCtx.appCtx
    val msgCtx = appCtx.msgCtx
    val globalCtx = modCtx.globalCtx
    val executor = modCtx.executor

    val initExprCtx: C_ExprContext

    private val initFrameCtx: C_FrameContext

    private val initFrameLate = C_LateInit(C_CompilerPass.FRAMES, R_CallFrame.ERROR)
    val initFrameGetter = initFrameLate.getter

    init {
        val fnCtx = C_FunctionContext(this, "${mntCtx.mountName}.<init>", null, TypedKeyMap())
        initFrameCtx = C_FrameContext.create(fnCtx)
        initExprCtx = C_ExprContext.createRoot(initFrameCtx.rootBlkCtx)

        executor.onPass(C_CompilerPass.FRAMES) {
            val cFrame = initFrameCtx.makeCallFrame(false)
            initFrameLate.set(cFrame.rFrame)
        }
    }

    fun checkDbUpdateAllowed(pos: S_Pos) {
        if (definitionType == C_DefinitionType.QUERY) {
            msgCtx.error(pos, "no_db_update:query", "Database modifications are not allowed in a query")
        } else if (definitionType == C_DefinitionType.OBJECT) {
            msgCtx.error(pos, "no_db_update:object:expr", "Database modifications are not allowed in object attribute expressions")
        } else if (modCtx.repl) {
            msgCtx.error(pos, "no_db_update:repl", "Database modifications are not allowed in REPL")
        }
    }
}

class C_FunctionContext(
        val defCtx: C_DefinitionContext,
        name: String,
        val explicitReturnType: R_Type?,
        val statementVars: TypedKeyMap
) {
    val nsCtx = defCtx.nsCtx
    val modCtx = nsCtx.modCtx
    val appCtx = modCtx.appCtx
    val msgCtx = appCtx.msgCtx
    val globalCtx = modCtx.globalCtx
    val executor = modCtx.executor

    val fnUid = defCtx.modCtx.nextFnUid(name)
    private val blockUidGen = C_UidGen { id, name -> R_FrameBlockUid(id, name, fnUid) }
    private val varUidGen = C_UidGen { id, name -> C_VarUid(id, name, fnUid) }
    private val loopUidGen = C_UidGen { id, name -> C_LoopUid(id, fnUid) }

    private val retTypeTracker =
            if (explicitReturnType != null) RetTypeTracker.Explicit(explicitReturnType) else RetTypeTracker.Implicit()

    fun nextBlockUid(name: String) = blockUidGen.next(name)
    fun nextVarUid(name: String) = varUidGen.next(name)
    fun nextLoopUid() = loopUidGen.next("")

    fun matchReturnType(pos: S_Pos, type: R_Type) {
        retTypeTracker.match(pos, type)
    }

    fun actualReturnType(): R_Type = retTypeTracker.getRetType()

    private sealed class RetTypeTracker {
        abstract fun getRetType(): R_Type
        abstract fun match(pos: S_Pos, type: R_Type)

        class Implicit: RetTypeTracker() {
            private var impType: R_Type? = null

            override fun getRetType(): R_Type {
                val t = impType
                if (t != null) return t
                val res = R_UnitType
                impType = res
                return res
            }

            override fun match(pos: S_Pos, type: R_Type) {
                val t = impType
                if (t == null || t.isError()) {
                    impType = type
                } else if (type.isError()) {
                    // Do nothing.
                } else if (t == R_UnitType) {
                    if (type != R_UnitType) {
                        throw errRetTypeMiss(pos, t, type)
                    }
                } else {
                    val comType = R_Type.commonTypeOpt(t, type)
                    if (comType == null) {
                        throw errRetTypeMiss(pos, t, type)
                    }
                    impType = comType
                }
            }
        }

        class Explicit(val expType: R_Type): RetTypeTracker() {
            override fun getRetType() = expType

            override fun match(pos: S_Pos, type: R_Type) {
                if (type.isNotError() && expType.isNotError()) {
                    val m = if (expType == R_UnitType) type == R_UnitType else expType.isAssignableFrom(type)
                    if (!m) {
                        throw errRetTypeMiss(pos, expType, type)
                    }
                }
            }
        }
    }

    companion object {
        private fun errRetTypeMiss(pos: S_Pos, dstType: R_Type, srcType: R_Type): C_Error =
                C_Errors.errTypeMismatch(pos, srcType, dstType, "fn_rettype", "Return type mismatch")
    }
}

class C_FunctionBodyContext(
        val defCtx: C_DefinitionContext,
        val namePos: S_Pos,
        val defNames: R_DefinitionNames,
        val explicitRetType: R_Type?,
        val forParams: C_FormalParameters
) {
    val appCtx = defCtx.appCtx
    val executor = defCtx.executor
}
