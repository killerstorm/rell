package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_EnumValue
import net.postchain.rell.runtime.Rt_Value

class C_AtClass(val rClass: R_Class, val alias: String, val index: Int) {
    private val rAtClass = R_AtClass(rClass, index)

    fun compile() = rAtClass
    fun compileExpr() = Db_ClassExpr(rAtClass)
}

class C_ClassAttr(val cls: C_AtClass, val attr: R_Attrib)

class C_BlockContext(val entCtx: C_EntityContext, private val parent: C_BlockContext?, val loop: C_LoopId?) {
    private val startOffset: Int = if (parent == null) 0 else parent.startOffset + parent.locals.size
    private val locals = mutableMapOf<String, C_ScopeEntry0>()

    private val blockId = entCtx.nsCtx.modCtx.globalCtx.nextFrameBlockId()

    fun add(name: S_Name, type: R_Type, modifiable: Boolean): Pair<C_VarId, R_VarPtr> {
        val nameStr = name.str
        if (lookupLocalVar(nameStr) != null) {
            throw C_Error(name.pos, "var_dupname:$nameStr", "Duplicate variable: '$nameStr'")
        }

        val ofs = startOffset + locals.size
        entCtx.adjustCallFrameSize(ofs + 1)

        val varId = entCtx.nextVarId(nameStr)

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
        val subBlkCtx = C_BlockContext(blkCtx.entCtx, blkCtx, loop)
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

        val fn = blkCtx.entCtx.nsCtx.getFunctionOpt(name.str)
        val fnExpr = if (fn == null) null else C_FunctionExpr(name, fn)

        val expr = makeValueFunctionExpr(name, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name)
    }

    abstract fun findAttributesByName(name: String): List<C_ClassAttr>
    abstract fun findAttributesByType(type: R_Type): List<C_ClassAttr>

    abstract fun createDefaultAtWhat(): C_AtWhat
}

class C_RNameContext(blkCtx: C_BlockContext): C_NameContext(blkCtx) {
    override fun resolveNameValue(ctx: C_ExprContext, name: S_Name): C_NameResolution? {
        val loc = blkCtx.lookupLocalVar(name.str)
        val glob = blkCtx.entCtx.nsCtx.getValueOpt(name.str)

        if (loc != null) return C_NameResolution_Local(name, ctx, loc)
        if (glob != null) return C_NameResolution_Value(name, blkCtx.entCtx, glob)

        return null
    }

    override fun resolveAttr(name: S_Name): C_Expr {
        throw C_Errors.errUnknownAttr(name)
    }

    override fun findAttributesByName(name: String): List<C_ClassAttr> = listOf()
    override fun findAttributesByType(type: R_Type): List<C_ClassAttr> = listOf()

    override fun createDefaultAtWhat() = C_AtWhat(listOf(), listOf()) // Must not be called
}

class C_DbNameContext(blkCtx: C_BlockContext, private val classes: List<C_AtClass>): C_NameContext(blkCtx) {
    override fun resolveNameValue(ctx: C_ExprContext, name: S_Name): C_NameResolution? {
        val nameStr = name.str

        val cls = findClassByAlias(nameStr)
        val loc = blkCtx.lookupLocalVar(nameStr)
        val glob = blkCtx.entCtx.nsCtx.getValueOpt(name.str)

        if (cls != null && loc != null) {
            throw C_Errors.errNameConflictAliasLocal(name)
        }

        if (cls != null) return C_NameResolution_Class(name, cls)
        if (loc != null) return C_NameResolution_Local(name, ctx, loc)
        if (glob != null) return C_NameResolution_Value(name, blkCtx.entCtx, glob)

        return null
    }

