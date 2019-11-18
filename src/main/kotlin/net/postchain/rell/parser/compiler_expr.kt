package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_EnumValue
import net.postchain.rell.runtime.Rt_Value

class C_AtEntity(val rEntity: R_Entity, val alias: String, val index: Int) {
    private val rAtEntity = R_AtEntity(rEntity, index)

    fun compile() = rAtEntity
    fun compileExpr() = Db_EntityExpr(rAtEntity)
}

class C_ExprContextAttr(val entity: C_AtEntity, val attrRef: C_EntityAttrRef) {
    fun compile(): Db_Expr {
        val entityExpr = entity.compileExpr()
        return attrRef.createDbContextAttrExpr(entityExpr)
    }
}

class C_ScopeEntry(
        val name: String,
        val type: R_Type,
        val modifiable: Boolean,
        val varId: C_VarId,
        val ptr: R_VarPtr
) {
    fun toVarExpr(): R_VarExpr = R_VarExpr(type, ptr, name)
}

class C_BlockContext(
        val defCtx: C_DefinitionContext,
        private val parent: C_BlockContext?,
        val loop: C_LoopId?,
        private val location: String
) {
    private val startOffset: Int = if (parent == null) 0 else parent.startOffset + parent.locals.size
    private val locals = mutableMapOf<String, C_ScopeEntry0>()

    private val blockId = defCtx.globalCtx.nextFrameBlockId(location)

    fun add(name: S_Name, type: R_Type, modifiable: Boolean): Pair<C_VarId, R_VarPtr> {
        val nameStr = name.str
        if (lookupLocalVar(nameStr) != null) {
            throw C_Error(name.pos, "var_dupname:$nameStr", "Duplicate variable: '$nameStr'")
        }

        val ofs = startOffset + locals.size
        defCtx.adjustCallFrameSize(ofs + 1)

        val varId = defCtx.nextVarId(nameStr)

        val entry = C_ScopeEntry0(nameStr, type, modifiable, ofs, varId)
        locals[nameStr] = entry

        val ptr = entry.toVarPtr(blockId)
        return Pair(varId, ptr)
    }

    fun lookupLocalVar(name: String): C_ScopeEntry? {
        val local = findValue { it.locals[name] }
        return local?.toScopeEntry(blockId)
    }

    private fun <T> findValue(getter: (C_BlockContext) -> T?): T? {
        var ctx: C_BlockContext? = this
        while (ctx != null) {
            val value = getter(ctx)
            if (value != null) {
                return value
            }
            ctx = ctx.parent
        }
        return null
    }

    fun makeFrameBlock(): R_FrameBlock {
        val parentId = if (parent != null) parent.blockId else null
        return R_FrameBlock(parentId, blockId, startOffset, locals.size)
    }

    companion object {
        // Difference between C_ScopeEntry0 and C_ScopeEntry: there is one C_ScopeEntry0 for each variable, but
        // may be different C_ScopeEntry for same variable if it is being accessed from different scopes.
        // C_ScopeEntry refers to a scope (via ptr.blockId) where the variable is being used, not where it is declared.
        private class C_ScopeEntry0(
                val name: String,
                val type: R_Type,
                val modifiable: Boolean,
                val offset: Int,
                val varId: C_VarId
        ) {
            fun toVarPtr(blockId: R_FrameBlockId): R_VarPtr = R_VarPtr(blockId, offset)

            fun toScopeEntry(blockId: R_FrameBlockId): C_ScopeEntry {
                return C_ScopeEntry(name, type, modifiable, varId, toVarPtr(blockId))
            }
        }
    }
}

class C_ExprContext(val blkCtx: C_BlockContext, val nameCtx: C_NameContext, val factsCtx: C_VarFactsContext) {
    val defCtx = blkCtx.defCtx
    val modCtx = defCtx.modCtx
    val globalCtx = defCtx.globalCtx

