/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.core

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_RellFile
import net.postchain.rell.compiler.base.def.C_AbstractFunctionDescriptor
import net.postchain.rell.compiler.base.def.C_MountTablesBuilder
import net.postchain.rell.compiler.base.def.C_OverrideFunctionDescriptor
import net.postchain.rell.compiler.base.def.C_Struct
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.fn.C_FormalParameters
import net.postchain.rell.compiler.base.modifier.C_ModifierValue
import net.postchain.rell.compiler.base.modifier.C_MountAnnotationValue
import net.postchain.rell.compiler.base.modifier.C_RawMountAnnotationValue
import net.postchain.rell.compiler.base.module.*
import net.postchain.rell.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.compiler.base.namespace.C_Namespace
import net.postchain.rell.compiler.base.namespace.C_NsAsm_ComponentAssembler
import net.postchain.rell.compiler.base.namespace.C_UserNsProtoBuilder
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.model.*
import net.postchain.rell.tools.api.IdeSymbolCategory
import net.postchain.rell.tools.api.IdeSymbolId
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.*
import org.apache.commons.lang3.mutable.MutableLong

data class C_VarUid(val id: Long, val name: String, val fn: R_FnUid)
data class C_LoopUid(val id: Long, val fn: R_FnUid)

class C_GlobalContext(val compilerOptions: C_CompilerOptions, val sourceDir: C_SourceDir) {
    companion object {
        private val appUidGen = C_UidGen { id, _ -> R_AppUid(id) }
        fun nextAppUid(): R_AppUid = synchronized(appUidGen) { appUidGen.next("") }
    }
}

class C_MessageContext(val globalCtx: C_GlobalContext) {
    val msgMgr = C_MessageManager()