    private fun findClassByAlias(alias: String): C_AtClass? {
        //TODO use a lookup table
        for (cls in classes) {
            if (cls.alias == alias) {
                return cls
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
            throw C_Errors.errMutlipleAttrs(name.pos, attrs, "at_attr_name_ambig:$nameStr",
                    "Multiple attributes with name '$nameStr'")
        }

        val attr = attrs[0]
        val dbExpr = makeDbAttrExpr(attr)
        return C_DbValue.makeExpr(name.pos, dbExpr)
    }

    private fun makeDbAttrExpr(attr: C_ClassAttr): Db_Expr {
        val clsExpr = attr.cls.compileExpr()
        val resultType = attr.attr.type
        val resultClass = if (resultType is R_ClassType) resultType.rClass else null
        return if (resultClass == null) Db_AttrExpr(clsExpr, attr.attr) else Db_RelExpr(clsExpr, attr.attr, resultClass)
    }

    override fun createDefaultAtWhat(): C_AtWhat {
        val exprs = classes.map {
            val name = if (classes.size == 1) null else it.alias
            val expr = it.compileExpr()
            Pair(name, expr)
        }
        return C_AtWhat(exprs, listOf())
    }

    override fun findAttributesByName(name: String): List<C_ClassAttr> {
        return findAttrsInChain { it.name == name }
    }

    override fun findAttributesByType(type: R_Type): List<C_ClassAttr> {
        return findAttrsInChain { it.type == type }
    }

    private fun findAttrsInChain(matcher: (R_Attrib) -> Boolean): List<C_ClassAttr> {
        val attrs = mutableListOf<C_ClassAttr>()

        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (cls in classes) {
            for (attr in cls.rClass.attributes.values) {
                if (matcher(attr)) {
                    attrs.add(C_ClassAttr(cls, attr))
                }
            }
        }

        return attrs.toList()
    }
}

sealed class C_NameResolution(val name: S_Name) {
    abstract fun toExpr(): C_Expr
}

private class C_NameResolution_Class(name: S_Name, private val cls: C_AtClass): C_NameResolution(name) {
    override fun toExpr() = C_DbValue.makeExpr(name.pos, cls.compileExpr())
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
        private val entCtx: C_EntityContext,
        private val value: C_NamespaceValue
): C_NameResolution(name) {
    override fun toExpr(): C_Expr = value.get(entCtx, listOf(name))
}

class C_BinOpType(val resType: R_Type, val rOp: R_BinaryOp?, val dbOp: Db_BinaryOp?)
class C_AssignOp(val pos: S_Pos, val code: String, val rOp: R_BinaryOp, val dbOp: Db_BinaryOp?)

abstract class C_Destination {
    abstract fun type(): R_Type
    open fun effectiveType(): R_Type = type()
    abstract fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement
    abstract fun compileAssignExpr(startPos: S_Pos, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr
}

class C_SimpleDestination(private val rDstExpr: R_DestinationExpr): C_Destination() {
    override fun type() = rDstExpr.type

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        return R_AssignStatement(rDstExpr, srcExpr, op?.rOp)
    }

    override fun compileAssignExpr(startPos: S_Pos, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
        return R_AssignExpr(rDstExpr.type, op.rOp, rDstExpr, srcExpr, post)
    }
}

class C_ClassFieldDestination(private val base: C_Value, private val rClass: R_Class, private val attr: R_Attrib): C_Destination() {
    override fun type() = attr.type

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        if (op != null && op.dbOp == null) {
            throw C_BinOp.errTypeMismatch(op.pos, op.code, attr.type, srcExpr.type)
        }

        val atClass = R_AtClass(rClass, 0)
        val whereLeft = Db_ClassExpr(atClass)
        val whereRight = Db_ParameterExpr(atClass.type, 0)
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Eq, whereLeft, whereRight)

        val rBase = base.toRExpr()
        val rTarget = R_UpdateTarget_Expr_One(atClass, where, rBase)
        val dbExpr = Db_InterpretedExpr(srcExpr)
        val rWhat = R_UpdateStatementWhat(attr, dbExpr, op?.dbOp)
        return R_UpdateStatement(rTarget, listOf(rWhat))
    }

    override fun compileAssignExpr(startPos: S_Pos, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
        val rStmt = compileAssignStatement(srcExpr, op)
        return R_StatementExpr(rStmt)
    }
}

class C_ObjectFieldDestination(private val rObject: R_Object, private val attr: R_Attrib): C_Destination() {
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

    override fun compileAssignExpr(startPos: S_Pos, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
        val rStmt = compileAssignStatement(srcExpr, op)
        return R_StatementExpr(rStmt)
    }
}

sealed class C_Value(val pos: S_Pos) {
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