    fun update(
            blkCtx: C_BlockContext? = null,
            nameCtx: C_NameContext? = null,
            factsCtx: C_VarFactsContext? = null
    ): C_ExprContext {
        val blkCtx2 = blkCtx ?: this.blkCtx
        val nameCtx2 = nameCtx ?: this.nameCtx
        val factsCtx2 = factsCtx ?: this.factsCtx
        return if (blkCtx2 == this.blkCtx && nameCtx2 == this.nameCtx && factsCtx2 == this.factsCtx) this else
            C_ExprContext(blkCtx2, nameCtx2, factsCtx2)
    }

    fun updateFacts(facts: C_VarFactsAccess): C_ExprContext {
        if (facts.isEmpty()) return this
        return update(factsCtx = this.factsCtx.sub(facts))
    }

    fun subBlock(loop: C_LoopId?): C_ExprContext {
        val subBlkCtx = C_BlockContext(blkCtx.defCtx, blkCtx, loop, "blk")
        val subNameCtx = C_RNameContext(subBlkCtx)
        return C_ExprContext(subBlkCtx, subNameCtx, factsCtx)
    }
}

sealed class C_NameContext(protected val blkCtx: C_BlockContext) {
    abstract fun resolveAttr(name: S_Name): C_Expr

    abstract fun resolveNameValue(ctx: C_ExprContext, name: S_Name): C_NameResolution?

    fun resolveName(ctx: C_ExprContext, name: S_Name): C_Expr {
        val value = resolveNameValue(ctx, name)
        val valueExpr = value?.toExpr()

        val fn = blkCtx.defCtx.nsCtx.getFunctionOpt(name.str)
        val fnExpr = if (fn == null) null else C_FunctionExpr(name, fn)

        val expr = C_ValueFunctionExpr.create(name, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name)
    }

    abstract fun findAttributesByName(name: String): List<C_ExprContextAttr>
    abstract fun findAttributesByType(type: R_Type): List<C_ExprContextAttr>

    abstract fun createDefaultAtWhat(): C_AtWhat
}

class C_RNameContext(blkCtx: C_BlockContext): C_NameContext(blkCtx) {
    override fun resolveNameValue(ctx: C_ExprContext, name: S_Name): C_NameResolution? {
        val loc = blkCtx.lookupLocalVar(name.str)
        val glob = blkCtx.defCtx.nsCtx.getValueOpt(name.str)

        if (loc != null) return C_NameResolution_Local(name, ctx, loc)
        if (glob != null) return C_NameResolution_Value(name, blkCtx.defCtx, glob)

        return null
    }

    override fun resolveAttr(name: S_Name): C_Expr {
        throw C_Errors.errUnknownAttr(name)
    }

    override fun findAttributesByName(name: String): List<C_ExprContextAttr> = listOf()
    override fun findAttributesByType(type: R_Type): List<C_ExprContextAttr> = listOf()

    override fun createDefaultAtWhat() = C_AtWhat(listOf(), listOf()) // Must not be called
}

class C_DbNameContext(blkCtx: C_BlockContext, private val entities: List<C_AtEntity>): C_NameContext(blkCtx) {
    override fun resolveNameValue(ctx: C_ExprContext, name: S_Name): C_NameResolution? {
        val nameStr = name.str

        val entity = findEntityByAlias(nameStr)
        val loc = blkCtx.lookupLocalVar(nameStr)
        val glob = blkCtx.defCtx.nsCtx.getValueOpt(name.str)

        if (entity != null && loc != null) {
            throw C_Errors.errNameConflictAliasLocal(name)
        }

        if (entity != null) return C_NameResolution_Entity(name, entity)
        if (loc != null) return C_NameResolution_Local(name, ctx, loc)
        if (glob != null) return C_NameResolution_Value(name, blkCtx.defCtx, glob)

        return null
    }

