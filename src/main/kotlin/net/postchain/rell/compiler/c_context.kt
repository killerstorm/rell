/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.model.*
import net.postchain.rell.utils.Getter
import net.postchain.rell.utils.TypedKeyMap
import org.apache.commons.lang3.StringUtils

data class C_VarUid(val id: Long, val name: String, val fn: R_FnUid)
data class C_LoopUid(val id: Long, val fn: R_FnUid)

class C_GlobalContext(val compilerOptions: C_CompilerOptions) {
    private val appUidGen = C_UidGen { id, _ -> R_AppUid(id) }

    fun nextAppUid() = appUidGen.next("")
}

class C_MessageContext(val globalCtx: C_GlobalContext) {
    private val messages = mutableListOf<C_Message>()

    fun message(type: C_MessageType, pos: S_Pos, code: String, text: String) {
        messages.add(C_Message(type, pos, code, text))
    }

    fun warning(pos: S_Pos, code: String, text: String) {
        message(C_MessageType.WARNING, pos, code, text)
    }

    fun error(pos: S_Pos, code: String, msg: String) {
        val message = C_Message(C_MessageType.ERROR, pos, code, msg)
        messages.add(message)
    }

    fun error(error: C_Error) {
        error(error.pos, error.code, error.errMsg)
    }

    fun messages() = messages.toList()

    fun <T> consumeError(code: () -> T): T? {
        try {
            return code()
        } catch (e: C_Error) {
            error(e)
            return null
        }
    }
}

sealed class C_ModuleContext(val appCtx: C_AppContext, val modMgr: C_ModuleManager) {
    val globalCtx = appCtx.globalCtx
    val msgCtx = appCtx.msgCtx
    val executor = appCtx.executor

    abstract val moduleName: R_ModuleName
    abstract val containerKey: C_ContainerKey
    abstract val abstract: Boolean
    abstract val external: Boolean
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
        appCtx: C_AppContext,
        private val module: C_Module,
        modMgr: C_ModuleManager
): C_ModuleContext(appCtx, modMgr) {
    private val descriptor = module.descriptor

    override val moduleName = descriptor.name
    override val containerKey = descriptor.containerKey
    override val abstract = descriptor.header.abstract
    override val external = descriptor.header.external
    override val mountName = descriptor.header.mountName
    override val extChain = descriptor.extChain
    override val sysDefs = extChain?.sysDefs ?: appCtx.sysDefs
    override val repl = false

    private val nsAssembler = appCtx.createModuleNsAssembler(descriptor.key, sysDefs, external)
    private val defsGetter: C_LateGetter<C_ModuleDefs>
    override val scopeBuilder: C_ScopeBuilder

    init {
        val rootScopeBuilder = C_ScopeBuilder(msgCtx)
        val sysScopeBuilder = rootScopeBuilder.nested { sysDefs.ns }
        val nsGetter = nsAssembler.futureNs()
        defsGetter = nsAssembler.futureDefs()
        scopeBuilder = sysScopeBuilder.nested(nsGetter)
    }

    override fun createFileNsAssembler() = nsAssembler.addComponent()

    override fun getModuleDefs() = defsGetter.get()

    override fun getModuleArgsStruct(): R_Struct? {
        val contents = module.contents()
        val struct = contents.defs.structs[C_Constants.MODULE_ARGS_STRUCT]
        return struct?.struct
    }
}

