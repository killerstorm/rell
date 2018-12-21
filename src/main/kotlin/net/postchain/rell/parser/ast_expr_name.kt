package net.postchain.rell.parser

import net.postchain.rell.model.*
import java.util.*

class S_NameExpr(val name: S_Name): S_Expr(name.pos) {
    override fun asName(): S_Name? = name

    override fun compile(ctx: C_ExprContext): RExpr {
        val res = resolveName(ctx, name)
        if (res is C_NameResolution_Local) {
            return res.loc.toVarExpr()
        } else {
            throw C_Utils.errUnknownName(name)
        }
    }

    override fun compileDestination(opPos: S_Pos, ctx: C_ExprContext): RDestinationExpr {
        val res = resolveName(ctx, name)
        if (res is C_NameResolution_Local) {
            if (!res.loc.modifiable) {
                throw C_Error(name.pos, "stmt_assign_val:${name.str}", "Value of '${name.str}' cannot be changed")
            }
            return res.loc.toVarExpr()
        } else {
            throw C_Utils.errUnknownName(name)
        }
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val res = resolveNameDb(ctx, name)
        if (res is C_DbNameResolution_Class) {
            return ClassDbExpr(res.cls)
        } else if (res is C_DbNameResolution_Local) {
            val rExpr = res.loc.toVarExpr()
            return InterpretedDbExpr(rExpr)
        } else {
            throw C_Utils.errUnknownName(name)
        }
    }

    override fun compileDbWhere(ctx: C_DbExprContext, idx: Int): DbExpr {
        val clsAttrs = ctx.findAttributesByName(name.str)
        val localVar = ctx.exprCtx.lookupOpt(name.str)

        if (clsAttrs.isEmpty() || localVar == null) {
            return compileDb(ctx)
        }

        // There is a class attribute and a local variable with such name -> implicit where condition.

        val argType = localVar.type
        val clsAttrsByType = clsAttrs.filter { S_BinaryOp_EqNe.checkTypes(it.attr.type, argType) }

        if (clsAttrsByType.isEmpty()) {
            val typeStr = argType.toStrictString()
            throw C_Error(name.pos, "at_attr_type:$idx:${name.str}:$typeStr",
                    "No attribute '${name.str}' of type $typeStr")
        } else if (clsAttrsByType.size > 1) {
            throw C_Utils.errMutlipleAttrs(name.pos, clsAttrsByType, "at_attr_name_ambig:$idx:${name.str}",
                    "Multiple attributes match '${name.str}'")
        }

        val clsAttr = clsAttrsByType[0]
        val attrType = clsAttr.attr.type
        if (!S_BinaryOp_EqNe.checkTypes(attrType, argType)) {
            throw C_Error(name.pos, "at_param_attr_type_missmatch:$name:$attrType:$argType",
                    "Parameter type does not match attribute type for '$name': $argType instead of $attrType")
        }

        val clsAttrExpr = AttrDbExpr(ClassDbExpr(clsAttr.cls), clsAttr.attr)
        val localAttrExpr = localVar.toVarExpr()
        return BinaryDbExpr(RBooleanType, DbBinaryOp_Eq, clsAttrExpr, InterpretedDbExpr(localAttrExpr))
    }

    override fun compileCall(ctx: C_ExprContext, args: List<RExpr>): RExpr {
        val fn = ctx.entCtx.modCtx.getFunction(name)
        return fn.compileCall(name, args)
    }

    override fun compileCallDb(ctx: C_DbExprContext, args: List<DbExpr>): DbExpr {
        val fn = ctx.exprCtx.entCtx.modCtx.getFunction(name)
        return compileCallDbGlobal(name, fn, args)
    }

    override fun discoverPathExpr(tailPath: List<S_Name>): Pair<S_Expr?, List<S_Name>> {
        return Pair(null, listOf(name) + tailPath)
    }
}

class S_AttrExpr(pos: S_Pos, val name: S_Name): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): RExpr {
        throw C_Utils.errUnknownAttr(name)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val attr = resolveAttrDb(ctx, name)
        return makeDbAttrExpr(attr)
    }
}

