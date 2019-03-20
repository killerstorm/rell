package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_EnumValue

class C_AtClass(val rClass: R_Class, val alias: String, val index: Int) {
    private val rAtClass = R_AtClass(rClass, index)

    fun compile() = rAtClass
    fun compileExpr() = Db_ClassExpr(rAtClass)
}

class C_ClassAttr(val cls: C_AtClass, val attr: R_Attrib)

class C_BlockContext(
        val entCtx: C_EntityContext,
        private val parent: C_BlockContext?,
        val insideLoop: Boolean
){
    private val startOffset: Int = if (parent == null) 0 else parent.startOffset + parent.locals.size
    private val locals = mutableMapOf<String, C_ScopeEntry0>()

    private val blockId = entCtx.nsCtx.modCtx.globalCtx.nextFrameBlockId()

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

        val fn = blkCtx.entCtx.nsCtx.getFunctionOpt(name.str)
        val fnExpr = if (fn == null) null else C_FunctionExpr(name, fn)

        val expr = makeValueFunctionExpr(name, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name)
    }

    protected fun resolveNameGlobal(name: S_Name): C_NameResolution? {
        val nsCtx = blkCtx.entCtx.nsCtx
        val value = nsCtx.getValueOpt(name.str)
        return if (value == null) null else C_NameResolution_Value(name, blkCtx.entCtx, value)
    }
}

class C_RExprContext(blkCtx: C_BlockContext): C_ExprContext(blkCtx) {
    override fun resolveNameValue(name: S_Name): C_NameResolution? {
        val loc = blkCtx.lookupLocalVar(name.str)
        val glob = blkCtx.entCtx.nsCtx.getValueOpt(name.str)

        if (loc != null) return C_NameResolution_Local(name, loc)
        if (glob != null) return C_NameResolution_Value(name, blkCtx.entCtx, glob)

        return null
    }

    override fun resolveAttr(name: S_Name): C_Expr {
        throw C_Errors.errUnknownAttr(name)
    }
}

