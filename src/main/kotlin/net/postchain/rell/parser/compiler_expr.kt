package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_EnumValue

class C_ClassAttr(val cls: R_AtClass, val attr: R_Attrib)

class C_BlockContext(
        val entCtx: C_EntityContext,
        private val parent: C_BlockContext?,
        val insideLoop: Boolean
){
    private val startOffset: Int = if (parent == null) 0 else parent.startOffset + parent.locals.size
    private val locals = mutableMapOf<String, C_ScopeEntry0>()

    private val blockId = entCtx.modCtx.globalCtx.nextFrameBlockId()

    fun add(name: S_Name, type: R_Type, modifiable: Boolean): R_VarPtr {
        val nameStr = name.str
        if (lookupLocalVar(nameStr) != null) {
            throw C_Error(name.pos, "var_dupname:$nameStr", "Duplicate variable: '$nameStr'")
        }

        val ofs = startOffset + locals.size
        entCtx.adjustCallFrameSize(ofs + 1)

        val entry = C_ScopeEntry0(nameStr, type, modifiable, ofs)
        locals.put(nameStr, entry)

        return entry.toVarPtr(blockId)
    }

    fun lookupLocalVar(name: String): C_ScopeEntry? {
        var ctx: C_BlockContext? = this
        while (ctx != null) {
            val local = ctx.locals[name]
            if (local != null) {
                return local.toScopeEntry(blockId)
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
        private class C_ScopeEntry0(val name: String, val type: R_Type, val modifiable: Boolean, val offset: Int) {
            fun toVarPtr(blockId: R_FrameBlockId): R_VarPtr = R_VarPtr(blockId, offset)

            fun toScopeEntry(blockId: R_FrameBlockId): C_ScopeEntry {
                return C_ScopeEntry(name, type, modifiable, toVarPtr(blockId))
            }
        }
    }
}

sealed class C_ExprContext(val blkCtx: C_BlockContext) {
    abstract fun resolveAttr(name: S_Name): C_Expr

    abstract fun resolveNameValue(name: S_Name): C_NameResolution?

    fun resolveName(name: S_Name): C_Expr {
        val value = resolveNameValue(name)
        val valueExpr = value?.toExpr()

        val fn = blkCtx.entCtx.modCtx.getFunctionOpt(name.str)
        val fnExpr = if (fn == null) null else C_FunctionExpr(name, fn)

        val expr = makeValueFunctionExpr(name, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name)
    }

    protected fun resolveNameGlobal(name: S_Name): C_NameResolution? {
        val modCtx = blkCtx.entCtx.modCtx

        val obj = modCtx.getObjectOpt(name.str)
        if (obj != null) return C_NameResolution_Object(name, obj, blkCtx.entCtx)

        val enum = modCtx.getEnumOpt(name.str)
        if (enum != null) return C_NameResolution_Enum(name, enum)

        return null
    }
}

class C_RExprContext(blkCtx: C_BlockContext): C_ExprContext(blkCtx) {
    override fun resolveNameValue(name: S_Name): C_NameResolution? {
        val loc = blkCtx.lookupLocalVar(name.str)
        val glob = resolveNameGlobal(name)
        val ns = C_LibFunctions.getNamespace(blkCtx.entCtx.modCtx, name.str)

        if (loc != null && glob != null) {
            throw C_Errors.errNameConflictLocalGlobal(name)
        }

        if (loc != null) return C_NameResolution_Local(name, loc)
        if (glob != null) return glob
        if (ns != null) return C_NameResolution_Namespace(name, ns)

        return null
    }

    override fun resolveAttr(name: S_Name): C_Expr {
        throw C_Errors.errUnknownAttr(name)
    }
}

class C_DbExprContext(blkCtx: C_BlockContext, val classes: List<R_AtClass>): C_ExprContext(blkCtx) {
    override fun resolveNameValue(name: S_Name): C_NameResolution? {
        val nameStr = name.str

        val cls = findClassByAlias(nameStr)
        val loc = blkCtx.lookupLocalVar(nameStr)
        val glob = resolveNameGlobal(name)
        val ns = C_LibFunctions.getNamespace(blkCtx.entCtx.modCtx, nameStr)

        if (cls != null && loc != null) {
            throw C_Errors.errNameConflictAliasLocal(name)
        }
        if (cls != null && glob != null) {
            throw C_Errors.errNameConflictClassGlobal(name)
        }
        if (loc != null && glob != null) {
            throw C_Errors.errNameConflictLocalGlobal(name)
        }

        if (cls != null) return C_NameResolution_Class(name, cls)
        if (loc != null) return C_NameResolution_Local(name, loc)
        if (glob != null) return glob
        if (ns != null) return C_NameResolution_Namespace(name, ns)

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
        return C_DbExpr(name.pos, dbExpr)
    }

    private fun makeDbAttrExpr(attr: C_ClassAttr): Db_Expr {
        val clsExpr = Db_ClassExpr(attr.cls)
        val resultType = attr.attr.type
        val resultClass = if (resultType is R_ClassType) resultType.rClass else null
        return if (resultClass == null) Db_AttrExpr(clsExpr, attr.attr) else Db_RelExpr(clsExpr, attr.attr, resultClass)
    }

    fun findAttributesByName(name: String): List<C_ClassAttr> {
        return findAttrsInChain({ it.name == name })
    }

    fun findAttributesByType(type: R_Type): List<C_ClassAttr> {
        return findAttrsInChain({ it.type == type })
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

    fun findClassByAlias(alias: String): R_AtClass? {
        //TODO use a lookup table
        for (cls in classes) {
            if (cls.alias == alias) {
                return cls
            }
        }
        return null
    }
}

sealed class C_NameResolution(val name: S_Name) {
    abstract fun toExpr(): C_Expr
}

private class C_NameResolution_Class(name: S_Name, private val cls: R_AtClass): C_NameResolution(name) {
    override fun toExpr() = C_DbExpr(name.pos, Db_ClassExpr(cls))
}

private class C_NameResolution_Local(name: S_Name, private val entry: C_ScopeEntry): C_NameResolution(name) {
    override fun toExpr(): C_Expr = C_LocalExpr(name, entry)
}

private class C_NameResolution_Namespace(name: S_Name, private val ns: C_LibNamespace): C_NameResolution(name) {
    override fun toExpr(): C_Expr = C_NamespaceExpr(name, ns)
}

private class C_NameResolution_Object(
        name: S_Name,
        private val rObject: R_Object,
        private val entCtx: C_EntityContext
): C_NameResolution(name) {
    override fun toExpr(): C_Expr {
        if (rObject.entityIndex >= entCtx.entityIndex && entCtx.entityType == C_EntityType.OBJECT) {
            throw C_Error(name.pos, "object_fwdref:${name.str}", "Object '${name.str}' must be defined before using")
        }
        return C_ObjectExpr(name, rObject)
    }
}

private class C_NameResolution_Enum(name: S_Name, private val rEnum: R_EnumType): C_NameResolution(name) {
    override fun toExpr(): C_Expr = C_EnumExpr(name, rEnum)
}

typealias C_OperandConversion_R = (R_Expr) -> R_Expr
typealias C_OperandConversion_Db = (Db_Expr) -> Db_Expr

class C_OperandConversion(val r: C_OperandConversion_R?, val db: C_OperandConversion_Db?)

class C_BinOpType(
        val resType: R_Type,
        val rOp: R_BinaryOp?,
        val dbOp: Db_BinaryOp?,
        val leftConv: C_OperandConversion? = null,
        val rightConv: C_OperandConversion? = null
)

sealed class C_Expr {
    abstract fun type(): R_Type
    abstract fun startPos(): S_Pos
    abstract fun isDb(): Boolean
    abstract fun toRExpr(): R_Expr
    abstract fun toDbExpr(): Db_Expr
    abstract fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr

    open fun call(pos: S_Pos, args: List<C_Expr>): C_Expr {
        val type = type()
        val typeStr = type.toStrictString()
        throw C_Error(pos, "expr_call_nofn:$typeStr", "Not a function: value of type $typeStr")
    }

    open fun destination(): R_DestinationExpr {
        throw C_Errors.errBadDestination(startPos())
    }

    protected fun errNoValue(name: S_Name, code: String, msg: String): C_Error {
        return C_Error(name.pos, "$code:${name.str}", "Not a value: '${name.str}' is $msg")
    }
}

class C_RExpr(private val startPos: S_Pos, private val rExpr: R_Expr): C_Expr() {
    override fun type() = rExpr.type
    override fun startPos() = startPos
    override fun isDb() = false
    override fun toRExpr() = rExpr
    override fun toDbExpr() = C_Utils.toDbExpr(startPos, rExpr)

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        return memberExprForType(this, memberName, safe)
    }
}

class C_DbExpr(private val startPos: S_Pos, private val dbExpr: Db_Expr): C_Expr() {
    override fun type() = dbExpr.type
    override fun startPos() = startPos
    override fun isDb() = true
    override fun toRExpr() = throw C_Errors.errExprDbNotAllowed(startPos)
    override fun toDbExpr() = dbExpr

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        if (dbExpr is Db_TableExpr) {
            val memExpr = makeDbMemberExpr(dbExpr, memberName)
            if (memExpr == null) throw C_Errors.errUnknownMember(dbExpr.type, memberName)
            return C_DbExpr(startPos, memExpr)
        } else {
            return memberExprForType(this, memberName, safe)
        }
    }
}

class C_LookupExpr(
        private val startPos: S_Pos,
        private val baseType: R_Type,
        private val expr: R_Expr,
        private val dstExpr: R_DestinationExpr?
): C_Expr() {
    override fun type() = expr.type
    override fun startPos() = startPos
    override fun isDb() = false
    override fun toRExpr() = expr
    override fun toDbExpr() = C_Utils.toDbExpr(startPos, expr)
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = memberExprForType(this, memberName, safe)

    override fun destination(): R_DestinationExpr {
        if (dstExpr == null) {
            val type = baseType.toStrictString()
            throw C_Error(startPos, "expr_unmodifiable:$type", "Value of type '$type' cannot be modified")
        }
        return dstExpr
    }
}

private class C_LocalExpr(private val name: S_Name, private val entry: C_ScopeEntry): C_Expr() {
    override fun type() = entry.type
    override fun startPos() = name.pos
    override fun isDb() = false
    override fun toRExpr() = entry.toVarExpr()
    override fun toDbExpr() = C_Utils.toDbExpr(name.pos, toRExpr())
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = memberExprForType(this, memberName, safe)

    override fun destination(): R_DestinationExpr {
        if (!entry.modifiable) {
            throw C_Error(name.pos, "expr_assign_val:${name.str}", "Value of '${name.str}' cannot be changed")
        }
        return entry.toVarExpr()
    }
}

private class C_NamespaceExpr(private val name: S_Name, private val ns: C_LibNamespace): C_Expr() {
    override fun type() = R_NamespaceType(name.str)
    override fun startPos() = name.pos
    override fun isDb() = false
    override fun toRExpr() = throw C_Errors.errUnknownName(name)
    override fun toDbExpr() = throw C_Errors.errUnknownName(name)

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val field = ns.getValueOpt(ctx.blkCtx.entCtx, name, memberName)
        val fn = ns.getFunctionOpt(ctx.blkCtx.entCtx, name, memberName)

        val valueExpr = if (field != null) C_RExpr(memberName.pos, field) else null
        val fnExpr = if (fn != null) C_FunctionExpr(memberName, fn) else null

        val expr = makeValueFunctionExpr(memberName, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name, memberName)
    }
}