    private fun findEntityByAlias(alias: String): C_AtEntity? {
        //TODO use a lookup table
        for (entity in entities) {
            if (entity.alias == alias) {
                return entity
            }
        }
        return null
    }

    override fun resolveAttr(name: S_Name): C_Expr {
        val nameStr = name.str
        val attrs = findAttributesByName(nameStr)

        if (attrs.isEmpty()) {
            throw C_Errors.errUnknownAttr(name)
        } else if (attrs.size > 1) {
            throw C_Errors.errMultipleAttrs(name.pos, attrs, "at_attr_name_ambig:$nameStr",
                    "Multiple attributes with name '$nameStr'")
        }

        val attr = attrs[0]
        val dbExpr = attr.compile()
        return C_DbValue.makeExpr(name.pos, dbExpr)
    }

    override fun createDefaultAtWhat(): C_AtWhat {
        val exprs = entities.map {
            val name = if (entities.size == 1) null else it.alias
            val expr = it.compileExpr()
            Pair(name, expr)
        }
        return C_AtWhat(exprs, listOf())
    }

    override fun findAttributesByName(name: String): List<C_ExprContextAttr> {
        return findContextAttrs { rEntity ->
            val attrRef = C_EntityAttrRef.resolveByName(rEntity, name)
            if (attrRef == null) listOf() else listOf(attrRef)
        }
    }

    override fun findAttributesByType(type: R_Type): List<C_ExprContextAttr> {
        return findContextAttrs { rEntity ->
            C_EntityAttrRef.resolveByType(rEntity, type)
        }
    }

    private fun findContextAttrs(resolver: (R_Entity) -> List<C_EntityAttrRef>): List<C_ExprContextAttr> {
        val attrs = mutableListOf<C_ExprContextAttr>()

        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (entity in entities) {
            val entityAttrs = resolver(entity.rEntity)
            val ctxAttrs = entityAttrs.map { C_ExprContextAttr(entity, it) }
            attrs.addAll(ctxAttrs)
        }

        return attrs.toList()
    }
}

sealed class C_NameResolution(val name: S_Name) {
    abstract fun toExpr(): C_Expr
}

private class C_NameResolution_Entity(name: S_Name, private val entity: C_AtEntity): C_NameResolution(name) {
    override fun toExpr() = C_DbValue.makeExpr(name.pos, entity.compileExpr())
}

private class C_NameResolution_Local(
        name: S_Name,
        private val ctx: C_ExprContext,
        private val entry: C_ScopeEntry
): C_NameResolution(name) {
    override fun toExpr(): C_Expr {
        val nulled = ctx.factsCtx.nulled(entry.varId)

        var smartType = entry.type
        var smartNotNull = false
        if (entry.type is R_NullableType && nulled == C_VarFact.NO) {
            smartType = entry.type.valueType
            smartNotNull = true
        }

        val value = C_LocalVarValue(ctx, name, entry, nulled, smartType, smartNotNull)
        return C_ValueExpr(value)
    }
}

private class C_NameResolution_Value(
        name: S_Name,
        private val defCtx: C_DefinitionContext,
        private val value: C_NamespaceValue
): C_NameResolution(name) {
    override fun toExpr(): C_Expr = value.get(defCtx, listOf(name))
}

class C_BinOpType(val resType: R_Type, val rOp: R_BinaryOp?, val dbOp: Db_BinaryOp?)
class C_AssignOp(val pos: S_Pos, val code: String, val rOp: R_BinaryOp, val dbOp: Db_BinaryOp?)

abstract class C_Destination {
    abstract fun type(): R_Type
    open fun effectiveType(): R_Type = type()
    abstract fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement
    abstract fun compileAssignExpr(startPos: S_Pos, resType: R_Type, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr
}

class C_SimpleDestination(private val rDstExpr: R_DestinationExpr): C_Destination() {
    override fun type() = rDstExpr.type

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        return R_AssignStatement(rDstExpr, srcExpr, op?.rOp)
    }

