package net.postchain.rell.parser

import net.postchain.rell.Getter
import net.postchain.rell.TypedKeyMap
import net.postchain.rell.model.*

class C_GlobalContext(val compilerOptions: C_CompilerOptions) {
    private var frameBlockIdCtr = 0L
    private val messages = mutableListOf<C_Message>()

    fun nextFrameBlockId(): R_FrameBlockId = R_FrameBlockId(frameBlockIdCtr++)

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

class C_ModuleContext(
        val module: C_Module,
        val appCtx: C_AppContext,
        val modMgr: C_ModuleManager,
        nsGetter: Getter<C_Namespace>
){
    val globalCtx = appCtx.globalCtx
    val executor = appCtx.executor

    val rootNsCtx = C_NamespaceContext(this, null, null, nsGetter)

    fun getModuleArgsStruct(): R_Struct? {
        val content = module.content()
        val struct = content.defs.structs[C_Constants.MODULE_ARGS_STRUCT]
        return struct?.struct
    }
}

class C_FileContext(val modCtx: C_ModuleContext) {
    val mntBuilder = C_MountTablesBuilder()
}

class C_NamespaceContext(
        val modCtx: C_ModuleContext,
        private val parentCtx: C_NamespaceContext?,
        val namespacePath: String?,
        private val nsGetter: Getter<C_Namespace>
) {
    val globalCtx = modCtx.globalCtx
    val executor = modCtx.executor

    fun defNames(simpleName: String): R_DefinitionNames {
        val moduleLevelName = C_Utils.fullName(namespacePath, simpleName)
        val modName = modCtx.module.name
        val appLevelName = if (modName.parts.isEmpty()) moduleLevelName else "$modName#$moduleLevelName"
        return R_DefinitionNames(simpleName, moduleLevelName, appLevelName)
    }

    fun getType(name: List<S_Name>): R_Type {
        val type = getTypeOpt(name)
        if (type == null) {
            val nameStr = C_Utils.nameStr(name)
            throw C_Error(name[0].pos, "unknown_type:$nameStr", "Unknown type: '$nameStr'")
        }
        return type
    }

    fun getTypeOpt(name: List<S_Name>): R_Type? {
        val typeDef = getTypeDefOpt(name)
        val type = typeDef?.useDef(modCtx, name)
        return type
    }

    private fun getTypeDefOpt(name: List<S_Name>): C_TypeDef? {
        check(!name.isEmpty())

        val name0 = name[0].str
        if (name.size == 1) {
            val typeDef = getDefOpt { it.getDirectTypeOpt(name0) }
            return typeDef
        }

        var nsDef = getDefOpt { it.getDirectNamespaceOpt(name0) }
        if (nsDef == null) return null

        for (i in 1 .. name.size - 2) {
            val nsName = name.subList(0, i)
            val ns = nsDef!!.useDef(modCtx, nsName)
            nsDef = ns.namespaces[name[i].str]
            if (nsDef == null) return null
        }

        val nsName = name.subList(0, name.size - 1)
        val typeDef = nsDef!!.useDef(modCtx, nsName).types[name[name.size - 1].str]
        return typeDef
    }

    fun getEntity(name: List<S_Name>): R_Entity {
        val type = getTypeOpt(name)
        if (type !is R_EntityType) {
            val nameStr = C_Utils.nameStr(name)
            throw C_Error(name[0].pos, "unknown_entity:$nameStr", "Unknown entity: '$nameStr'")
        }
        return type.rEntity
    }

    fun getValueOpt(name: String): C_NamespaceValue? {
        val e = getDefOpt { it.getDirectValueOpt(name) }
        return e
    }

    fun getFunctionOpt(name: String): C_GlobalFunction? {
        executor.checkPass(C_CompilerPass.EXPRESSIONS, null)
        val fn = getDefOpt { it.getDirectFunctionOpt(name) }
        return fn
    }

    private fun <T> getDefOpt(getter: (C_NamespaceContext) -> T?): T? {
        executor.checkPass(C_CompilerPass.MEMBERS, null)
        var ctx: C_NamespaceContext? = this
        while (ctx != null) {
            val def = getter(ctx)
            if (def != null) return def
            ctx = ctx.parentCtx
        }
        return null
    }

    private fun getDirectTypeOpt(name: String): C_TypeDef? {
        return nsGetter().types[name]
    }

    private fun getDirectNamespaceOpt(name: String): C_NamespaceDef? {
        return nsGetter().namespaces[name]
    }

    private fun getDirectValueOpt(name: String): C_NamespaceValue? {
        return nsGetter().values[name]
    }

    private fun getDirectFunctionOpt(name: String): C_GlobalFunction? {
        return nsGetter().functions[name]
    }
}

class C_ExternalChain(
        val name: S_String,
        val ref: R_ExternalChainRef,
        val blockEntity: R_Entity,
        val transactionEntity: R_Entity,
        val mntTables: C_MountTables
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
    val globalCtx = modCtx.globalCtx
    val executor = nsCtx.executor
    val mntBuilder = fileCtx.mntBuilder

    fun checkNotExternal(pos: S_Pos, decType: C_DeclarationType) {
        if (extChain != null) {
            val def = decType.description
            throw C_Error(pos, "def_external:$def", "Not allowed to have $def in external block")
        }
    }

    fun mountName(modTarget: C_ModifierTarget, simpleName: S_Name): R_MountName {
        val explicit = modTarget.mount?.get()
        if (explicit != null) {
            return explicit
        }

        val rName = simpleName.toRName()
        val path = mountName.parts + rName
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
}

class C_DefinitionContext(
        val nsCtx: C_NamespaceContext,
        val definitionType: C_DefinitionType,
        explicitReturnType: R_Type?,
        val statementVars: TypedKeyMap
){
    val modCtx = nsCtx.modCtx
    val appCtx = modCtx.appCtx
    val globalCtx = modCtx.globalCtx
    val executor = modCtx.executor

    private val retTypeTracker =
            if (explicitReturnType != null) RetTypeTracker.Explicit(explicitReturnType) else RetTypeTracker.Implicit()

    private var callFrameSize = 0

    private var nextLoopId = 0
    private var nextVarId = 0

    private val rootBlkCtx = C_BlockContext(this, null, null)
    private val rootNameCtx = C_RNameContext(rootBlkCtx)
    val rootExprCtx = C_ExprContext(rootBlkCtx, rootNameCtx, C_VarFactsContext(C_VarFacts.EMPTY))

    fun checkDbUpdateAllowed(pos: S_Pos) {
        if (definitionType == C_DefinitionType.QUERY) {
            throw C_Error(pos, "no_db_update", "Database modifications are not allowed in this context")
        }
    }

    fun matchReturnType(pos: S_Pos, type: R_Type) {
        retTypeTracker.match(pos, type)
    }

    fun actualReturnType(): R_Type = retTypeTracker.getRetType()

    fun adjustCallFrameSize(size: Int) {
        check(size >= 0)
        callFrameSize = Math.max(callFrameSize, size)
    }

    fun nextLoopId() = C_LoopId(nextLoopId++)
    fun nextVarId(name: String) = C_VarId(nextVarId++, name)

    fun makeCallFrame(): R_CallFrame {
        val rootBlock = rootExprCtx.blkCtx.makeFrameBlock()
        return R_CallFrame(callFrameSize, rootBlock)
    }

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
                if (t == null) {
                    impType = type
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
                val m = if (expType == R_UnitType) type == R_UnitType else expType.isAssignableFrom(type)
                if (!m) {
                    throw errRetTypeMiss(pos, expType, type)
                }
            }
        }
    }