private class C_ObjectExpr(private val name: S_Name, private val rObject: R_Object): C_Expr() {
    override fun type() = rObject.type
    override fun startPos() = name.pos
    override fun isDb() = false
    override fun toRExpr() = R_ObjectExpr(rObject.type)
    override fun toDbExpr() = C_Utils.toDbExpr(name.pos, toRExpr())

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val attr = rObject.rClass.attributes[memberName.str]
        if (attr == null) {
            return throw C_Errors.errUnknownName(name, memberName)
        }

        val rExpr = createAccessExpr(attr)
        return C_RExpr(name.pos, rExpr)
    }

    private fun createAccessExpr(attr: R_Attrib): R_Expr {
        val rClass = rObject.rClass
        val atCls = R_AtClass(rClass, rClass.name, 0)
        val from = listOf(atCls)
        val whatExpr = Db_AttrExpr(Db_ClassExpr(atCls), attr)
        val what = listOf(whatExpr)
        val atBase = R_AtExprBase(from, what, null, listOf())
        return R_ObjectAttrExpr(attr.type, rObject, atBase)
    }
}

private class C_EnumExpr(private val name: S_Name, private val rEnum: R_EnumType): C_Expr() {
    override fun type() = rEnum
    override fun startPos() = name.pos
    override fun isDb() = false
    override fun toRExpr() = throw errNoValue()
    override fun toDbExpr() = throw errNoValue()

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
        return C_RExpr(name.pos, rExpr)
    }

    private fun memberFn(memberName: S_Name): C_Expr? {
        val fn = C_LibFunctions.getEnumStaticFunction(rEnum, memberName.str)
        return if (fn == null) null else C_FunctionExpr(memberName, fn)
    }

    private fun errNoValue() = errNoValue(name, "expr_val_enum", "an enum type")
}