class C_ReplModuleContext(
        appCtx: C_AppContext,
        modMgr: C_ModuleManager,
        moduleName: R_ModuleName,
        replNsGetter: Getter<C_Namespace>,
        componentNsGetter: Getter<C_Namespace>
): C_ModuleContext(appCtx, modMgr) {
    override val moduleName = moduleName
    override val containerKey = C_ReplContainerKey
    override val abstract = false
    override val external = false
    override val mountName = R_MountName.EMPTY
    override val extChain = null
    override val sysDefs = appCtx.sysDefs
    override val repl = true

    override val scopeBuilder: C_ScopeBuilder

    init {
        var builder = C_ScopeBuilder(msgCtx)
        builder = builder.nested { sysDefs.ns }
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

    val nameCtx = C_NameContext.createNamespace(this)

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

    fun getEntity(name: List<S_Name>): R_Entity {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getEntity(name)
    }

    fun getValueOpt(name: S_Name): C_DefRef<C_NamespaceValue>? {
        executor.checkPass(C_CompilerPass.EXPRESSIONS, null)
        return scope.getValueOpt(name)
    }

    fun getFunctionOpt(qName: List<S_Name>): C_DefRef<C_GlobalFunction>? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        return scope.getFunctionOpt(qName)
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
        throw C_Error(pos, "def_external:$place:$decType", "$type not allowed in external $place")
    }

    fun checkNotRepl(pos: S_Pos, decType: C_DeclarationType) {
        if (modCtx.repl) {
            msgCtx.error(pos, "def_repl:$decType", "Cannot declare ${decType.msg} in REPL")
        }
    }

    fun mountName(modTarget: C_ModifierTarget, simpleName: S_Name): R_MountName {
        val explicit = modTarget.mount?.get()
        if (explicit != null) {
            return explicit
        }

        val path = mountName.parts + simpleName.rName
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
    REPL
}

class C_DefinitionContext(val mntCtx: C_MountContext, val definitionType: C_DefinitionType){
    val nsCtx = mntCtx.nsCtx
    val modCtx = nsCtx.modCtx
    val appCtx = modCtx.appCtx
    val msgCtx = appCtx.msgCtx
    val globalCtx = modCtx.globalCtx
    val executor = modCtx.executor

    val defExprCtx = C_ExprContext(this, nsCtx.nameCtx, C_VarFactsContext.EMPTY)

    fun checkDbUpdateAllowed(pos: S_Pos) {
        if (definitionType == C_DefinitionType.QUERY) {
            msgCtx.error(pos, "no_db_update:query", "Database modifications are not allowed in a query")
        } else if (modCtx.repl) {
            msgCtx.error(pos, "no_db_update:repl", "Database modifications are not allowed in REPL")
        }
    }
}

class C_EntityContext(
        val defCtx: C_DefinitionContext,
        private val entityName: String,
        private val logAnnotation: Boolean
) {
    private val msgCtx = defCtx.msgCtx

    private val attributes = mutableMapOf<String, R_Attrib>()
    private val keys = mutableListOf<R_Key>()
    private val indices = mutableListOf<R_Index>()
    private val uniqueKeys = mutableSetOf<Set<String>>()
    private val uniqueIndices = mutableSetOf<Set<String>>()

    fun hasAttribute(name: String): Boolean = name in attributes

    fun addAttribute(attr: S_AttrHeader, mutable: Boolean, expr: S_Expr?) {
        val defType = defCtx.definitionType

        val rType = attr.compileTypeOpt(defCtx.nsCtx)

        val name = attr.name
        val nameStr = name.str
        if (nameStr in attributes) {
            msgCtx.error(name.pos, "dup_attr:$nameStr", "Duplicate attribute: '$nameStr'")
            return
        }

        var err = rType == null

        val insideEntity = defType == C_DefinitionType.ENTITY || defType == C_DefinitionType.OBJECT

        if (insideEntity && !C_EntityAttrRef.isAllowedRegularAttrName(nameStr)) {
            msgCtx.error(name.pos, "unallowed_attr_name:$nameStr", "Unallowed attribute name: '$nameStr'")
            err = true
        }

        if (mutable && logAnnotation) {
            val ann = C_Constants.LOG_ANNOTATION
            msgCtx.error(name.pos, "entity_attr_mutable_log:$entityName:$nameStr",
                    "Entity '$entityName' cannot have mutable attributes because of the '$ann' annotation")
            err = true
        }

        if (rType == null) {
            return
        }

        if (insideEntity && !rType.sqlAdapter.isSqlCompatible()) {
            msgCtx.error(name.pos, "entity_attr_type:$nameStr:${rType.toStrictString()}",
                    "Attribute '$nameStr' has unallowed type: ${rType.toStrictString()}")
            err = true
        }

        if (defType == C_DefinitionType.OBJECT && expr == null) {
            msgCtx.error(name.pos, "object_attr_novalue:$entityName:$nameStr",
                    "Object attribute '$entityName.$nameStr' must have a default value")
            err = true
        }

        if (err) {
            return
        }

        val exprCreator: (() -> R_Expr)? = if (expr == null) null else { ->
            val rExpr = expr.compile(defCtx.defExprCtx).value().toRExpr()
            val adapter = S_Type.adapt(rType, rExpr.type, name.pos, "attr_type:$nameStr", "Default value type mismatch for '$nameStr'")
            adapter.adaptExpr(rExpr)
        }

        addAttribute0(nameStr, rType, mutable, true, exprCreator)
    }

    fun addAttribute0(name: String, rType: R_Type, mutable: Boolean, canSetInCreate: Boolean, exprCreator: (() -> R_Expr)?) {
        check(name !in attributes)
        check(defCtx.definitionType != C_DefinitionType.OBJECT || exprCreator != null)

        val rAttr = R_Attrib(attributes.size, name, rType, mutable, exprCreator != null, canSetInCreate)

        defCtx.executor.onPass(C_CompilerPass.EXPRESSIONS) {
            if (exprCreator == null) {
                rAttr.setExpr(null)
            } else {
                compileAttributeExpression(rAttr, exprCreator)
            }
        }

        attributes[name] = rAttr
    }

    private fun compileAttributeExpression(rAttr: R_Attrib, exprCreator: (() -> R_Expr)) {
        val rExpr = exprCreator()
        check(rAttr.type.isAssignableFrom(rExpr.type)) {
            val exp = rAttr.type.toStrictString()
            val act = rExpr.type.toStrictString()
            "Attribute '$entityName.${rAttr.name}' expression type mismatch: expected '$exp', was '$act'"
        }
        rAttr.setExpr(rExpr)
    }

    fun addKey(pos: S_Pos, attrs: List<S_Name>) {
        val names = attrs.map { it.str }
        addUniqueKeyIndex(pos, uniqueKeys, names, "entity_key_dup", "Duplicate key")
        keys.add(R_Key(names))
    }

    fun addIndex(pos: S_Pos, attrs: List<S_Name>) {
        val names = attrs.map { it.str }
        addUniqueKeyIndex(pos, uniqueIndices, names, "entity_index_dup", "Duplicate index")
        indices.add(R_Index(names))
    }

    fun createEntityBody(): R_EntityBody {
        return R_EntityBody(keys.toList(), indices.toList(), attributes.toMap())
    }

    fun createStructBody(): Map<String, R_Attrib> {
        return attributes.toMap()
    }

    private fun addUniqueKeyIndex(pos: S_Pos, set: MutableSet<Set<String>>, names: List<String>, errCode: String, errMsg: String) {
        if (defCtx.definitionType == C_DefinitionType.OBJECT) {
            throw C_Error(pos, "object_keyindex:${entityName}", "Object cannot have key or index")
        }

        val nameSet = names.toSet()
        if (!set.add(nameSet)) {
            val nameLst = names.sorted()
            throw C_Error(pos, "$errCode:${nameLst.joinToString(",")}", "$errMsg: ${nameLst.joinToString()}")
        }
    }
}

class C_FunctionContext(
        val defCtx: C_DefinitionContext,
        name: String,
        explicitReturnType: R_Type?,
        val statementVars: TypedKeyMap
){
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
                if (t == null || t == R_CtErrorType) {
                    impType = type
                } else if (type == R_CtErrorType) {
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
                if (type != R_CtErrorType && expType != R_CtErrorType) {
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
        val mntCtx: C_MountContext,
        val namePos: S_Pos,
        val defNames: R_DefinitionNames,
        val explicitRetType: R_Type?,
        val forParams: C_FormalParameters
)