class S_MemberExpr(val base: S_Expr, val name: S_Name): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): RExpr {
        val (deepBase, path) = discoverPathExpr(listOf())
        if (deepBase != null) {
            val rBase = deepBase.compile(ctx)
            return C_PathExprUtils.compilePath(ctx, rBase, path)
        } else {
            return C_PathExprUtils.compilePath(ctx, path)
        }
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val (deepBase, path) = discoverPathExpr(listOf())
        if (deepBase != null) {
            val dbBase = deepBase.compileDb(ctx)
            return C_PathExprUtils.compilePathDb(ctx, dbBase, path)
        } else {
            return C_PathExprUtils.compilePathDb(ctx, path)
        }
    }

    override fun compileDestination(opPos: S_Pos, ctx: C_ExprContext): RDestinationExpr {
        val rBase = base.compile(ctx)
        val member = C_PathExprUtils.compilePathStep(ctx, rBase.type, name)
        return member.destination(opPos, rBase)
    }

    override fun compileCall(ctx: C_ExprContext, args: List<RExpr>): RExpr {
        val (deepBase, path) = discoverPathExpr(listOf())
        check(path.size > 0)
        check(path[path.size - 1] == name)
        val leftPath = path.subList(0, path.size - 1)

        if (deepBase != null) {
            val rDeepBase = deepBase.compile(ctx)
            val rBase = C_PathExprUtils.compilePath(ctx, rDeepBase, leftPath)
            return compileCall0(rBase, args)
        }

        if (leftPath.size > 1) {
            val rBase = C_PathExprUtils.compilePath(ctx, leftPath)
            return compileCall0(rBase, args)
        }

        check(leftPath.size == 1)
        val head = leftPath[0]
        val res = resolveName(ctx, head)

        if (res is C_NameResolution_Local) {
            val rBase = res.loc.toVarExpr()
            return compileCall0(rBase, args)
        } else if (res is C_NameResolution_Namespace) {
            val fn = res.ns.getFunction(ctx.entCtx, head, name)
            return fn.compileCall(name, args)
        } else {
            throw C_Utils.errUnknownName(head)
        }
    }

    override fun compileCallDb(ctx: C_DbExprContext, args: List<DbExpr>): DbExpr {
        val (deepBase, path) = discoverPathExpr(listOf())
        check(path.size > 0)
        check(path[path.size - 1] == name)
        val leftPath = path.subList(0, path.size - 1)

        if (deepBase != null) {
            val dbDeepBase = deepBase.compileDb(ctx)
            val dbBase = C_PathExprUtils.compilePathDb(ctx, dbDeepBase, leftPath)
            return compileCallDb0(dbBase, args)
        }

        check(leftPath.size >= 1)

        if (leftPath.size > 1) {
            val dbBase = C_PathExprUtils.compilePathDb(ctx, leftPath)
            return compileCallDb0(dbBase, args)
        }

        check(leftPath.size == 1)
        val head = leftPath[0]

        val res = resolveNameDb(ctx, head)

        if (res is C_DbNameResolution_Class) {
            val dbBase = ClassDbExpr(res.cls)
            return compileCallDb0(dbBase, args)
        } else if (res is C_DbNameResolution_Local) {
            val rBase = res.loc.toVarExpr()
            val dbBase = InterpretedDbExpr(rBase)
            return compileCallDb0(dbBase, args)
        } else if (res is C_DbNameResolution_Namespace) {
            val fn = res.ns.getFunction(ctx.exprCtx.entCtx, head, name)
            return compileCallDbGlobal(name, fn, args)
        } else {
            throw errBadPath(path, 2)
        }
    }

    private fun compileCall0(rBase: RExpr, args: List<RExpr>): RExpr {
        val calculator = C_PathExprUtils.compileCallStep(rBase.type, name, args)
        return RMemberExpr(rBase, false, calculator)
    }

    private fun compileCallDb0(dbBase: DbExpr, args: List<DbExpr>): DbExpr {
        val fn = S_LibFunctions.getMemberFunction(dbBase.type, name)

        val rArgs = args.filter { it is InterpretedDbExpr }.map { (it as InterpretedDbExpr).expr }
        if (dbBase is InterpretedDbExpr && rArgs.size == args.size) {
            val calculator = fn.compileCall(name.pos, dbBase.type, rArgs)
            val rExpr = RMemberExpr(dbBase.expr, false, calculator)
            return InterpretedDbExpr(rExpr)
        }

        return fn.compileCallDb(name.pos, dbBase, args)
    }

    override fun discoverPathExpr(tailPath: List<S_Name>): Pair<S_Expr?, List<S_Name>> {
        return base.discoverPathExpr(listOf(name) + tailPath)
    }
}