private class C_MemberFieldExpr(
        private val base: C_Expr,
        private val name: S_Name,
        private val safe: Boolean,
        private val field: C_MemberField,
        private val type: R_Type
): C_Expr() {
    override fun type() = type
    override fun startPos() = base.startPos()
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

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        return memberExprForType(this, memberName, safe)
    }

    override fun destination(): R_DestinationExpr {
        val rBase = base.toRExpr()
        return field.destination(name.pos, rBase)
    }
}

private class C_MemberFunctionExpr(
        private val base: C_Expr,
        private val baseType: R_Type,
        private val name: S_Name,
        private val safe: Boolean,
        private val fn: C_SysMemberFunction
): C_Expr() {
    override fun type() = throw errNoValue()
    override fun startPos() = base.startPos()
    override fun isDb() = base.isDb()
    override fun toRExpr() = throw errNoValue()
    override fun toDbExpr() = throw errNoValue()
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = throw errNoValue()
    override fun call(pos: S_Pos, args: List<C_Expr>) = fn.compileCall(name, base, safe, args)

    private fun errNoValue() = errNoValue(name, "expr_val_fn", "a function")
}

private class C_FunctionExpr(private val name: S_Name, private val fn: C_GlobalFunction): C_Expr() {
    override fun type() = throw errNoValue()
    override fun startPos() = name.pos
    override fun isDb() = throw errNoValue()
    override fun toRExpr() = throw errNoValue()
    override fun toDbExpr() = throw errNoValue()
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = throw errNoValue()
    override fun call(pos: S_Pos, args: List<C_Expr>) = fn.compileCall(name, args)