        val valueExpr = memberFieldForType(pos, this, effectiveBaseType, memberName, safe)
        val fnExpr = memberFunctionForType(pos, this, effectiveBaseType, memberName, safe)

        if (valueExpr == null && fnExpr == null) {
            throw C_Errors.errUnknownMember(effectiveBaseType, memberName)
        }

        checkNullAccess(baseType, memberName, safe)

        val expr = makeValueFunctionExpr(memberName, valueExpr, fnExpr)
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
            val memExpr = makeDbMemberExpr(dbExpr, memberName)
            memExpr ?: throw C_Errors.errUnknownMember(dbExpr.type, memberName)
            return C_DbValue.makeExpr(pos, memExpr.dbExpr)
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

        val globCtx = ctx.blkCtx.entCtx.nsCtx.modCtx.globalCtx
        val freq = if (nulled == C_VarFact.YES) "always" else "never"
        globCtx.warning(name.pos, "expr_var_null:$freq:${name.str}", "Variable '${name.str}' is $freq null at that location")

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

        override fun compileAssignExpr(startPos: S_Pos, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
            checkInitialized()
            val rDstExpr = entry.toVarExpr()
            return R_AssignExpr(rDstExpr.type, op.rOp, rDstExpr, srcExpr, post)
        }
    }
}

private class C_ObjectValue(private val name: List<S_Name>, private val rObject: R_Object): C_Value(name[0].pos) {
    override fun type() = rObject.type
    override fun isDb() = false
    override fun toRExpr() = R_ObjectExpr(rObject.type)
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val attr = rObject.rClass.attributes[memberName.str]
        attr ?: throw C_Errors.errUnknownName(name, memberName)
        val value = C_ObjectFieldValue(memberName.pos, rObject, attr)
        return C_ValueExpr(value)
    }
}

private class C_ObjectFieldValue(pos: S_Pos, private val rObject: R_Object, private val attr: R_Attrib): C_Value(pos) {
    override fun type() = attr.type
    override fun isDb() = false
    override fun toRExpr() = createAccessExpr()
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(ctx: C_ExprContext): C_Destination {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(pos)
        return C_ObjectFieldDestination(rObject, attr)
    }

    private fun createAccessExpr(): R_Expr {
        val rClass = rObject.rClass
        val atCls = R_AtClass(rClass, 0)
        val from = listOf(atCls)
        val whatExpr = Db_AttrExpr(Db_ClassExpr(atCls), attr)
        val what = listOf(whatExpr)
        val atBase = R_AtExprBase(from, what, null, listOf())
        return R_ObjectAttrExpr(attr.type, rObject, atBase)
    }
}

private class C_MemberFieldValue(
        pos: S_Pos,
        private val base: C_Value,
        private val name: S_Name,
        private val safe: Boolean,
        private val field: C_MemberField,
        private val type: R_Type,
        private val varFacts: C_ExprVarFacts
): C_Value(pos) {
    override fun type() = type
    override fun isDb() = base.isDb()

    override fun toRExpr(): R_Expr {
        val rBase = base.toRExpr()
        val calculator = field.calculator()
        return R_MemberExpr(rBase, safe, calculator)
    }

    override fun toDbExpr(): Db_Expr {
        val rExpr = toRExpr()
        return C_Utils.toDbExpr(name.pos, rExpr)
    }

    override fun varFacts() = varFacts

    override fun destination(ctx: C_ExprContext): C_Destination {
        val rBase = base.toRExpr()
        val rDstExpr = field.destination(name.pos, rBase)
        return C_SimpleDestination(rDstExpr)
    }
}

