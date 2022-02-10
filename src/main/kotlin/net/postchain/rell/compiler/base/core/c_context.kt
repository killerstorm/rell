/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.core

import com.google.common.math.IntMath
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_QualifiedName
import net.postchain.rell.compiler.ast.S_RellFile
import net.postchain.rell.compiler.base.def.C_AbstractFunctionDescriptor
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.def.C_MountTablesBuilder
import net.postchain.rell.compiler.base.def.C_OverrideFunctionDescriptor
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.fn.C_FormalParameters
import net.postchain.rell.compiler.base.modifier.C_ModifierValue
import net.postchain.rell.compiler.base.modifier.C_MountAnnotationValue
import net.postchain.rell.compiler.base.modifier.C_RawMountAnnotationValue
import net.postchain.rell.compiler.base.module.*
import net.postchain.rell.compiler.base.namespace.*
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.lib.C_LibFunctions
import net.postchain.rell.model.*
import net.postchain.rell.utils.*
import org.apache.commons.lang3.mutable.MutableLong

data class C_VarUid(val id: Long, val name: String, val fn: R_FnUid)
data class C_LoopUid(val id: Long, val fn: R_FnUid)

class C_GlobalContext(val compilerOptions: C_CompilerOptions, val sourceDir: C_SourceDir) {
    val libFunctions: C_LibFunctions by lazy {
        C_LibFunctions(compilerOptions)
    }

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

    fun error(pos: S_Pos, codeMsg: C_CodeMsg) {
        error(pos, codeMsg.code, codeMsg.msg)
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
    fun firstErrorReporter() = C_FirstErrorReporter(this)

    inner class C_ErrorWatcher {
        private var lastErrorCount = errorCount

        fun hasNewErrors(): Boolean {
            val count = errorCount
            val res = count > lastErrorCount
            lastErrorCount = count
            return res
        }
    }

    class C_FirstErrorReporter(private val msgCtx: C_MessageContext) {
        private var reported = false

        fun error(pos: S_Pos, code: String, msg: String) {
            if (!reported) {
                reported = true
                msgCtx.error(pos, code, msg)
            }
        }

        fun error(pos: S_Pos, codeMsg: C_CodeMsg) = error(pos, codeMsg.code, codeMsg.msg)
    }
}

class C_ModuleProvider(modules: Map<C_ModuleKey, C_Module>, preModules: Map<C_ModuleKey, C_PrecompiledModule>) {
    private val modules = modules.toImmMap()
    private val preModules = preModules.toImmMap()