    override fun compileAssignExpr(startPos: S_Pos, resType: R_Type, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
        return R_AssignExpr(resType, op.rOp, rDstExpr, srcExpr, post)
    }
}

class C_EntityAttrDestination(private val base: C_Value, private val rEntity: R_Entity, private val attr: R_Attrib): C_Destination() {
    override fun type() = attr.type

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        if (op != null && op.dbOp == null) {
            throw C_BinOp.errTypeMismatch(op.pos, op.code, attr.type, srcExpr.type)
        }

        val atEntity = R_AtEntity(rEntity, 0)
        val whereLeft = Db_EntityExpr(atEntity)
        val whereRight = Db_ParameterExpr(atEntity.rEntity.type, 0)
        val where = C_Utils.makeDbBinaryExprEq(whereLeft, whereRight)

        val rBase = base.toRExpr()
        val rTarget = R_UpdateTarget_Expr_One(atEntity, where, rBase)
        val dbExpr = Db_InterpretedExpr(srcExpr)
        val rWhat = R_UpdateStatementWhat(attr, dbExpr, op?.dbOp)
        return R_UpdateStatement(rTarget, listOf(rWhat))
    }

    override fun compileAssignExpr(startPos: S_Pos, resType: R_Type, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
        val rStmt = compileAssignStatement(srcExpr, op)
        return R_StatementExpr(rStmt)
    }
}

class C_ObjectAttrDestination(private val rObject: R_Object, private val attr: R_Attrib): C_Destination() {
    override fun type() = attr.type

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        if (op != null && op.dbOp == null) {
            throw C_BinOp.errTypeMismatch(op.pos, op.code, attr.type, srcExpr.type)
        }

        val rTarget = R_UpdateTarget_Object(rObject)
        val dbExpr = Db_InterpretedExpr(srcExpr)
        val rWhat = R_UpdateStatementWhat(attr, dbExpr, op?.dbOp)
        return R_UpdateStatement(rTarget, listOf(rWhat))
    }

    override fun compileAssignExpr(startPos: S_Pos, resType: R_Type, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
        val rStmt = compileAssignStatement(srcExpr, op)
        return R_StatementExpr(rStmt)
    }
}

abstract class C_Value(val pos: S_Pos) {
    abstract fun type(): R_Type
    abstract fun isDb(): Boolean
    abstract fun toRExpr(): R_Expr
    abstract fun toDbExpr(): Db_Expr

    open fun constantValue(): Rt_Value? = null
    open fun varId(): C_VarId? = null
    open fun varFacts(): C_ExprVarFacts = C_ExprVarFacts.EMPTY

    open fun asNullable(): C_Value = this

    open fun destination(ctx: C_ExprContext): C_Destination {
        throw C_Errors.errBadDestination(pos)
    }

    open fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val baseType = type()
        val effectiveBaseType = if (baseType is R_NullableType) baseType.valueType else baseType

        val memberRef = C_MemberRef(pos, this, memberName, safe)
        val valueExpr = C_MemberResolver.valueForType(ctx, effectiveBaseType, memberRef)
        val fnExpr = C_MemberResolver.functionForType(effectiveBaseType, memberRef)

        if (valueExpr == null && fnExpr == null) {
            throw C_Errors.errUnknownMember(effectiveBaseType, memberName)
        }

        C_MemberResolver.checkNullAccess(baseType, memberName, safe)

        val expr = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        return expr!!
    }
}

class C_RValue(
        pos: S_Pos,
        private val rExpr: R_Expr,
        private val exprVarFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY
): C_Value(pos) {
    override fun type() = rExpr.type
    override fun isDb() = false
    override fun toRExpr() = rExpr
    override fun toDbExpr() = C_Utils.toDbExpr(pos, rExpr)
    override fun constantValue() = rExpr.constantValue()
    override fun varFacts() = exprVarFacts

    companion object {
        fun makeExpr(pos: S_Pos, rExpr: R_Expr, varFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY): C_Expr {
            val value = C_RValue(pos, rExpr, varFacts)
            return C_ValueExpr(value)
        }
    }
}