private class C_ClassFieldValue(
        pos: S_Pos,
        private val base: C_Value,
        private val baseSafe: Boolean,
        private val atClass: R_AtClass,
        private val parent: C_Value,
        private val memberInfo: C_DbMemberInfo,
        private val resultType: R_Type
): C_Value(pos) {
    override fun type() = resultType
    override fun isDb() = false // Important: node does not belong to an outer @-expression

    override fun toRExpr(): R_Expr {
        val from = listOf(atClass)

        val whereLeft = Db_ClassExpr(atClass)
        val whereRight = Db_ParameterExpr(atClass.type, 0)
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Eq, whereLeft, whereRight)

        val atBase = R_AtExprBase(from, listOf(memberInfo.dbExpr), where, listOf())
        val calculator = R_MemberCalculator_DataAttribute(memberInfo.dbExpr.type, atBase)

        val rBase = base.toRExpr()
        val rExpr = R_MemberExpr(rBase, baseSafe, calculator)
        return rExpr
    }

    // Cannot inject the corresponding Db_Expr directly into another Db_Expr - must wrap it in R_Expr.
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(ctx: C_ExprContext): C_Destination {
        if (!memberInfo.attr.mutable) {
            throw C_Errors.errAttrNotMutable(memberInfo.name.pos, memberInfo.attr.name)
        }
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(pos)
        return C_ClassFieldDestination(parent, memberInfo.rClass, memberInfo.attr)
    }

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val valueExpr = memberValue(this, memberName, safe)
        val fnExpr = memberFunctionForType(pos, this, memberInfo.dbExpr.type, memberName, safe)

        val res = makeValueFunctionExpr(memberName, valueExpr, fnExpr)
        res ?: throw C_Errors.errUnknownMember(memberInfo.dbExpr.type, memberName)

        checkNullAccess(resultType, memberName, safe)
        return res
    }

    private fun memberValue(thisValue: C_Value, memberName: S_Name, safe: Boolean): C_Expr? {
        val resDbExpr = makeDbMemberExpr(memberInfo.dbExpr, memberName)
        if (resDbExpr == null) return null
        checkNullAccess(resultType, memberName, safe)
        val resResultType = C_Utils.effectiveMemberType(resDbExpr.dbExpr.type, safe)
        val value = C_ClassFieldValue(pos, base, baseSafe, atClass, thisValue, resDbExpr, resResultType)
        return C_ValueExpr(value)
    }
}

enum class C_ExprKind(val code: String) {
    VALUE("expression"),
    NAMESPACE("namespace"),
    TYPE("type"),
    RECORD("record"),
    OBJECT("object"),
    ENUM("enum"),
    FUNCTION("function")
}

sealed class C_Expr {
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

    protected fun compileArgs(ctx: C_ExprContext, args: List<S_NameExprPair>): List<C_Value> {
        val namedArg = args.map { it.name }.filterNotNull().firstOrNull()
        if (namedArg != null) {
            val argName = namedArg.str
            throw C_Error(namedArg.pos, "expr_call_namedarg:$argName", "Named function arguments not supported")
        }

        val cArgs = args.map {
            val sArg = it.expr
            val cArg = sArg.compile(ctx).value()
            val type = cArg.type()
            C_Utils.checkUnitType(sArg.startPos, type, "expr_arg_unit", "Argument expression returns nothing")
            cArg
        }

        return cArgs
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
        val ns = nsDef.useDef(ctx.blkCtx.entCtx.nsCtx, name)
        val valueExpr = memberValue(ctx.blkCtx.entCtx, memberName, ns)

        val fn = ns.functions[memberName.str]
        val fnExpr = if (fn != null) C_FunctionExpr(memberName, fn) else null

        val expr = makeValueFunctionExpr(memberName, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name, memberName)
    }

    private fun memberValue(entCtx: C_EntityContext, memberName: S_Name, ns: C_Namespace): C_Expr? {
        val nsValue = ns.values[memberName.str]
        val valueExpr = nsValue?.get(entCtx, name + listOf(memberName))
        if (valueExpr != null) return valueExpr

        val nsTypeDef = ns.types[memberName.str]
        return if (nsTypeDef == null) null else {
            val fullName = name + listOf(memberName)
            C_TypeNameExpr(memberName.pos, fullName, nsTypeDef)
        }
    }
}