    fun getModule(name: R_ModuleName, extChain: C_ExternalChain?): C_ModuleDescriptor? {
        val key = C_ModuleKey(name, extChain)
        return preModules[key]?.descriptor ?: modules[key]?.descriptor
    }
}

sealed class C_ModuleContext(
        val appCtx: C_AppContext,
        private val modProvider: C_ModuleProvider,
        val moduleName: R_ModuleName,
        val extChain: C_ExternalChain?,
        val containerKey: C_ContainerKey
) {
    val globalCtx = appCtx.globalCtx
    val msgCtx = appCtx.msgCtx
    val executor = appCtx.executor

    abstract val abstract: Boolean
    abstract val external: Boolean
    abstract val test: Boolean
    abstract val mountName: R_MountName
    abstract val sysDefs: C_SystemDefs
    abstract val repl: Boolean

    abstract val scopeBuilder: C_ScopeBuilder

    open val isTestDependency: Boolean = false

    val rModuleKey = R_ModuleKey(moduleName, extChain?.name)

    private val containerUid = appCtx.nextContainerUid(containerKey.keyStr())
    private val fnUidGen = C_UidGen { id, name -> R_FnUid(id, name, containerUid) }

    private val constFnUid = nextFnUid("<const>")
    private val constUidGen = C_UidGen { id, name -> C_VarUid(id, name, constFnUid) }

    private val namelessFunctionIds = mutableMapOf<R_QualifiedName, MutableLong>()

    fun nextFnUid(name: String) = fnUidGen.next(name)
    fun nextConstVarUid(name: String) = constUidGen.next(name)

    fun nextNamelessFunctionId(namespace: R_QualifiedName): Long {
        val idCtr = namelessFunctionIds.computeIfAbsent(namespace) { MutableLong(0) }
        val res = idCtr.toLong()
        idCtr.increment()
        return res
    }

    fun isTestLib(): Boolean = test || repl || globalCtx.compilerOptions.testLib

    fun getModule(name: R_ModuleName, extChain: C_ExternalChain?): C_ModuleDescriptor? {
        return modProvider.getModule(name, extChain)
    }

    abstract fun createFileNsAssembler(): C_NsAsm_ComponentAssembler
    abstract fun getModuleDefs(): C_ModuleDefs
    abstract fun getModuleArgsStruct(): R_Struct?
}

class C_RegularModuleContext(
        appCtx: C_AppContext,
        modProvider: C_ModuleProvider,
        private val module: C_Module,
        override val isTestDependency: Boolean
): C_ModuleContext(
        appCtx,
        modProvider,
        module.descriptor.name,
        module.descriptor.extChain,
        module.descriptor.containerKey
) {
    private val descriptor = module.descriptor

    override val abstract = descriptor.header.abstract
    override val external = descriptor.header.external
    override val test = descriptor.header.test
    override val mountName = descriptor.header.mountName
    override val sysDefs = extChain?.sysDefs ?: appCtx.sysDefs
    override val repl = false

    private val nsAssembler = appCtx.createModuleNsAssembler(descriptor.key, sysDefs, external)
    private val defsGetter = nsAssembler.futureDefs()

    override val scopeBuilder: C_ScopeBuilder = let {
        val sysNs = if (isTestLib()) sysDefs.testNs else sysDefs.appNs
        val rootScopeBuilder = C_ScopeBuilder(msgCtx)
        val sysScopeBuilder = rootScopeBuilder.nested { sysNs }
        val nsGetter = nsAssembler.futureNs()
        sysScopeBuilder.nested(nsGetter)
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
        modProvider: C_ModuleProvider,
        moduleName: R_ModuleName,
        replNsGetter: Getter<C_Namespace>,
        componentNsGetter: Getter<C_Namespace>
): C_ModuleContext(appCtx, modProvider, moduleName, null, C_ReplContainerKey) {
    override val abstract = false
    override val external = false
    override val test = false
    override val mountName = R_MountName.EMPTY
    override val sysDefs = appCtx.sysDefs
    override val repl = true

    override val scopeBuilder: C_ScopeBuilder = let {
        var builder = C_ScopeBuilder(msgCtx)
        builder = builder.nested { sysDefs.testNs }
        builder = builder.nested(replNsGetter)
        builder.nested(componentNsGetter)
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
    private val abstracts = C_ListBuilder<C_AbstractFunctionDescriptor>()
    private val overrides = C_ListBuilder<C_OverrideFunctionDescriptor>()

    fun createMountContext(): C_MountContext {
        val mountName = modCtx.mountName
        val nsAssembler = modCtx.createFileNsAssembler()
        return S_RellFile.createMountContext(this, mountName, nsAssembler)
    }

    fun addImport(d: C_ImportDescriptor) {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        imports.add(d)
    }

    fun addAbstractFunction(d: C_AbstractFunctionDescriptor) {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        abstracts.add(d)
    }

    fun addOverrideFunction(d: C_OverrideFunctionDescriptor) {
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

class C_NamespaceContext(
        val modCtx: C_ModuleContext,
        val namespaceName: C_QualifiedName?,
        val scopeBuilder: C_ScopeBuilder
) {
    val globalCtx = modCtx.globalCtx
    val appCtx = modCtx.appCtx
    val msgCtx = modCtx.msgCtx
    val executor = modCtx.executor

    val rNamespaceName = namespaceName?.toRName() ?: R_QualifiedName.EMPTY
    private val rawNamespaceName = namespaceName?.toCRawName()

    private val scope = scopeBuilder.scope()

    fun defNames(qualifiedName: C_RawQualifiedName, extChain: C_ExternalChain? = null): R_DefinitionNames {
        val moduleKey = modCtx.rModuleKey.copy(externalChain = extChain?.name)
        val fullName = rawNamespaceName?.add(qualifiedName) ?: qualifiedName
        return C_Utils.createDefNames(moduleKey, fullName)
    }

    fun defNames(qualifiedName: S_QualifiedName, extChain: C_ExternalChain? = null): R_DefinitionNames {
        val cQualifiedName = C_RawQualifiedName.of(qualifiedName)
        return defNames(cQualifiedName, extChain)
    }

    fun defNames(simpleName: S_Name, extChain: C_ExternalChain? = null): R_DefinitionNames {
        return defNames(S_QualifiedName(simpleName), extChain)
    }

    fun getType(name: S_QualifiedName): R_Type {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getType(name)
    }

    fun getTypeOpt(name: S_QualifiedName): R_Type? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getTypeOpt(name)
    }

    fun getEntity(name: S_QualifiedName): R_EntityDefinition {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getEntity(name)
    }

    fun getEntityOpt(name: S_QualifiedName): R_EntityDefinition? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getEntityOpt(name)
    }

    fun getObjectOpt(name: S_QualifiedName): R_ObjectDefinition? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getObjectOpt(name)
    }

    fun getValueOpt(name: S_QualifiedName): C_DefRef<C_NamespaceValue>? {
        executor.checkPass(C_CompilerPass.EXPRESSIONS, null)
        return scope.getValueOpt(name)
    }

    fun getFunctionOpt(name: S_QualifiedName): C_DefRef<C_GlobalFunction>? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getFunctionOpt(name)
    }

    fun getOperationOpt(name: S_QualifiedName): R_OperationDefinition? {
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
        val type = decType.msg.capitalize()
        throw C_Error.stop(pos, "def_external:$place:$decType", "$type not allowed in external $place")
    }

    fun checkNotReplOrTest(pos: S_Pos, decType: C_DeclarationType) {
        checkNotReplOrTest(pos) { decType.name toCodeMsg decType.msg }
    }

    fun checkNotReplOrTest(pos: S_Pos, declSupplier: C_CodeMsgSupplier): Boolean {
        return checkNotTest(pos, declSupplier) && checkNotRepl(pos, declSupplier)
    }

    fun checkNotTest(pos: S_Pos, declSupplier: C_CodeMsgSupplier): Boolean {
        return C_Errors.check(modCtx.msgCtx, !modCtx.test, pos) {
            val codeMsg = declSupplier()
            "def_test:${codeMsg.code}" toCodeMsg "Cannot declare ${codeMsg.msg} in a test module"
        }
    }

    fun checkNotRepl(pos: S_Pos, declSupplier: C_CodeMsgSupplier): Boolean {
        return C_Errors.check(modCtx.msgCtx, !modCtx.repl, pos) {
            val codeMsg = declSupplier()
            "def_repl:${codeMsg.code}" toCodeMsg "Cannot declare ${codeMsg.msg} in REPL"
        }
    }

    fun mountName(modMount: C_ModifierValue<C_RawMountAnnotationValue>, simpleName: S_Name): R_MountName {
        val mountValue = modMount.value()?.process(false)
        return mountName(mountValue, S_QualifiedName(simpleName))
    }

    fun mountName(mountAnn: C_MountAnnotationValue?, qualifiedName: S_QualifiedName?): R_MountName {
        if (mountAnn != null) {
            return mountAnn.calculateMountName(msgCtx, mountName)
        }

        val namePath = qualifiedName?.parts?.map { it.rName } ?: immListOf()
        val path = mountName.parts + namePath

        return R_MountName(path)
    }

    fun externalChain(modExternal: C_ModifierValue<C_ExtChainName>): C_ExternalChain? {
        val ann = modExternal.value()
        return if (ann == null) extChain else appCtx.addExternalChain(ann.name)
    }
}

enum class C_DefinitionType {
    ENTITY,
    OBJECT,
    STRUCT,
    QUERY,
    OPERATION,
    FUNCTION,
    CONSTANT,
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