class C_DbValue(pos: S_Pos, private val dbExpr: Db_Expr, private val varFacts: C_ExprVarFacts): C_Value(pos) {
    override fun type() = dbExpr.type
    override fun isDb() = true
    override fun toRExpr() = throw C_Errors.errExprDbNotAllowed(pos)
    override fun toDbExpr() = dbExpr
    override fun constantValue() = dbExpr.constantValue()
    override fun varFacts() = varFacts

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        if (dbExpr is Db_TableExpr) {
            val attrRef = C_EntityAttrRef.resolveByName(dbExpr.rEntity, memberName.str)
            attrRef ?: throw C_Errors.errUnknownMember(dbExpr.type, memberName)
            return attrRef.createDbMemberExpr(dbExpr, pos, memberName)
        }
        return super.member(ctx, memberName, safe)
    }

    companion object {
        fun makeExpr(pos: S_Pos, dbExpr: Db_Expr, varFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY): C_Expr {
            val value = C_DbValue(pos, dbExpr, varFacts)
            return C_ValueExpr(value)
        }
    }
}

class C_LookupValue(
        pos: S_Pos,
        private val baseType: R_Type,
        private val expr: R_Expr,
        private val dstExpr: R_DestinationExpr?,
        private val varFacts: C_ExprVarFacts
): C_Value(pos) {
    override fun type() = expr.type
    override fun isDb() = false
    override fun toRExpr() = expr
    override fun toDbExpr() = C_Utils.toDbExpr(pos, expr)
    override fun varFacts() = varFacts

    override fun destination(ctx: C_ExprContext): C_Destination {
        if (dstExpr == null) {
            val type = baseType.toStrictString()
            throw C_Error(pos, "expr_unmodifiable:$type", "Value of type '$type' cannot be modified")
        }
        return C_SimpleDestination(dstExpr)
    }
}

private class C_LocalVarValue(
        private val ctx: C_ExprContext,
        private val name: S_Name,
        private val entry: C_ScopeEntry,
        private val nulled: C_VarFact,
        private val smartType: R_Type,
        private val smartNotNull: Boolean
): C_Value(name.pos) {
    override fun type() = smartType
    override fun isDb() = false
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())
    override fun varId() = entry.varId

    override fun toRExpr(): R_Expr {
        checkInitialized()
        var rExpr: R_Expr = entry.toVarExpr()
        if (smartNotNull) {
            rExpr = R_NotNullExpr(smartType, rExpr)
        }
        return rExpr
    }

    override fun asNullable(): C_Value {
        if (entry.type !is R_NullableType || nulled == C_VarFact.MAYBE) {
            return this
        }

        val globCtx = ctx.globalCtx
        val (freq, msg) = if (nulled == C_VarFact.YES) Pair("always", "is always") else Pair("never", "cannot be")
        globCtx.warning(name.pos, "expr_var_null:$freq:${name.str}", "Variable '${name.str}' $msg null at this location")

        return C_LocalVarValue(ctx, name, entry, nulled, entry.type, false)
    }

    override fun destination(ctx: C_ExprContext): C_Destination {
        check(ctx === this.ctx)
        if (!entry.modifiable) {
            if (ctx.factsCtx.inited(entry.varId) != C_VarFact.NO) {
                throw C_Error(name.pos, "expr_assign_val:${name.str}", "Value of '${name.str}' cannot be changed")
            }
        }
        val effectiveType = if (smartNotNull) smartType else entry.type
        return C_LocalVarDestination(effectiveType)
    }

    private fun checkInitialized() {
        if (ctx.factsCtx.inited(entry.varId) != C_VarFact.YES) {
            val nameStr = name.str
            throw C_Error(pos, "expr_var_uninit:$nameStr", "Variable '$nameStr' may be uninitialized")
        }
    }

    private inner class C_LocalVarDestination(private val effectiveType: R_Type): C_Destination() {
        override fun type() = entry.type
        override fun effectiveType() = effectiveType

        override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
            if (op != null) {
                checkInitialized()
            }
            val rDstExpr = entry.toVarExpr()
            return R_AssignStatement(rDstExpr, srcExpr, op?.rOp)
        }

        override fun compileAssignExpr(startPos: S_Pos, resType: R_Type, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
            checkInitialized()
            val rDstExpr = entry.toVarExpr()
            return R_AssignExpr(resType, op.rOp, rDstExpr, srcExpr, post)
        }
    }
}