class C_RecordExpr(
        private val name: List<S_Name>,
        private val record: R_RecordType,
        private val nsDef: C_NamespaceDef
): C_Expr() {
    override fun kind() = C_ExprKind.RECORD
    override fun startPos() = name[0].pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val nsExpr = C_NamespaceExpr(name, nsDef)
        return nsExpr.member(ctx, memberName, safe)
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        val startPos = startPos()
        val attrs = C_AttributeResolver.resolveCreate(ctx, record.attributes, args, startPos)
        val rExpr = R_RecordExpr(record, attrs.rAttrs)
        return C_RValue.makeExpr(startPos, rExpr, attrs.exprFacts)
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

class C_EnumExpr(private val name: List<S_Name>, private val rEnum: R_EnumType): C_Expr() {
    override fun kind() = C_ExprKind.ENUM
    override fun startPos() = name[0].pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val valueExpr = memberValue(memberName)
        val fnExpr = memberFn(memberName)
        val expr = makeValueFunctionExpr(memberName, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name, memberName)
    }

    private fun memberValue(memberName: S_Name): C_Expr? {
        val attr = rEnum.attr(memberName.str)
        if (attr == null) {
            return null
        }

        val rValue = Rt_EnumValue(rEnum, attr)
        val rExpr = R_ConstantExpr(rValue)
        return C_RValue.makeExpr(startPos(), rExpr)
    }

    private fun memberFn(memberName: S_Name): C_Expr? {
        val fn = C_LibFunctions.getTypeStaticFunction(rEnum, memberName.str)
        return if (fn == null) null else C_FunctionExpr(memberName, fn)
    }
}

private class C_MemberFunctionExpr(
        private val startPos: S_Pos,
        private val base: C_Value,
        private val name: S_Name,
        private val safe: Boolean,
        private val fn: C_SysMemberFunction
): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = startPos

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        val cArgs = compileArgs(ctx, args)
        return fn.compileCall(ctx, name, base, safe, cArgs)
    }
}

class C_FunctionExpr(private val name: S_Name, private val fn: C_GlobalFunction): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = name.pos

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        val cArgs = compileArgs(ctx, args)
        return fn.compileCall(ctx, name, cArgs)
    }
}

class C_TypeNameExpr(private val pos: S_Pos, private val name: List<S_Name>, private val typeDef: C_TypeDef): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val type = typeDef.useDef(ctx.blkCtx.entCtx.nsCtx, name)
        val fn = C_LibFunctions.getTypeStaticFunction(type, memberName.str)
        if (fn == null) throw C_Errors.errUnknownName(type.name, memberName)
        return C_FunctionExpr(memberName, fn)
    }
}

class C_TypeExpr(private val pos: S_Pos, private val type: R_Type): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val fn = C_LibFunctions.getTypeStaticFunction(type, memberName.str)
        if (fn == null) throw C_Errors.errUnknownName(type.name, memberName)
        return C_FunctionExpr(memberName, fn)
    }
}

class C_ValueFunctionExpr(private val name: S_Name, private val valueExpr: C_Expr, private val fnExpr: C_Expr): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = name.pos
    override fun value() = valueExpr.value()
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = valueExpr.member(ctx, memberName, safe)
    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>) = fnExpr.call(ctx, pos, args)
}

private class C_DbMemberInfo(val rClass: R_Class, val name: S_Name, val attr: R_Attrib, val dbExpr: Db_Expr)

private fun makeValueFunctionExpr(name: S_Name, valueExpr: C_Expr?, fnExpr: C_Expr?): C_Expr? {
    if (valueExpr != null && fnExpr != null) {
        return C_ValueFunctionExpr(name, valueExpr, fnExpr)
    } else if (valueExpr != null) {
        return valueExpr
    } else {
        return fnExpr
    }
}

private fun memberFieldForType(pos: S_Pos, base: C_Value, type: R_Type, name: S_Name, safe: Boolean): C_Expr? {
    if (type is R_TupleType) {
        return memberFieldForTuple(pos, base, type, name, safe)
    } else if (type is R_RecordType) {
        return memberFieldForRecord(pos, base, type, name, safe)
    } else if (type is R_ClassType) {
        return memberFieldForClass(pos, base, type, name, safe)
    } else if (type is R_EnumType) {
        return memberFieldForEnum(pos, base, name, safe)
    } else {
        return null
    }
}

private fun memberFieldForTuple(pos: S_Pos, base: C_Value, type: R_TupleType, name: S_Name, safe: Boolean): C_Expr? {
    val idx = type.fields.indexOfFirst { it.name == name.str }
    if (idx == -1) {
        return null
    }

    val field = C_MemberField_TupleField(type.fields[idx].type, idx)
    return makeMemberExpr(pos, base, name, safe, field)
}