class C_DbExprContext(blkCtx: C_BlockContext, val classes: List<C_AtClass>): C_ExprContext(blkCtx) {
    override fun resolveNameValue(name: S_Name): C_NameResolution? {
        val nameStr = name.str

        val cls = findClassByAlias(nameStr)
        val loc = blkCtx.lookupLocalVar(nameStr)
        val glob = blkCtx.entCtx.nsCtx.getValueOpt(name.str)

        if (cls != null && loc != null) {
            throw C_Errors.errNameConflictAliasLocal(name)
        }
        if (cls != null && glob != null) {
            throw C_Errors.errNameConflictClassGlobal(name)
        }

        if (cls != null) return C_NameResolution_Class(name, cls)
        if (loc != null) return C_NameResolution_Local(name, loc)
        if (glob != null) return C_NameResolution_Value(name, blkCtx.entCtx, glob)

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
        val clsExpr = attr.cls.compileExpr()
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

    fun findClassByAlias(alias: String): C_AtClass? {
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

private class C_NameResolution_Class(name: S_Name, private val cls: C_AtClass): C_NameResolution(name) {
    override fun toExpr() = C_DbExpr(name.pos, cls.compileExpr())
}

private class C_NameResolution_Local(name: S_Name, private val entry: C_ScopeEntry): C_NameResolution(name) {
    override fun toExpr(): C_Expr = C_LocalVarExpr(name, entry)
}

private class C_NameResolution_Value(
        name: S_Name,
        private val entCtx: C_EntityContext,
        private val value: C_NamespaceValue
): C_NameResolution(name) {
    override fun toExpr(): C_Expr = value.get(entCtx, listOf(name))
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

class C_AssignOp(val pos: S_Pos, val code: String, val rOp: R_BinaryOp, val dbOp: Db_BinaryOp?)

sealed class C_Destination {
    abstract fun type(): R_Type
    abstract fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement
}

class C_SimpleDestination(private val rDstExpr: R_DestinationExpr): C_Destination() {
    override fun type() = rDstExpr.type

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        return R_AssignStatement(rDstExpr, srcExpr, op?.rOp)
    }
}

class C_ClassFieldDestination(private val base: C_Value, private val rClass: R_Class, private val attr: R_Attrib): C_Destination() {
    override fun type() = attr.type

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        if (op != null && op.dbOp == null) {
            throw S_BinaryOp.errTypeMissmatch(op.pos, op.code, attr.type, srcExpr.type)
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
}

class C_ObjectFieldDestination(private val rObject: R_Object, private val attr: R_Attrib): C_Destination() {
    override fun type() = attr.type

    override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
        if (op != null && op.dbOp == null) {
            throw S_BinaryOp.errTypeMissmatch(op.pos, op.code, attr.type, srcExpr.type)
        }

        val rTarget = R_UpdateTarget_Object(rObject)
        val dbExpr = Db_InterpretedExpr(srcExpr)
        val rWhat = R_UpdateStatementWhat(attr, dbExpr, op?.dbOp)
        return R_UpdateStatement(rTarget, listOf(rWhat))
    }
}

sealed class C_Value(val pos: S_Pos) {
    abstract fun type(): R_Type
    abstract fun isDb(): Boolean
    abstract fun toRExpr(): R_Expr
    abstract fun toDbExpr(): Db_Expr

    open fun destination(): C_Destination {
        throw C_Errors.errBadDestination(pos)
    }
}

private class C_RValue(pos: S_Pos, private val rExpr: R_Expr): C_Value(pos) {
    override fun type() = rExpr.type
    override fun isDb() = false
    override fun toRExpr() = rExpr
    override fun toDbExpr() = C_Utils.toDbExpr(pos, rExpr)
}

private class C_DbValue(pos: S_Pos, private val dbExpr: Db_Expr): C_Value(pos) {
    override fun type() = dbExpr.type
    override fun isDb() = true
    override fun toRExpr() = throw C_Errors.errExprDbNotAllowed(pos)
    override fun toDbExpr() = dbExpr
}

private class C_LookupValue(
        pos: S_Pos,
        private val baseType: R_Type,
        private val expr: R_Expr,
        private val dstExpr: R_DestinationExpr?
): C_Value(pos) {
    override fun type() = expr.type
    override fun isDb() = false
    override fun toRExpr() = expr
    override fun toDbExpr() = C_Utils.toDbExpr(pos, expr)

    override fun destination(): C_Destination {
        if (dstExpr == null) {
            val type = baseType.toStrictString()
            throw C_Error(pos, "expr_unmodifiable:$type", "Value of type '$type' cannot be modified")
        }
        return C_SimpleDestination(dstExpr)
    }
}

private class C_LocalVarValue(private val name: S_Name, private val entry: C_ScopeEntry): C_Value(name.pos) {
    override fun type() = entry.type
    override fun isDb() = false
    override fun toRExpr() = entry.toVarExpr()
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(): C_Destination {
        if (!entry.modifiable) {
            throw C_Error(name.pos, "expr_assign_val:${name.str}", "Value of '${name.str}' cannot be changed")
        }
        val rDstExpr = entry.toVarExpr()
        return C_SimpleDestination(rDstExpr)
    }
}

private class C_ObjectValue(pos: S_Pos, private val rObject: R_Object): C_Value(pos) {
    override fun type() = rObject.type
    override fun isDb() = false
    override fun toRExpr() = R_ObjectExpr(rObject.type)
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())
}

private class C_ObjectFieldValue(
        pos: S_Pos,
        private val rObject: R_Object,
        private val attr: R_Attrib
): C_Value(pos) {
    override fun type() = attr.type
    override fun isDb() = false
    override fun toRExpr() = createAccessExpr()
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(): C_Destination {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
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
        private val type: R_Type
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

    override fun destination(): C_Destination {
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
        private val memberExpr: C_DbMemberExpr,
        private val resultType: R_Type
): C_Value(pos) {
    override fun type() = resultType
    override fun isDb() = false // Important: node does not belong to an outer @-expression

    override fun toRExpr(): R_Expr {
        val from = listOf(atClass)

        val whereLeft = Db_ClassExpr(atClass)
        val whereRight = Db_ParameterExpr(atClass.type, 0)
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Eq, whereLeft, whereRight)

        val atBase = R_AtExprBase(from, listOf(memberExpr.dbExpr), where, listOf())
        val calculator = R_MemberCalculator_DataAttribute(memberExpr.dbExpr.type, atBase)

        val rBase = base.toRExpr()
        val rExpr = R_MemberExpr(rBase, baseSafe, calculator)
        return rExpr
    }

    // Cannot inject the corresponding Db_Expr directly into another Db_Expr - must wrap it in R_Expr.
    override fun toDbExpr() = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(): C_Destination {
        if (!memberExpr.attr.mutable) {
            throw C_Errors.errAttrNotMutable(memberExpr.name.pos, memberExpr.attr.name)
        }
        return C_ClassFieldDestination(parent, memberExpr.rClass, memberExpr.attr)
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
        val pos = startPos()
        val baseValue = value() // May fail with "not a value" - OK.
        val baseType = baseValue.type()
        val effectiveBaseType = if (baseType is R_NullableType) baseType.valueType else baseType

        val valueExpr = memberFieldForType(pos, baseValue, effectiveBaseType, memberName, safe)
        val fnExpr = memberFunctionForType(pos, baseValue, effectiveBaseType, memberName, safe)

        if (valueExpr == null && fnExpr == null) {
            throw C_Errors.errUnknownMember(effectiveBaseType, memberName)
        }

        checkNullAccess(baseType, memberName, safe)

        val expr = makeValueFunctionExpr(memberName, valueExpr, fnExpr)
        return expr!!
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

class C_RExpr(private val startPos: S_Pos, private val rExpr: R_Expr): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = startPos
    override fun value(): C_Value = C_RValue(startPos, rExpr)
}

class C_DbExpr(private val startPos: S_Pos, private val dbExpr: Db_Expr): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = startPos
    override fun value(): C_Value = C_DbValue(startPos, dbExpr)

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        if (dbExpr is Db_TableExpr) {
            val memExpr = makeDbMemberExpr(dbExpr, memberName)
            if (memExpr == null) throw C_Errors.errUnknownMember(dbExpr.type, memberName)
            return C_DbExpr(startPos, memExpr.dbExpr)
        }
        return super.member(ctx, memberName, safe)
    }
}

class C_LookupExpr(
        private val startPos: S_Pos,
        private val baseType: R_Type,
        private val expr: R_Expr,
        private val dstExpr: R_DestinationExpr?
): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = startPos
    override fun value(): C_Value = C_LookupValue(startPos, baseType, expr, dstExpr)
}

private class C_LocalVarExpr(private val name: S_Name, private val entry: C_ScopeEntry): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = name.pos
    override fun value(): C_Value = C_LocalVarValue(name, entry)
}