    companion object {
        private fun errRetTypeMiss(pos: S_Pos, dstType: R_Type, srcType: R_Type): C_Error =
                C_Errors.errTypeMismatch(pos, srcType, dstType, "entity_rettype", "Return type mismatch")
    }
}

class C_EntityContext(
        val defCtx: C_DefinitionContext,
        private val entityName: String,
        private val logAnnotation: Boolean
) {
    private val attributes = mutableMapOf<String, R_Attrib>()
    private val keys = mutableListOf<R_Key>()
    private val indices = mutableListOf<R_Index>()
    private val uniqueKeys = mutableSetOf<Set<String>>()
    private val uniqueIndices = mutableSetOf<Set<String>>()

    fun hasAttribute(name: String): Boolean = name in attributes

    fun addAttribute(name: S_Name, attr: S_AttrHeader, mutable: Boolean, expr: S_Expr?) {
        val defType = defCtx.definitionType

        val rType = attr.compileTypeOpt(defCtx.nsCtx)

        val nameStr = name.str
        if (nameStr in attributes) {
            defCtx.globalCtx.error(name.pos, "dup_attr:$nameStr", "Duplicate attribute: '$nameStr'")
            return
        }

        var err = rType == null

        val insideEntity = defType == C_DefinitionType.ENTITY || defType == C_DefinitionType.OBJECT

        if (insideEntity && !C_EntityAttrRef.isAllowedRegularAttrName(nameStr)) {
            defCtx.globalCtx.error(name.pos, "unallowed_attr_name:$nameStr", "Unallowed attribute name: '$nameStr'")
            err = true
        }

        if (mutable && logAnnotation) {
            val ann = C_Constants.LOG_ANNOTATION
            defCtx.globalCtx.error(name.pos, "entity_attr_mutable_log:$entityName:$nameStr",
                    "Entity '$entityName' cannot have mutable attributes because of the '$ann' annotation")
            err = true
        }

        if (rType == null) {
            return
        }

        if (insideEntity && !rType.sqlAdapter.isSqlCompatible()) {
            defCtx.globalCtx.error(name.pos, "entity_attr_type:$nameStr:${rType.toStrictString()}",
                    "Attribute '$nameStr' has unallowed type: ${rType.toStrictString()}")
            err = true
        }

        if (defType == C_DefinitionType.OBJECT && expr == null) {
            defCtx.globalCtx.error(name.pos, "object_attr_novalue:$entityName:$nameStr",
                    "Object attribute '$entityName.$nameStr' must have a default value")
            err = true
        }

        if (err) {
            return
        }

        val exprCreator: (() -> R_Expr)? = if (expr == null) null else { ->
            val rExpr = expr.compile(defCtx.rootExprCtx).value().toRExpr()
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
