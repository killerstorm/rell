package net.postchain.rell.parser

import com.google.common.base.Preconditions
import net.postchain.rell.model.*

internal typealias EnvMap = Map<String, RAttrib>
internal typealias TypeMap = Map<String, RType>

class S_Attribute(val name: String, val type: String) {
    internal fun compile(types: TypeMap): RAttrib {
        val rType = types[type]
        Preconditions.checkState(rType != null, "Undefined type: [%s]", type)
        return RAttrib(name, rType!!)
    }
}

class S_Key(val attrNames: List<String>)
class S_Index(val attrNames: List<String>)

internal class ModuleCompilationContext {
    val typeMap = mutableMapOf<String, RType>(
            "text" to RTextType,
            "byte_array" to RByteArrayType,
            "integer" to RIntegerType,
            "pubkey" to RByteArrayType,
            "name" to RTextType,
            "timestamp" to RTimestampType,
            "signer" to RSignerType,
            "guid" to RGUIDType,
            "tuid" to RTextType,
            "json" to RJSONType,
            "retval json" to RJSONType,
            "retval require" to RUnitType
    )

    val classes = mutableMapOf<String, RClass>()
    val operations = mutableListOf<ROperation>()
    val queries = mutableMapOf<String, RQuery>()
}

internal class CompilationScopeEntry(val attr: RAttrib, val offset: Int)

internal class ExprCompilationContext(val modCtx: ModuleCompilationContext, val parent: ExprCompilationContext?) {
    val startOffset: Int = if (parent == null) 0 else parent.startOffset + parent.locals.size
    val locals = mutableMapOf<String, CompilationScopeEntry>()

    fun add(rAttr: RAttrib): CompilationScopeEntry {
        Preconditions.checkState(!(rAttr.name in locals), "Duplicated variable: [%s]", rAttr.name)
        val ofs = startOffset + locals.size
        val entry = CompilationScopeEntry(rAttr, ofs)
        locals.put(rAttr.name, entry)
        return entry
    }

    fun lookup(name: String): CompilationScopeEntry {
        val local = lookupOpt(name)
        if (local == null) {
            throw IllegalStateException("Name not found: [${name}]")
        }
        return local
    }

    fun lookupOpt(name: String): CompilationScopeEntry? {
        var ctx: ExprCompilationContext? = this
        while (ctx != null) {
            val local = ctx.locals[name]
            if (local != null) {
                return local
            }
            ctx = ctx.parent
        }
        return null
    }
}

internal class DbClassAttr(val cls: RSelectClass, val index: Int, val type: RType)

internal class DbCompilationContext(val exprCtx: ExprCompilationContext, val classes: List<RSelectClass>) {
    fun findAttributeClass(name: String): DbClassAttr? {
        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (cls in classes) {
            for (i in 0 until cls.rClass.attributes.size) {
                val attr = cls.rClass.attributes[i]
                if (attr.name == name) {
                    return DbClassAttr(cls, i, attr.type)
                }
            }
        }
        return null
    }
}

sealed class S_Expression {
    internal abstract fun compile(ctx: ExprCompilationContext): RExpr
    internal abstract fun compileDb(ctx: DbCompilationContext): DbExpr;

    internal fun delegateCompileDb(ctx: DbCompilationContext): DbExpr = InterpretedDbExpr(compile(ctx.exprCtx))
}

class S_NameExpr(val name: String): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val entry = ctx.lookup(name)
        return RVarRef(entry.attr.type, entry.offset, entry.attr)
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr {
        val clsAttr = ctx.findAttributeClass(name)
        val localAttr = ctx.exprCtx.lookupOpt(name)
        Preconditions.checkState(clsAttr == null || localAttr == null, "Ambiguous name: [%s]", name)
        Preconditions.checkState(clsAttr != null || localAttr != null, "Unknown name: [%s]", name)

        if (clsAttr != null) {
            val clsType = RInstanceRefType(clsAttr.cls.rClass.name, clsAttr.cls.rClass)
            val clsExpr = ClassDbExpr(clsType, clsAttr.cls)
            return AttributeDbExpr(clsAttr.type, clsExpr, clsAttr.index, name)
        } else {
            TODO("TODO")
        }
    }
}

class S_StringLiteral(val literal: String): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = RStringLiteral(RTextType, literal)
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)
}

class S_ByteALiteral(val bytes: ByteArray): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = RByteALiteral(RByteArrayType, bytes)
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)
}

class S_IntLiteral(val value: Long): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = RIntegerLiteral(RIntegerType, value)
    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)
}

sealed class S_BinaryOperator {
    abstract fun compile(left: RExpr, right: RExpr): RExpr
    abstract fun compileDb(left: DbExpr, right: DbExpr): DbExpr
}

sealed class S_BinaryOperatorEqNe(val dbOp: DbBinaryOp): S_BinaryOperator() {
    override fun compile(left: RExpr, right: RExpr): RExpr = TODO("TODO")
    override fun compileDb(left: DbExpr, right: DbExpr): DbExpr = BinaryDbExpr(RBooleanType, left, right, dbOp)
}