private class C_ObjectValue(private val name: List<S_Name>, private val rObject: R_Object): C_Value(name[0].pos) {
    override fun type() = rObject.type
    override fun isDb() = false
    override fun toRExpr() = R_ObjectExpr(rObject.type)
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val attr = rObject.rEntity.attributes[memberName.str]
        attr ?: throw C_Errors.errUnknownName(name, memberName)
        val value = C_ObjectAttrValue(memberName.pos, rObject, attr)
        return C_ValueExpr(value)
    }
}

private class C_ObjectAttrValue(pos: S_Pos, private val rObject: R_Object, private val attr: R_Attrib): C_Value(pos) {
    override fun type() = attr.type
    override fun isDb() = false
    override fun toRExpr() = createAccessExpr()
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(ctx: C_ExprContext): C_Destination {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        ctx.blkCtx.defCtx.checkDbUpdateAllowed(pos)
        return C_ObjectAttrDestination(rObject, attr)
    }

    private fun createAccessExpr(): R_Expr {
        val rEntity = rObject.rEntity
        val atEntity = R_AtEntity(rEntity, 0)
        val from = listOf(atEntity)
        val whatExpr = Db_AttrExpr(Db_EntityExpr(atEntity), attr)
        val what = listOf(whatExpr)
        val atBase = R_AtExprBase(from, what, null, listOf())
        return R_ObjectAttrExpr(attr.type, rObject, atBase)
    }
}

enum class C_ExprKind(val code: String) {
    VALUE("expression"),
    NAMESPACE("namespace"),
    TYPE("type"),
    STRUCT("struct"),
    OBJECT("object"),
    ENUM("enum"),
    FUNCTION("function")
}

abstract class C_Expr {
    abstract fun kind(): C_ExprKind
    abstract fun startPos(): S_Pos
    open fun value(): C_Value = throw errNoValue()

    open fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        throw errNoValue()
    }

    open fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        val type = value().type() // May fail with "not a value" - that's OK.
        val typeStr = type.toStrictString()
        throw C_Error(pos, "expr_call_nofn:$typeStr", "Not a function: value of type $typeStr")
    }

    private fun errNoValue(): C_Error {
        val pos = startPos()
        val kind = kind().code
        return C_Error(pos, "expr_novalue:$kind", "Expression has no value: $kind")
    }
}

class C_ValueExpr(private val value: C_Value): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = value.pos
    override fun value() = value

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        return value.member(ctx, memberName, safe)
    }
}

class C_NamespaceExpr(private val name: List<S_Name>, private val nsDef: C_NamespaceDef): C_Expr() {
    override fun kind() = C_ExprKind.NAMESPACE
    override fun startPos() = name[0].pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val defCtx = ctx.blkCtx.defCtx
        val ns = nsDef.useDef(defCtx.modCtx, name)
        val valueExpr = memberValue(defCtx, memberName, ns)

        val fn = ns.functions[memberName.str]
        val fnExpr = if (fn != null) C_FunctionExpr(memberName, fn) else null