private fun memberFieldForRecord(pos: S_Pos, base: C_Value, type: R_RecordType, name: S_Name, safe: Boolean): C_Expr? {
    val attr = type.attributes[name.str]
    if (attr == null) {
        return null
    }

    val field = C_MemberField_RecordAttr(attr)
    return makeMemberExpr(pos, base, name, safe, field)
}

private fun memberFieldForClass(pos: S_Pos, base: C_Value, type: R_ClassType, name: S_Name, safe: Boolean): C_Expr? {
    val atClass = R_AtClass(type.rClass, 0)
    val baseDbExpr = Db_ClassExpr(atClass)
    val memExpr = makeDbMemberExpr(baseDbExpr, name)
    memExpr ?: return null
    val resultType = C_Utils.effectiveMemberType(memExpr.dbExpr.type, safe)
    val value = C_ClassFieldValue(pos, base, safe, atClass, base, memExpr, resultType)
    return C_ValueExpr(value)
}

private fun memberFieldForEnum(pos: S_Pos, base: C_Value, name: S_Name, safe: Boolean): C_Expr? {
    val calculator = if (name.str == "name") {
        R_MemberCalculator_SysFn(R_TextType, R_SysFn_Enum_Name, listOf())
    } else if (name.str == "value") {
        R_MemberCalculator_SysFn(R_IntegerType, R_SysFn_Enum_Value, listOf())
    } else {
        return null
    }
    val field = C_MemberField_SimpleReadOnly(name, calculator)
    return makeMemberExpr(pos, base, name, safe, field)
}

private fun memberFunctionForType(pos: S_Pos, base: C_Value, type: R_Type, name: S_Name, safe: Boolean): C_Expr? {
    val fn = C_LibFunctions.getMemberFunctionOpt(type, name.str)
    return if (fn == null) null else C_MemberFunctionExpr(pos, base, name, safe, fn)
}

private fun makeMemberExpr(pos: S_Pos, base: C_Value, name: S_Name, safe: Boolean, field: C_MemberField): C_Expr {
    val fieldType = field.type
    val effectiveType = C_Utils.effectiveMemberType(fieldType, safe)
    val exprFacts = C_ExprVarFacts.of(postFacts = base.varFacts().postFacts)
    val value = C_MemberFieldValue(pos, base, name, safe, field, effectiveType, exprFacts)
    return C_ValueExpr(value)
}

private fun makeDbMemberExpr(base: Db_Expr, name: S_Name): C_DbMemberInfo? {
    if (base !is Db_TableExpr) return null
    val attr = base.rClass.attributes[name.str]
    if (attr == null) {
        return null
    }

    val resultType = attr.type
    val resultClass = if (resultType is R_ClassType) resultType.rClass else null
    val dbExpr = if (resultClass == null) Db_AttrExpr(base, attr) else Db_RelExpr(base, attr, resultClass)
    return C_DbMemberInfo(base.rClass, name, attr, dbExpr)
}

private fun checkNullAccess(type: R_Type, name: S_Name, safe: Boolean) {
    if (!safe && type is R_NullableType) {
        throw C_Error(name.pos, "expr_mem_null:${name.str}", "Cannot access member '${name.str}' of nullable value")
    }
}

private abstract class C_MemberField(val type: R_Type) {
    abstract fun calculator(): R_MemberCalculator
    abstract fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr
}

private class C_MemberField_TupleField(type: R_Type, val fieldIndex: Int): C_MemberField(type) {
    override fun calculator() = R_MemberCalculator_TupleField(type, fieldIndex)
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(pos)
}

private class C_MemberField_RecordAttr(val attr: R_Attrib): C_MemberField(attr.type) {
    override fun calculator() = R_MemberCalculator_RecordAttr(attr)

    override fun destination(pos: S_Pos, base: R_Expr): R_DestinationExpr {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        return R_RecordMemberExpr(base, attr)
    }
}

private class C_MemberField_SimpleReadOnly(val name: S_Name, val calculator: R_MemberCalculator): C_MemberField(calculator.type) {
    override fun calculator() = calculator
    override fun destination(pos: S_Pos, base: R_Expr) = throw C_Errors.errBadDestination(name)
}