object S_BinaryOperatorEq: S_BinaryOperatorEqNe(DbBinaryOpEq)
object S_BinaryOperatorNe: S_BinaryOperatorEqNe(DbBinaryOpNe)

sealed class S_BinaryOperatorCmp: S_BinaryOperator() {
    override fun compile(left: RExpr, right: RExpr): RExpr = TODO("TODO")
    override fun compileDb(left: DbExpr, right: DbExpr): DbExpr = TODO("TODO")
}

object S_BinaryOperatorLt: S_BinaryOperatorCmp()
object S_BinaryOperatorGt: S_BinaryOperatorCmp()
object S_BinaryOperatorLe: S_BinaryOperatorCmp()
object S_BinaryOperatorGe: S_BinaryOperatorCmp()

sealed class S_BinaryOperatorArith: S_BinaryOperator() {
    override fun compile(left: RExpr, right: RExpr): RExpr = TODO("TODO")
    override fun compileDb(left: DbExpr, right: DbExpr): DbExpr = TODO("TODO")
}

object S_BinaryOperatorPlus: S_BinaryOperatorArith()
object S_BinaryOperatorMinus: S_BinaryOperatorArith()
object S_BinaryOperatorMul: S_BinaryOperatorArith()
object S_BinaryOperatorDiv: S_BinaryOperatorArith()
object S_BinaryOperatorMod: S_BinaryOperatorArith()

sealed class S_BinaryOperatorLogic: S_BinaryOperator() {
    override fun compile(left: RExpr, right: RExpr): RExpr = TODO("TODO")
    override fun compileDb(left: DbExpr, right: DbExpr): DbExpr = TODO("TODO")
}

object S_BinaryOperatorAnd: S_BinaryOperatorLogic()
object S_BinaryOperatorOr: S_BinaryOperatorLogic()

class S_BinaryExpr(val left: S_Expression, val right: S_Expression, val op: S_BinaryOperator): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")

    override fun compileDb(ctx: DbCompilationContext): DbExpr {
        val dbLeft = left.compileDb(ctx)
        val dbRight = right.compileDb(ctx)

        //TODO don't use "is"
        if (dbLeft is InterpretedDbExpr && dbRight is InterpretedDbExpr) {
            val rExpr = op.compile(dbLeft.expr, dbRight.expr)
            return InterpretedDbExpr(rExpr)
        } else {
            return op.compileDb(dbLeft, dbRight)
        }
    }
}

class S_UnaryExpr(val op: String, val expr: S_Expression): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
    override fun compileDb(ctx: DbCompilationContext): DbExpr = TODO("TODO")
}

class S_SelectExpr(val className: String, val exprs: List<S_Expression>): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val cls = ctx.modCtx.classes[className]
        if (cls == null) {
            throw IllegalStateException("Unknown class: [$className]")
        }

        val selClass = RSelectClass(cls, 0)
        val classes = listOf(selClass)
        val dbCtx = DbCompilationContext(ctx, classes)
        val compiledExprs = exprs.map { it.compileDb(dbCtx) }
        val where = makeWhere(compiledExprs)

        val resType = RInstanceRefType(cls.name, cls)
        return RSelectExpr(resType, selClass, where)
    }

    private fun makeWhere(compiledExprs: List<DbExpr>): DbExpr {
        val dbExprs = compiledExprs.filter { !(it is InterpretedDbExpr) }
        val rExprs = compiledExprs.filter { it is InterpretedDbExpr }.map { (it as InterpretedDbExpr).expr }

        val dbTree = exprsToTree(dbExprs)
        val rTree = exprsToTree(rExprs)

        if (dbTree != null && rTree != null) {
            return BinaryDbExpr(RBooleanType, dbTree, InterpretedDbExpr(rTree), DbBinaryOpAnd)
        } else if (dbTree != null) {
            return dbTree
        } else if (rTree != null) {
            return InterpretedDbExpr(rTree)
        } else {
            throw IllegalStateException("Impossible")
        }
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr = TODO("TODO")

    private fun exprsToTree(exprs: List<DbExpr>): DbExpr? {
        if (exprs.isEmpty()) {
            return null
        }

        var left = exprs[0]
        for (right in exprs.subList(1, exprs.size)) {
            left = BinaryDbExpr(RBooleanType, left, right, DbBinaryOpAnd)
        }
        return left
    }

    private fun exprsToTree(exprs: List<RExpr>): RExpr? {
        if (exprs.isEmpty()) {
            return null
        }

        var left = exprs[0]
        for (right in exprs.subList(1, exprs.size)) {
            TODO("TODO")
        }
        return left
    }
}

class S_AttributeExpr(val base: S_Expression, val name: String): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
    override fun compileDb(ctx: DbCompilationContext): DbExpr = TODO("TODO")
}

class S_CallExpr(val base: S_Expression, val args: List<S_Expression>): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
    override fun compileDb(ctx: DbCompilationContext): DbExpr = TODO("TODO")
}

class S_AttrValue(val name: String, val expr: S_Expression)

abstract class S_Statement {
    internal abstract fun compile(ctx: ExprCompilationContext): RStatement
}