        val expr = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name, memberName)
    }

    private fun memberValue(defCtx: C_DefinitionContext, memberName: S_Name, ns: C_Namespace): C_Expr? {
        val nsValue = ns.values[memberName.str]
        val valueExpr = nsValue?.get(defCtx, name + listOf(memberName))
        if (valueExpr != null) return valueExpr

        val nsTypeDef = ns.types[memberName.str]
        return if (nsTypeDef == null) null else {
            val fullName = name + listOf(memberName)
            C_TypeNameExpr(memberName.pos, fullName, nsTypeDef)
        }
    }
}

class C_StructExpr(
        private val name: List<S_Name>,
        private val struct: R_Struct,
        private val nsDef: C_NamespaceDef
): C_Expr() {
    override fun kind() = C_ExprKind.STRUCT
    override fun startPos() = name[0].pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val nsExpr = C_NamespaceExpr(name, nsDef)
        return nsExpr.member(ctx, memberName, safe)
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        return C_StructGlobalFunction.compileCall(struct, ctx, name.last(), args)
    }
}

class C_ObjectExpr(name: List<S_Name>, rObject: R_Object): C_Expr() {
    private val value = C_ObjectValue(name, rObject)

    override fun kind() = C_ExprKind.OBJECT
    override fun startPos() = value.pos
    override fun value(): C_Value = value

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        return value.member(ctx, memberName, safe)
    }
}

class C_EnumExpr(private val name: List<S_Name>, private val rEnum: R_Enum): C_Expr() {
    override fun kind() = C_ExprKind.ENUM
    override fun startPos() = name[0].pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val valueExpr = memberValue(memberName)
        val fnExpr = memberFn(memberName)
        val expr = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name, memberName)
    }

    private fun memberValue(memberName: S_Name): C_Expr? {
        val attr = rEnum.attr(memberName.str)
        if (attr == null) {
            return null
        }

        val rValue = Rt_EnumValue(rEnum.type, attr)
        val rExpr = R_ConstantExpr(rValue)
        return C_RValue.makeExpr(startPos(), rExpr)
    }

    private fun memberFn(memberName: S_Name): C_Expr? {
        val fn = C_LibFunctions.getTypeStaticFunction(rEnum.type, memberName.str)
        return if (fn == null) null else C_FunctionExpr(memberName, fn)
    }
}

class C_FunctionExpr(private val name: S_Name, private val fn: C_GlobalFunction): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = name.pos

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        return fn.compileCall(ctx, name, args)
    }
}

class C_TypeNameExpr(private val pos: S_Pos, private val name: List<S_Name>, private val typeDef: C_TypeDef): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val type = typeDef.useDef(ctx.modCtx, name)
        val fn = C_LibFunctions.getTypeStaticFunction(type, memberName.str)
        if (fn == null) throw C_Errors.errUnknownName(type, memberName)
        return C_FunctionExpr(memberName, fn)
    }
}

class C_TypeExpr(private val pos: S_Pos, private val type: R_Type): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val fn = C_LibFunctions.getTypeStaticFunction(type, memberName.str)
        if (fn == null) throw C_Errors.errUnknownName(type, memberName)
        return C_FunctionExpr(memberName, fn)
    }
}

class C_ValueFunctionExpr private constructor(
        private val name: S_Name,
        private val valueExpr: C_Expr,
        private val fnExpr: C_Expr
): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = name.pos
    override fun value() = valueExpr.value()
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = valueExpr.member(ctx, memberName, safe)
    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>) = fnExpr.call(ctx, pos, args)

    companion object {
        fun create(name: S_Name, valueExpr: C_Expr?, fnExpr: C_Expr?): C_Expr? {
            if (valueExpr != null && fnExpr != null) {
                return C_ValueFunctionExpr(name, valueExpr, fnExpr)
            } else if (valueExpr != null) {
                return valueExpr
            } else {
                return fnExpr
            }
        }
    }
}