    fun message(type: C_MessageType, pos: S_Pos, code: String, text: String) {
        msgMgr.message(type, pos, code, text)
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

    fun messages() = msgMgr.messages()
    fun <T> consumeError(code: () -> T): T? = msgMgr.consumeError(code)
    fun errorWatcher() = msgMgr.errorWatcher()
    fun firstErrorReporter() = msgMgr.firstErrorReporter()
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
    abstract val directory: Boolean
    abstract val test: Boolean
    abstract val selected: Boolean
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

    private val namelessFunctionIds = mutableMapOf<C_RNamePath, MutableLong>()

    fun nextFnUid(name: String) = fnUidGen.next(name)
    fun nextConstVarUid(name: String) = constUidGen.next(name)

    fun nextNamelessFunctionId(namespace: C_RNamePath): Long {
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
    abstract fun getModuleArgsStruct(): C_Struct?
}

class C_RegularModuleContext(
        appCtx: C_AppContext,
        modProvider: C_ModuleProvider,
        private val module: C_Module,
        override val selected: Boolean,
        override val isTestDependency: Boolean,
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
    override val directory = descriptor.directory
    override val test = descriptor.header.test
    override val mountName = descriptor.header.mountName
    override val sysDefs = extChain?.sysDefs ?: appCtx.sysDefs
    override val repl = false

    private val nsAssembler = appCtx.createModuleNsAssembler(descriptor.key, sysDefs, external)
    private val defsGetter = nsAssembler.futureDefs()

    override val scopeBuilder: C_ScopeBuilder = let {
        val sysNs = if (isTestLib()) sysDefs.testNs else sysDefs.appNs
        val rootScopeBuilder = C_ScopeBuilder()
        val sysScopeBuilder = rootScopeBuilder.nested { sysNs }
        val nsGetter = nsAssembler.futureNs()
        sysScopeBuilder.nested(nsGetter)
    }

    override fun createFileNsAssembler() = nsAssembler.addComponent()

    override fun getModuleDefs() = defsGetter.get()

    override fun getModuleArgsStruct(): C_Struct? {
        val contents = module.contents()
        val struct = contents.defs.structs[C_Constants.MODULE_ARGS_STRUCT]
        return struct
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
    override val directory = false
    override val test = false
    override val selected = true // Doesn't really matter
    override val mountName = R_MountName.EMPTY
    override val sysDefs = appCtx.sysDefs
    override val repl = true

    override val scopeBuilder: C_ScopeBuilder = let {
        var builder = C_ScopeBuilder()
        builder = builder.nested { sysDefs.testNs }
        builder = builder.nested(replNsGetter)
        builder.nested(componentNsGetter)
    }

    override fun createFileNsAssembler() = throw UnsupportedOperationException()
    override fun getModuleDefs() = C_ModuleDefs.EMPTY
    override fun getModuleArgsStruct() = null
}

class C_FileContext(
    val modCtx: C_ModuleContext,
    val symCtx: C_SymbolContext,
) {
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

class C_ExternalChain(
        val name: String,
        val ref: R_ExternalChainRef,
        val sysDefs: C_SystemDefs
)

class C_MountContext(
        val fileCtx: C_FileContext,
        val nsCtx: C_NamespaceContext,
        private val extChain: C_ExternalChain?,
        val nsBuilder: C_UserNsProtoBuilder,
        val mountName: R_MountName
) {
    val modCtx = nsCtx.modCtx
    val appCtx = modCtx.appCtx
    val msgCtx = appCtx.msgCtx
    val globalCtx = msgCtx.globalCtx
    val symCtx = fileCtx.symCtx
    val executor = nsCtx.executor
    val mntBuilder = fileCtx.mntBuilder

    private val stringNamespacePath = nsCtx.namespacePath.parts.map { it.str }.toImmList()

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

    private fun checkNotReplOrTest(pos: S_Pos, declSupplier: C_CodeMsgSupplier): Boolean {
        return checkNotTest(pos, declSupplier) && checkNotRepl(pos, declSupplier)
    }

    private fun checkNotRepl(pos: S_Pos, declSupplier: C_CodeMsgSupplier): Boolean {
        return C_Errors.check(modCtx.msgCtx, !modCtx.repl, pos) {
            val codeMsg = declSupplier()
            "def_repl:${codeMsg.code}" toCodeMsg "Cannot declare ${codeMsg.msg} in REPL"
        }
    }

    fun checkNotTest(pos: S_Pos, declSupplier: C_CodeMsgSupplier): Boolean {
        return C_Errors.check(modCtx.msgCtx, !modCtx.test, pos) {
            val codeMsg = declSupplier()
            "def_test:${codeMsg.code}" toCodeMsg "Cannot declare ${codeMsg.msg} in a test module"
        }
    }

    fun mountName(modMount: C_ModifierValue<C_RawMountAnnotationValue>, simpleName: C_Name): R_MountName {
        val mountValue = modMount.value()?.process(false)
        return mountName(mountValue, C_QualifiedName(simpleName))
    }

    fun mountName(mountAnn: C_MountAnnotationValue?, qualifiedName: C_QualifiedName?): R_MountName {
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

    fun defBase(qualifiedName: C_StringQualifiedName, extChain: C_ExternalChain? = null): C_DefinitionBase {
        val moduleKey = modCtx.rModuleKey.copy(externalChain = extChain?.name)
        val fullName = C_StringQualifiedName.of(stringNamespacePath + qualifiedName.parts)
        return C_Utils.createDefBase(moduleKey, fullName)
    }

    fun defBaseEx(
        simpleName: C_Name,
        defType: C_DefinitionType,
        ideKind: IdeSymbolKind,
        extChain: C_ExternalChain? = null,
    ): C_DefinitionBaseEx {
        val qualifiedName = C_StringQualifiedName.of(simpleName.str)
        val base = defBase(qualifiedName, extChain)
        val ideId = base.ideId(defType)
        return base.baseEx(simpleName.pos, defType, ideKind, ideId)
    }
}

enum class C_DefinitionType(val ideCategory: IdeSymbolCategory) {
    REPL(IdeSymbolCategory.NONE),
    CONSTANT(IdeSymbolCategory.CONSTANT),
    ENTITY(IdeSymbolCategory.ENTITY),
    ENUM(IdeSymbolCategory.ENUM),
    FUNCTION(IdeSymbolCategory.FUNCTION),
    IMPORT(IdeSymbolCategory.IMPORT),
    OBJECT(IdeSymbolCategory.OBJECT),
    OPERATION(IdeSymbolCategory.OPERATION),
    QUERY(IdeSymbolCategory.QUERY),
    STRUCT(IdeSymbolCategory.STRUCT),
    ;

    fun isEntityLike() = this == ENTITY || this == OBJECT
}

class C_DefinitionContext(
    val mntCtx: C_MountContext,
    val definitionType: C_DefinitionType,
    val defId: R_DefinitionId,
    val defName: R_DefinitionName,
    private val ideId: IdeSymbolId,
) {
    val nsCtx = mntCtx.nsCtx
    val modCtx = nsCtx.modCtx
    val appCtx = modCtx.appCtx
    val symCtx = mntCtx.symCtx
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

    private var tupleFieldIdCounter: Long = 0

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

    fun tupleIdeId(): IdeSymbolId {
        val k = tupleFieldIdCounter++
        val name = R_Name.of("_${k}")
        return ideId.appendMember(IdeSymbolCategory.TUPLE, name)
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
    val defName: R_DefinitionName,
    val explicitRetType: R_Type?,
    val forParams: C_FormalParameters
) {
    val appCtx = defCtx.appCtx
    val executor = defCtx.executor
}
