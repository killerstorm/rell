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
        var ctx: ExprCompilationContext? = this
        while (ctx != null) {
            val local = ctx.locals[name]
            if (local != null) {
                return local
            }
            ctx = ctx.parent
        }
        throw IllegalStateException("Name not found: [${name}]")
    }
}

sealed class S_Expression {
    internal abstract fun compile(ctx: ExprCompilationContext): RExpr
}

class S_NameExpr(val name: String): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr {
        val entry = ctx.lookup(name)
        return RVarRef(entry.attr.type, entry.offset, entry.attr)
    }
}

class S_StringLiteral(val literal: String): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr = RStringLiteral(RTextType, literal)
}

class S_ByteALiteral(val bytes: ByteArray): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr = RByteALiteral(RByteArrayType, bytes)
}

class S_IntLiteral(val value: Long): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr = RIntegerLiteral(RIntegerType, value)
}

class S_AttributeExpr(val base: S_Expression, val name: String): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
}

class S_BinaryExprRight(val op: String, val expr: S_Expression)

class S_BinOp(val op: String, val left: S_Expression, val right: S_Expression): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class S_BinaryExpr(val left: S_Expression, val right: List<S_BinaryExprRight>): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
}

class S_UnaryExpr(val op: String, val expr: S_Expression): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
}

class S_SelectExpr(val className: String, val exprs: List<S_Expression>): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr {
        val cls = ctx.modCtx.classes[className]
        Preconditions.checkState(cls != null, "Unknown class: [%s]", className)

        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class S_CallExpr(val base: S_Expression, val args: List<S_Expression>): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class S_AttrExpr(val name: String, val expr: S_Expression)

sealed class S_BaseExprTail
class S_BaseExprTailAttribute(val name: String): S_BaseExprTail()
class S_BaseExprTailCall(val args: List<S_Expression>): S_BaseExprTail()

class S_BaseExpr(val head: S_Expression, val tail: List<S_BaseExprTail>): S_Expression() {
    internal override fun compile(ctx: ExprCompilationContext): RExpr = TODO("TODO")
}

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

class S_CreateStatement(val classname: String, val attrs: List<S_AttrExpr>): S_Statement() {
    internal override fun compile(ctx: ExprCompilationContext): RStatement {
        TODO("not implemented")
    }
}

class S_CallStatement(val expr: S_CallExpr): S_Statement() {
    internal override fun compile(ctx: ExprCompilationContext): RStatement {
        TODO("not implemented")
    }
}

class S_UpdateStatement(val attrs: List<S_AttrExpr>): S_Statement() {
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