class C_NamespaceExpr(private val name: List<S_Name>, private val ns: C_Namespace): C_Expr() {
    override fun kind() = C_ExprKind.NAMESPACE
    override fun startPos() = name[0].pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val valueExpr = memberValue(ctx.blkCtx.entCtx, memberName)

        val fn = ns.functions[memberName.str]
        val fnExpr = if (fn != null) C_FunctionExpr(memberName, fn) else null

        val expr = makeValueFunctionExpr(memberName, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name, memberName)
    }

    private fun memberValue(entCtx: C_EntityContext, memberName: S_Name): C_Expr? {
        val nsValue = ns.values[memberName.str]
        val valueExpr = nsValue?.get(entCtx, name + listOf(memberName))
        if (valueExpr != null) return valueExpr

        val nsType = ns.types[memberName.str]
        return if (nsType == null) null else C_TypeExpr(memberName, nsType)
    }
}

class C_RecordExpr(private val name: List<S_Name>, private val record: R_RecordType, private val ns: C_Namespace): C_Expr() {
    override fun kind() = C_ExprKind.RECORD
    override fun startPos() = name[0].pos

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val nsExpr = C_NamespaceExpr(name, ns)
        return nsExpr.member(ctx, memberName, safe)
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        val startPos = startPos()
        val attrs = C_AttributeResolver.resolveCreate(ctx, record.attributes, args, startPos)
        val rExpr = R_RecordExpr(record, attrs)
        return C_RExpr(startPos, rExpr)
    }
}

class C_ObjectExpr(private val name: List<S_Name>, private val rObject: R_Object): C_Expr() {
    override fun kind() = C_ExprKind.OBJECT
    override fun startPos() = name[0].pos
    override fun value(): C_Value = C_ObjectValue(startPos(), rObject)

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val attr = rObject.rClass.attributes[memberName.str]
        attr ?: throw C_Errors.errUnknownName(name, memberName)
        return C_ObjectFieldExpr(memberName, rObject, attr)
    }
}