class S_SafeMemberExpr(val base: S_Expr, val name: S_Name): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): RExpr {
        val rBase = base.compile(ctx)
        return compile0(ctx, rBase)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val dbBase = base.compileDb(ctx)
        if (dbBase is InterpretedDbExpr) {
            val rExpr = compile0(ctx.exprCtx, dbBase.expr)
            return InterpretedDbExpr(rExpr)
        } else {
            throw errWrongType(dbBase.type)
        }
    }

    private fun compile0(ctx: C_ExprContext, rBase: RExpr): RExpr {
        val valueType = getValueType(rBase)
        val member = C_PathExprUtils.compilePathStep(ctx, valueType, name)
        val calculator = member.calculator()
        return RMemberExpr(rBase, true, calculator)
    }

    override fun compileDestination(opPos: S_Pos, ctx: C_ExprContext): RDestinationExpr {
        val rBase = base.compile(ctx)
        val valueType = getValueType(rBase)
        val member = C_PathExprUtils.compilePathStep(ctx, valueType, name)
        val dest = member.destination(opPos, rBase)
        return dest
    }

    override fun compileCall(ctx: C_ExprContext, args: List<RExpr>): RExpr {
        val rBase = base.compile(ctx)
        val valueType = getValueType(rBase)
        val calculator = C_PathExprUtils.compileCallStep(valueType, name, args)
        return RMemberExpr(rBase, true, calculator)
    }

    private fun getValueType(rBase: RExpr): RType {
        val baseType = rBase.type
        if (baseType !is RNullableType) {
            throw errWrongType(baseType)
        }
        return baseType.valueType
    }

    private fun errWrongType(type: RType): C_Error {
        return C_Error(name.pos, "expr_safemem_type:${type.toStrictString()}",
                "Wrong type for operator '?.': ${type.toStrictString()}")
    }
}

object C_PathExprUtils {
    fun compilePath(ctx: C_ExprContext, path: List<S_Name>): RExpr {
        check(!path.isEmpty())

        val head = path[0]
        val res = resolveName(ctx, head)

        if (res is C_NameResolution_Local) {
            val rBase = res.loc.toVarExpr()
            return compilePath(ctx, rBase, path.subList(1, path.size))
        } else if (res is C_NameResolution_Namespace && path.size >= 2) {
            val second = path[1]
            val rBase = res.ns.getValue(ctx.entCtx, head, second)
            return compilePath(ctx, rBase, path.subList(2, path.size))
        } else {
            throw C_Utils.errUnknownName(head)
        }
    }

    fun compilePath(ctx: C_ExprContext, rBase: RExpr, path: List<S_Name>): RExpr {
        var headBase = rBase
        val tailPath = LinkedList(path)

        while (!tailPath.isEmpty()) {
            val member = compilePathStep(ctx, headBase.type, tailPath)
            val calculator = member.calculator()
            headBase = RMemberExpr(headBase, false, calculator)
        }

        return headBase
    }

    fun compilePathStep(ctx: C_ExprContext, baseType: RType, name: S_Name): C_PathMember {
        val tailPath = LinkedList(listOf(name))
        val member = C_PathExprUtils.compilePathStep(ctx, baseType, tailPath)
        check(tailPath.isEmpty())
        return member
    }

    private fun compilePathStep(ctx: C_ExprContext, baseType: RType, tailPath: Queue<S_Name>): C_PathMember {
        val name = tailPath.peek()

        val effectiveType = if (baseType is RNullableType) baseType.valueType else baseType
        val calculator = compilePathStep0(ctx, effectiveType, tailPath)

        if (baseType is RNullableType) {
            throw C_Error(name.pos, "expr_mem_null:${name.str}", "Cannot access member '${name.str}' of null")
        }

        return calculator
    }

    private fun compilePathStep0(ctx: C_ExprContext, baseType: RType, tailPath: Queue<S_Name>): C_PathMember {
        if (baseType is RTupleType) {
            return compilePathStepTuple(baseType, tailPath)
        } else if (baseType is RRecordType) {
            return compilePathStepRecord(baseType, tailPath)
        } else if (baseType is RInstanceRefType) {
            return compilePathStepDataObject(ctx, baseType, tailPath)
        } else {
            val name = tailPath.peek()
            throw C_Utils.errUnknownMember(baseType, name)
        }
    }

    private fun compilePathStepTuple(type: RTupleType, path: Queue<S_Name>): C_PathMember {
        val step = path.remove()
        val idx = type.fields.indexOfFirst { it.name == step.str }
        if (idx == -1) {
            throw C_Utils.errUnknownMember(type, step)
        }
        return C_PathMember_TupleField(type.fields[idx].type, idx)
    }