class S_ValStatement(val name: String, val expr: S_Expression): S_Statement() {
    internal override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        val entry = ctx.add(RAttrib(name, rExpr.type))
        return RValStatement(entry.offset, entry.attr, rExpr)
    }
}

class S_CreateStatement(val classname: String, val attrs: List<S_AttrValue>): S_Statement() {
    internal override fun compile(ctx: ExprCompilationContext): RStatement {
        TODO("not implemented")
    }
}

class S_UpdateStatement(val attrs: List<S_AttrValue>): S_Statement() {
    internal override fun compile(ctx: ExprCompilationContext): RStatement {
        TODO("not implemented")
    }
}

class S_DeleteStatement(): S_Statement() {
    internal override fun compile(ctx: ExprCompilationContext): RStatement {
        TODO("not implemented")
    }
}

class S_ReturnStatement(val expr: S_Expression): S_Statement() {
    internal override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        return RReturnStatement(rExpr)
    }
}

sealed class S_RelClause
class S_AttributeClause(val attr: S_Attribute): S_RelClause()
class S_KeyClause(val attrs: List<S_Attribute>): S_RelClause()
class S_IndexClause(val attrs: List<S_Attribute>): S_RelClause()

sealed class S_Definition(val identifier: String) {
    internal abstract fun compile(ctx: ModuleCompilationContext)
}

sealed class S_RelDefinition (
        identifier: String,
        val attributes: List<S_Attribute>,
        val keys: List<S_Key>,
        val indices: List<S_Index>
): S_Definition(identifier)

class S_ClassDefinition(
        identifier: String,
        attributes: List<S_Attribute>,
        keys: List<S_Key>,
        indices: List<S_Index>
) : S_RelDefinition(identifier, attributes, keys, indices)
{
    internal override fun compile(ctx: ModuleCompilationContext) {
        Preconditions.checkState(!(identifier in ctx.typeMap), "Duplicated type name: [%s]", identifier)

        val rAttrs = compileAttrs(ctx.typeMap, attributes)
        val rKeys = keys.map { RKey(it.attrNames.toTypedArray()) }
        val rIndexes = indices.map { RIndex(it.attrNames.toTypedArray()) }
        val rClass = RClass(identifier, rKeys.toTypedArray(), rIndexes.toTypedArray(), rAttrs.toTypedArray())

        ctx.classes[rClass.name] = rClass
        ctx.typeMap[rClass.name] = RInstanceRefType(rClass.name, rClass)
    }
}

class S_OpDefinition(
        identifier: String,
        val args: List<S_Attribute>,
        val statements: List<S_Statement>
): S_Definition(identifier)
{
    internal override fun compile(ctx: ModuleCompilationContext) {
//        operations.add(makeROperation(def, typeMap))
        TODO("Not implemented")
    }
}

abstract class S_QueryBody {
    internal abstract fun compile(ctx: ExprCompilationContext): Array<RStatement>
}

class S_QueryBodyShort(val expr: S_Expression): S_QueryBody() {
    internal override fun compile(ctx: ExprCompilationContext): Array<RStatement> {
        val rExpr = expr.compile(ctx)
        val rStmt = RReturnStatement(rExpr)
        return arrayOf(rStmt)
    }
}

class S_QueryBodyFull(val statements: List<S_Statement>): S_QueryBody() {
    internal override fun compile(ctx: ExprCompilationContext): Array<RStatement> {
        return statements.map { it.compile(ctx) }.toTypedArray()
    }
}

class S_QueryDefinition(
        identifier: String,
        val args: List<S_Attribute>,
        val body: S_QueryBody
): S_Definition(identifier) {
    internal override fun compile(ctx: ModuleCompilationContext) {
        Preconditions.checkState(!ctx.queries.containsKey(identifier), "Duplicated query name: [%s]", identifier)

        val rArgs = compileAttrs(ctx.typeMap, args)

        val exprCtx = ExprCompilationContext(ctx, null)
        val rParams = mutableListOf<Int>()
        for (rArg in rArgs) {
            val entry = exprCtx.add(rArg)
            rParams.add(entry.offset)
        }

        val statements = body.compile(exprCtx)

        val rQuery = RQuery(identifier, rParams.toTypedArray(), statements)
        ctx.queries.put(identifier, rQuery)
    }
}

class S_ModuleDefinition(val definitions: List<S_Definition>) {
    fun compile(): RModule {
        val ctx = ModuleCompilationContext()

        for (def in definitions) {
            def.compile(ctx)
        }

        return RModule(ctx.classes.values.toTypedArray(), ctx.operations.toTypedArray(), ctx.queries.values.toTypedArray())
    }
}

private fun compileAttrs(types: TypeMap, args: List<S_Attribute>): List<RAttrib> {
    val names = mutableSetOf<String>()
    val res = mutableListOf<RAttrib>()
    for (arg in args) {
        Preconditions.checkState(names.add(arg.name), "Duplicated parameter: [%s]", arg.name)
        val rAttr = arg.compile(types)
        res.add(rAttr)
    }
    return res
}