private class C_ObjectFieldExpr(
        private val name: S_Name,
        private val rObject: R_Object,
        private val attr: R_Attrib
): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = name.pos
    override fun value(): C_Value = C_ObjectFieldValue(name.pos, rObject, attr)
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
        return C_RExpr(startPos(), rExpr)
    }

    private fun memberFn(memberName: S_Name): C_Expr? {
        val fn = C_LibFunctions.getEnumStaticFunction(rEnum, memberName.str)
        return if (fn == null) null else C_FunctionExpr(memberName, fn)
    }
}

private class C_MemberFieldExpr(
        private val startPos: S_Pos,
        private val base: C_Value,
        private val name: S_Name,
        private val safe: Boolean,
        private val field: C_MemberField,
        private val type: R_Type
): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = startPos
    override fun value() = C_MemberFieldValue(startPos(), base, name, safe, field, type)
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
        return fn.compileCall(name, base, safe, cArgs)
    }
}

class C_FunctionExpr(private val name: S_Name, private val fn: C_GlobalFunction): C_Expr() {
    override fun kind() = C_ExprKind.FUNCTION
    override fun startPos() = name.pos

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>): C_Expr {
        val cArgs = compileArgs(ctx, args)
        return fn.compileCall(name, cArgs)
    }
}

private class C_ClassFieldExpr(
        private val startPos: S_Pos,
        private val base: C_Value,
        private val baseSafe: Boolean,
        private val atClass: R_AtClass,
        private val parent: C_Value,
        private val memberExpr: C_DbMemberExpr,
        private val resultType: R_Type
): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = startPos
    override fun value(): C_Value = C_ClassFieldValue(startPos, base, baseSafe, atClass, parent, memberExpr, resultType)

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val thisValue = value()
        val valueExpr = memberValue(thisValue, memberName, safe)
        val fnExpr = memberFunctionForType(startPos, thisValue, memberExpr.dbExpr.type, memberName, safe)

        val res = makeValueFunctionExpr(memberName, valueExpr, fnExpr)
        res ?: throw C_Errors.errUnknownMember(memberExpr.dbExpr.type, memberName)

        checkNullAccess(resultType, memberName, safe)
        return res
    }

    private fun memberValue(thisValue: C_Value, memberName: S_Name, safe: Boolean): C_Expr? {
        val resDbExpr = makeDbMemberExpr(memberExpr.dbExpr, memberName)
        if (resDbExpr == null) return null
        checkNullAccess(resultType, memberName, safe)
        val resResultType = C_Utils.effectiveMemberType(resDbExpr.dbExpr.type, safe)
        return C_ClassFieldExpr(startPos, base, baseSafe, atClass, thisValue, resDbExpr, resResultType)
    }
}

class C_TypeExpr(private val name: S_Name, private val type: R_Type): C_Expr() {
    override fun kind() = C_ExprKind.TYPE
    override fun startPos() = name.pos
}

class C_ValueFunctionExpr(private val name: S_Name, private val valueExpr: C_Expr, private val fnExpr: C_Expr): C_Expr() {
    override fun kind() = C_ExprKind.VALUE
    override fun startPos() = name.pos
    override fun value() = valueExpr.value()
    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean) = valueExpr.member(ctx, memberName, safe)
    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_NameExprPair>) = fnExpr.call(ctx, pos, args)
}

private class C_DbMemberExpr(val rClass: R_Class, val name: S_Name, val attr: R_Attrib, val dbExpr: Db_Expr)

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
    if (memExpr == null) return null
    val resultType = C_Utils.effectiveMemberType(memExpr.dbExpr.type, safe)
    return C_ClassFieldExpr(pos, base, safe, atClass, base, memExpr, resultType)
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
    return C_MemberFieldExpr(pos, base, name, safe, field, effectiveType)
}

private fun makeDbMemberExpr(base: Db_Expr, name: S_Name): C_DbMemberExpr? {
    if (base !is Db_TableExpr) return null
    val attr = base.rClass.attributes[name.str]
    if (attr == null) {
        return null
    }

    val resultType = attr.type
    val resultClass = if (resultType is R_ClassType) resultType.rClass else null
    val dbExpr = if (resultClass == null) Db_AttrExpr(base, attr) else Db_RelExpr(base, attr, resultClass)
    return C_DbMemberExpr(base.rClass, name, attr, dbExpr)
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