    private fun compilePathStepRecord(type: RRecordType, path: Queue<S_Name>): C_PathMember {
        val step = path.remove()
        val attr = type.attributes[step.str]
        if (attr == null) {
            throw C_Utils.errUnknownMember(type, step)
        }
        return C_PathMember_RecordAttr(attr)
    }

    private fun compilePathStepDataObject(
            ctx: C_ExprContext,
            type: RInstanceRefType,
            path: Queue<S_Name>): C_PathMember
    {
        val atClass = RAtClass(type.rClass, "", 0)
        val from = listOf(atClass)
        val dbCtx = C_DbExprContext(null, ctx, from)

        val pathList = path.toList()
        path.clear()
        val what = compileDbPathExpr(dbCtx, pathList)

        val whereLeft = ClassDbExpr(atClass)
        val whereRight = ParameterDbExpr(atClass.type, 0)
        val where = BinaryDbExpr(RBooleanType, DbBinaryOp_Eq, whereLeft, whereRight)

        val atBase = RAtExprBase(from, listOf(what), where, listOf(), false, false)
        return C_PathMember_DataAttribute(what.type, atBase)
    }

    fun compilePathDb(ctx: C_DbExprContext, path: List<S_Name>): DbExpr {
        val head = path[0]
        val res = resolveNameDb(ctx, head)
        return compilePathDb0(ctx, path, head, res)
    }

    private fun compilePathDb0(ctx: C_DbExprContext, path: List<S_Name>, head: S_Name, res: C_DbNameResolution): DbExpr {
        if (res is C_DbNameResolution_Class) {
            val base = ClassDbExpr(res.cls)
            return compilePathDb(ctx, base, path.subList(1, path.size))
        } else if (res is C_DbNameResolution_Local) {
            val rBase = res.loc.toVarExpr()
            val rExpr = compilePath(ctx.exprCtx, rBase, path.subList(1, path.size))
            return InterpretedDbExpr(rExpr)
        } else if (res is C_DbNameResolution_Namespace && path.size >= 2) {
            val second = path[1]
            val rBase = res.ns.getValue(ctx.exprCtx.entCtx, head, second)
            val rExpr = compilePath(ctx.exprCtx, rBase, path.subList(2, path.size))
            return InterpretedDbExpr(rExpr)
        } else {
            throw C_Utils.errUnknownName(head)
        }
    }

    fun compilePathDb(ctx: C_DbExprContext, base: DbExpr, path: List<S_Name>): DbExpr {
        if (base is InterpretedDbExpr) {
            val rExpr = compilePath(ctx.exprCtx, base.expr, path)
            return InterpretedDbExpr(rExpr)
        }

        var res = base
        for (step in path) {
            res = compilePathStepDb(res, step)
        }
        return res
    }

    private fun compilePathStepDb(base: DbExpr, name: S_Name): DbExpr {
        if (base !is TableDbExpr) {
            throw C_Utils.errUnknownMember(base.type, name)
        }

        val attr = base.rClass.attributes[name.str]
        if (attr == null) {
            throw C_Utils.errUnknownMember(base.type, name)
        }

        return makeDbAttrExpr(base, attr)
    }

    fun compileCallStep(baseType: RType, name: S_Name, args: List<RExpr>): RMemberCalculator {
        val effectiveType = if (baseType is RNullableType) baseType.valueType else baseType

        val fn = S_LibFunctions.getMemberFunction(effectiveType, name)
        val calculator = fn.compileCall(name.pos, baseType, args)

        if (baseType is RNullableType) {
            throw C_Error(name.pos, "expr_call_null:${name.str}", "Cannot call function '${name.str}' on null")
        }

        return calculator
    }
}

private fun compileCallDbGlobal(name: S_Name, fn: C_Function, args: List<DbExpr>): DbExpr {
    val rArgs = args.filter { it is InterpretedDbExpr }.map { (it as InterpretedDbExpr).expr }
    if (rArgs.size == args.size) {
        val rExpr = fn.compileCall(name, rArgs)
        return InterpretedDbExpr(rExpr)
    } else {
        return fn.compileCallDb(name, args)
    }
}

fun compileDbPathExpr(ctx: C_DbExprContext, path: List<S_Name>): DbExpr {
    val head = path[0]
    val attr = resolveAttrDb(ctx, head)
    val base = makeDbAttrExpr(attr)
    return C_PathExprUtils.compilePathDb(ctx, base, path.subList(1, path.size))
}