    private fun errNoValue() = errNoValue(name, "expr_val_fn", "a function")
}

private class C_ClassFieldExpr(
        private val base: C_Expr,
        private val baseSafe: Boolean,
        private val atClass: R_AtClass,
        private val dbExpr: Db_Expr,
        private val resultType: R_Type
): C_Expr() {
    override fun type() = resultType
    override fun startPos() = base.startPos()
    override fun isDb() = false // Important: node does not belong to an outer @-expression

    override fun toRExpr(): R_Expr {
        val from = listOf(atClass)

        val whereLeft = Db_ClassExpr(atClass)
        val whereRight = Db_ParameterExpr(atClass.type, 0)
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Eq, whereLeft, whereRight)

        val atBase = R_AtExprBase(from, listOf(dbExpr), where, listOf())
        val calculator = R_MemberCalculator_DataAttribute(dbExpr.type, atBase)

        val rBase = base.toRExpr()
        val rExpr = R_MemberExpr(rBase, baseSafe, calculator)
        return rExpr
    }

    override fun toDbExpr() = dbExpr

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val valueExpr = memberValue(memberName, safe)
        val fnExpr = memberFunctionForType(this, dbExpr.type, memberName, safe)

        val res = makeValueFunctionExpr(memberName, valueExpr, fnExpr)
        if (res == null) {
            throw C_Errors.errUnknownMember(dbExpr.type, memberName)
        }

        checkNullAccess(resultType, memberName, safe)
        return res
    }

    private fun memberValue(memberName: S_Name, safe: Boolean): C_Expr? {
        val resDbExpr = makeDbMemberExpr(dbExpr, memberName)
        if (resDbExpr == null) return null
        checkNullAccess(resultType, memberName, safe)
        val resResultType = C_Utils.effectiveMemberType(resDbExpr.type, safe)
        return C_ClassFieldExpr(base, baseSafe, atClass, resDbExpr, resResultType)
    }
}

private class C_ValueFunctionExpr(private val name: S_Name, private val valueExpr: C_Expr?, private val fnExpr: C_Expr?): C_Expr() {
    override fun type() = toValueExpr().type()
    override fun startPos() = name.pos
    override fun isDb() = toValueExpr().isDb()
    override fun toRExpr() = toValueExpr().toRExpr()
    override fun toDbExpr() = toValueExpr().toDbExpr()
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = toValueExpr().member(ctx, memberName, safe)
    override fun call(pos: S_Pos, args: List<C_Expr>) = toFnExpr().call(pos, args)
    override fun destination() = toValueExpr().destination()

    private fun toValueExpr(): C_Expr = valueExpr ?: throw C_Errors.errUnknownName(name)
    private fun toFnExpr(): C_Expr = fnExpr ?: throw C_Error(name.pos, "expr_nofn:${name.str}", "Not a function: '${name.str}'")
}