    private val initFrameLate = C_LateInit(C_CompilerPass.FRAMES, R_CallFrame.ERROR)
    val initFrameGetter = initFrameLate.getter

    val initExprCtx: C_ExprContext = let {
        val fnCtx = C_FunctionContext(this, "${mntCtx.mountName}.<init>", null, TypedKeyMap())
        val initFrameCtx = C_FrameContext.create(fnCtx)

        executor.onPass(C_CompilerPass.FRAMES) {
            val cFrame = initFrameCtx.makeCallFrame(false)
            initFrameLate.set(cFrame.rFrame)
        }

        C_ExprContext.createRoot(initFrameCtx.rootBlkCtx)
    }

    fun getDbModificationRestriction(): C_CodeMsg? {
        return when {
            definitionType == C_DefinitionType.QUERY -> {
                C_CodeMsg("no_db_update:query", "Database modifications are not allowed in a query")
            }
            definitionType == C_DefinitionType.OBJECT && !globalCtx.compilerOptions.allowDbModificationsInObjectExprs -> {
                C_CodeMsg("no_db_update:object:expr", "Database modifications are not allowed in object attribute expressions")
            }
            modCtx.repl -> {
                C_CodeMsg("no_db_update:repl", "Database modifications are not allowed in REPL")
            }
            else -> null
        }
    }

    fun checkDbUpdateAllowed(pos: S_Pos) {
        val r = getDbModificationRestriction()
        if (r != null) {
            msgCtx.error(pos, r.code, r.msg)
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

    fun matchReturnType(pos: S_Pos, type: R_Type): C_TypeAdapter {
        return retTypeTracker.match(pos, type)
    }

    fun actualReturnType(): R_Type = retTypeTracker.getRetType()

    private sealed class RetTypeTracker {
        abstract fun getRetType(): R_Type
        abstract fun match(pos: S_Pos, type: R_Type): C_TypeAdapter

        class Implicit: RetTypeTracker() {
            private var impType: R_Type? = null

            override fun getRetType(): R_Type {
                val t = impType
                if (t != null) return t
                val res = R_UnitType
                impType = res
                return res
            }

            override fun match(pos: S_Pos, type: R_Type): C_TypeAdapter {
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
                return C_TypeAdapter_Direct
            }
        }

        class Explicit(val expType: R_Type): RetTypeTracker() {
            override fun getRetType() = expType

            override fun match(pos: S_Pos, type: R_Type): C_TypeAdapter {
                return if (type.isError() || expType.isError()) C_TypeAdapter_Direct else {
                    val m = if (expType == R_UnitType) {
                        if (type == R_UnitType) C_TypeAdapter_Direct else null
                    } else {
                        expType.getTypeAdapter(type)
                    }
                    m ?: throw errRetTypeMiss(pos, expType, type)
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