private fun resolveAttrDb(ctx: C_DbExprContext, name: S_Name): DbClassAttr {
    val nameStr = name.str
    val attrs = ctx.findAttributesByName(nameStr)

    if (attrs.isEmpty()) {
        throw C_Utils.errUnknownAttr(name)
    } else if (attrs.size > 1) {
        throw C_Utils.errMutlipleAttrs(name.pos, attrs, "at_attr_name_ambig:$nameStr",
                "Multiple attributes with name '$nameStr'")
    }

    return attrs[0]
}

private fun makeDbAttrExpr(attr: DbClassAttr): DbExpr {
    val clsExpr = ClassDbExpr(attr.cls)
    val resultType = attr.attr.type
    val resultClass = if (resultType is RInstanceRefType) resultType.rClass else null
    return if (resultClass == null) AttrDbExpr(clsExpr, attr.attr) else RelDbExpr(clsExpr, attr.attr, resultClass)
}

private fun makeDbAttrExpr(baseExpr: TableDbExpr, attr: RAttrib): DbExpr {
    val resultType = attr.type
    val resultClass = if (resultType is RInstanceRefType) resultType.rClass else null
    return if (resultClass == null) AttrDbExpr(baseExpr, attr) else RelDbExpr(baseExpr, attr, resultClass)
}

private fun errBadPath(path: List<S_Name>, errOfs: Int): C_Error {
    val pathStr = path.subList(0, errOfs).joinToString(".") { it.str }
    return C_Error(path[0].pos, "bad_path_expr:$pathStr", "Inavlid path expression: '$pathStr'")
}

private fun resolveName(ctx: C_ExprContext, name: S_Name): C_NameResolution {
    val loc = ctx.lookupOpt(name.str)
    if (loc != null) return C_NameResolution_Local(loc)

    val ns = S_LibFunctions.getNamespace(ctx.entCtx.modCtx, name.str)
    if (ns != null) return C_NameResolution_Namespace(ns)

    throw C_Utils.errUnknownName(name)
}

private fun resolveNameDb(ctx: C_DbExprContext, name: S_Name): C_DbNameResolution {
    val nameStr = name.str

    val cls = ctx.findClassByAlias(nameStr)
    val loc = ctx.exprCtx.lookupOpt(nameStr)
    val ns = S_LibFunctions.getNamespace(ctx.exprCtx.entCtx.modCtx, nameStr)

    if (cls != null && loc != null) {
        throw C_Error(name.pos, "expr_name_clsloc:$nameStr",
                "Name '$nameStr' is ambiguous: can be class alias or local variable")
    }

    if (cls != null) return C_DbNameResolution_Class(cls)

    if (loc != null) return C_DbNameResolution_Local(loc)

    if (ns != null) return C_DbNameResolution_Namespace(ns)

    throw C_Utils.errUnknownName(name)
}

private sealed class C_NameResolution
private class C_NameResolution_Local(val loc: C_ScopeEntry): C_NameResolution()
private class C_NameResolution_Namespace(val ns: S_LibNamespace): C_NameResolution()

private sealed class C_DbNameResolution
private class C_DbNameResolution_Class(val cls: RAtClass): C_DbNameResolution()
private class C_DbNameResolution_Local(val loc: C_ScopeEntry): C_DbNameResolution()
private class C_DbNameResolution_Namespace(val ns: S_LibNamespace): C_DbNameResolution()

abstract class C_PathMember {
    abstract fun calculator(): RMemberCalculator
    abstract fun destination(pos: S_Pos, base: RExpr): RDestinationExpr
}

class C_PathMember_TupleField(val type: RType, val fieldIndex: Int): C_PathMember() {
    override fun calculator() = RMemberCalculator_TupleField(type, fieldIndex)
    override fun destination(pos: S_Pos, base: RExpr) = throw C_Utils.errBadDestination(pos)
}

class C_PathMember_RecordAttr(val attr: RAttrib): C_PathMember() {
    override fun calculator() = RMemberCalculator_RecordAttr(attr)

    override fun destination(pos: S_Pos, base: RExpr): RDestinationExpr {
        if (!attr.mutable) {
            throw C_Utils.errAttrNotMutable(pos, attr.name)
        }
        return RRecordMemberExpr(base, attr)
    }
}

class C_PathMember_DataAttribute(val type: RType, val atBase: RAtExprBase): C_PathMember() {
    override fun calculator() = RMemberCalculator_DataAttribute(type, atBase)
    override fun destination(pos: S_Pos, base: RExpr) = throw C_Utils.errBadDestination(pos)
}