private fun makeValueFunctionExpr(name: S_Name, valueExpr: C_Expr?, fnExpr: C_Expr?): C_Expr? {
    if (valueExpr != null && fnExpr != null) {
        return C_ValueFunctionExpr(name, valueExpr, fnExpr)
    } else if (valueExpr != null) {
        return valueExpr
    } else {
        return fnExpr
    }
}

private fun memberExprForType(base: C_Expr, name: S_Name, safe: Boolean): C_Expr {
    val baseType = base.type()
    val effectiveBaseType = if (baseType is R_NullableType) baseType.valueType else baseType

    val valueExpr = memberFieldForType(base, effectiveBaseType, name, safe)
    val fnExpr = memberFunctionForType(base, effectiveBaseType, name, safe)

    if (valueExpr == null && fnExpr == null) {
        throw C_Errors.errUnknownMember(effectiveBaseType, name)
    }

    checkNullAccess(baseType, name, safe)

    val expr = makeValueFunctionExpr(name, valueExpr, fnExpr)
    return expr!!
}

private fun memberFieldForType(base: C_Expr, type: R_Type, name: S_Name, safe: Boolean): C_Expr? {
    if (type is R_TupleType) {
        return memberFieldForTuple(base, type, name, safe)
    } else if (type is R_RecordType) {
        return memberFieldForRecord(base, type, name, safe)
    } else if (type is R_ClassType) {
        return memberFieldForClass(base, type, name, safe)
    } else if (type is R_EnumType) {
        return memberFieldForEnum(base, name, safe)
    } else {
        return null
    }
}

private fun memberFieldForTuple(base: C_Expr, type: R_TupleType, name: S_Name, safe: Boolean): C_Expr? {
    val idx = type.fields.indexOfFirst { it.name == name.str }
    if (idx == -1) {
        return null
    }

    val field = C_MemberField_TupleField(type.fields[idx].type, idx)
    return makeMemberExpr(base, name, safe, field)
}

private fun memberFieldForRecord(base: C_Expr, type: R_RecordType, name: S_Name, safe: Boolean): C_Expr? {
    val attr = type.attributes[name.str]
    if (attr == null) {
        return null
    }

    val field = C_MemberField_RecordAttr(attr)
    return makeMemberExpr(base, name, safe, field)
}

private fun memberFieldForClass(base: C_Expr, type: R_ClassType, name: S_Name, safe: Boolean): C_Expr? {
    val atClass = R_AtClass(type.rClass, "", 0)
    val baseDbExpr = Db_ClassExpr(atClass)
    val dbExpr = makeDbMemberExpr(baseDbExpr, name)
    if (dbExpr == null) return null
    val resultType = C_Utils.effectiveMemberType(dbExpr.type, safe)
    return C_ClassFieldExpr(base, safe, atClass, dbExpr, resultType)
}

private fun memberFieldForEnum(base: C_Expr, name: S_Name, safe: Boolean): C_Expr? {
    val calculator = if (name.str == "name") {
        R_MemberCalculator_SysFn(R_TextType, R_SysFn_Enum_Name, listOf())
    } else if (name.str == "value") {
        R_MemberCalculator_SysFn(R_IntegerType, R_SysFn_Enum_Value, listOf())
    } else {
        return null
    }
    val field = C_MemberField_SimpleReadOnly(name, calculator)
    return makeMemberExpr(base, name, safe, field)
}

private fun memberFunctionForType(base: C_Expr, type: R_Type, name: S_Name, safe: Boolean): C_Expr? {
    val fn = C_LibFunctions.getMemberFunctionOpt(type, name.str)
    return if (fn == null) null else C_MemberFunctionExpr(base, type, name, safe, fn)
}

private fun makeMemberExpr(base: C_Expr, name: S_Name, safe: Boolean, field: C_MemberField): C_Expr {
    val fieldType = field.type
    val effectiveType = C_Utils.effectiveMemberType(fieldType, safe)
    return C_MemberFieldExpr(base, name, safe, field, effectiveType)
}

private fun makeDbMemberExpr(base: Db_Expr, name: S_Name): Db_Expr? {
    if (base !is Db_TableExpr) return null
    val attr = base.rClass.attributes[name.str]
    if (attr == null) {
        return null
    }

    val resultType = attr.type
    val resultClass = if (resultType is R_ClassType) resultType.rClass else null
    return if (resultClass == null) Db_AttrExpr(base, attr) else Db_RelExpr(base, attr, resultClass)
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
