package net.postchain.rell.parser

import net.postchain.rell.model.*

internal typealias TypeMap = Map<String, RType>

class CtError(val code: String, msg: String): Exception(msg)

class S_NameType(val name: String, val type: String) {
    internal fun compile(ctx: ModuleCompilationContext): RAttrib {
        val rType = ctx.getType(type)
        return RAttrib(name, rType)
    }
}

internal class ModuleCompilationContext {
    private val typeMap = mutableMapOf<String, RType>(
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

    private val classes = mutableMapOf<String, RClass>()
    private val operations = mutableListOf<ROperation>()
    private val queries = mutableMapOf<String, RQuery>()

    fun typeExists(name: String): Boolean = name in typeMap
    fun classExists(name: String): Boolean = name in classes
    fun queryExists(name: String): Boolean = name in queries

    fun getType(name: String): RType {
        val type = typeMap[name]
        if (type == null) {
            throw CtError("unknown_type:$name", "Unknown type: $name")
        }
        return type
    }

    fun getClass(name: String): RClass {
        val  cls = classes[name]
        if (cls == null) {
            throw CtError("unknown_class:$name", "Unknown class: $name")
        }
        return cls
    }

    fun addClass(cls: RClass) {
        check(!(cls.name in typeMap))
        check(!(cls.name in classes))
        classes[cls.name] = cls
        typeMap[cls.name] = RInstanceRefType(cls)
    }

    fun addQuery(query: RQuery) {
        check(!(query.name in queries))
        queries[query.name] = query
    }

    fun createModule(): RModule {
        return RModule(classes.values.toList(), operations.toList(), queries.values.toList())
    }
}

internal class CompilationScopeEntry(val attr: RAttrib, val offset: Int) {
    fun toVarExpr(): RVarExpr = RVarExpr(attr, offset)
}

internal class ExprCompilationContext(val modCtx: ModuleCompilationContext, val parent: ExprCompilationContext?) {
    val startOffset: Int = if (parent == null) 0 else parent.startOffset + parent.locals.size
    val locals = mutableMapOf<String, CompilationScopeEntry>()

    fun add(rAttr: RAttrib): CompilationScopeEntry {
        val name = rAttr.name
        if (name in locals) {
            throw CtError("dup_var_name:$name", "Duplicated variable name: $name")
        }

        val ofs = startOffset + locals.size
        val entry = CompilationScopeEntry(rAttr, ofs)
        locals.put(name, entry)
        return entry
    }

    fun lookup(name: String): CompilationScopeEntry {
        val local = lookupOpt(name)
        if (local == null) {
            throw CtError("unknown_name:$name", "Unknown name: $name")
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

class S_AttrValue(val name: String, val expr: S_Expression)

abstract class S_Statement {
    internal abstract fun compile(ctx: ExprCompilationContext): RStatement
}

class S_ValStatement(val name: String, val expr: S_Expression): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        val entry = ctx.add(RAttrib(name, rExpr.type))
        return RValStatement(entry.offset, entry.attr, rExpr)
    }
}

class S_CreateStatement(val classname: String, val attrs: List<S_AttrValue>): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        TODO("not implemented")
    }
}

class S_UpdateStatement(val attrs: List<S_AttrValue>): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        TODO("not implemented")
    }
}

class S_DeleteStatement(): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        TODO("not implemented")
    }
}

class S_ReturnStatement(val expr: S_Expression): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        return RReturnStatement(rExpr)
    }
}

sealed class S_RelClause {
    internal abstract fun compile(ctx: ClassCompilationContext)
}

class S_AttributeClause(val attr: S_NameType): S_RelClause() {
    override fun compile(ctx: ClassCompilationContext) {
        ctx.addAttribute(attr.name, attr.type)
    }
}

class S_KeyClause(val attrs: List<S_NameType>): S_RelClause() {
    override fun compile(ctx: ClassCompilationContext) {
        for (attr in attrs) {
            ctx.addAttribute(attr.name, attr.type)
        }
        ctx.addKey(attrs.map { it.name })
    }
}

class S_IndexClause(val attrs: List<S_NameType>): S_RelClause() {
    override fun compile(ctx: ClassCompilationContext) {
        for (attr in attrs) {
            ctx.addAttribute(attr.name, attr.type)
        }
        ctx.addIndex(attrs.map { it.name })
    }
}

sealed class S_Definition(val name: String) {
    internal abstract fun compile(ctx: ModuleCompilationContext)
}

internal class ClassCompilationContext(private val ctx: ModuleCompilationContext) {
    private val attributes = mutableMapOf<String, RAttrib>()
    private val keys = mutableListOf<RKey>()
    private val indices = mutableListOf<RIndex>()

    fun addAttribute(name: String, type: String) {
        if (name in attributes) {
            throw CtError("dup_attr:$name", "Duplicated attribute name: $name")
        }

        val rType = ctx.getType(type)
        attributes[name] = RAttrib(name, rType)
    }

    fun addKey(attrs: List<String>) {
        keys.add(RKey(attrs))
    }

    fun addIndex(attrs: List<String>) {
        indices.add(RIndex(attrs))
    }

    fun createClass(name: String): RClass {
        return RClass(name, keys.toList(), indices.toList(), attributes.values.toList())
    }
}

class S_ClassDefinition(name: String, val clauses: List<S_RelClause>): S_Definition(name) {
    override fun compile(ctx: ModuleCompilationContext) {
        if (ctx.typeExists(name)) {
            throw CtError("dup_type_name:$name", "Duplicated type name: $name")
        }

        val clsCtx = ClassCompilationContext(ctx)
        for (clause in clauses) {
            clause.compile(clsCtx)
        }

        val rClass = clsCtx.createClass(name)
        ctx.addClass(rClass)
    }
}

class S_OpDefinition(
        name: String,
        val args: List<S_NameType>,
        val statements: List<S_Statement>
): S_Definition(name)
{
    override fun compile(ctx: ModuleCompilationContext) {
//        operations.add(makeROperation(def, typeMap))
        TODO("Not implemented")
    }
}

abstract class S_QueryBody {
    internal abstract fun compile(ctx: ExprCompilationContext): Array<RStatement>
}

class S_QueryBodyShort(val expr: S_Expression): S_QueryBody() {
    override fun compile(ctx: ExprCompilationContext): Array<RStatement> {
        val rExpr = expr.compile(ctx)
        val rStmt = RReturnStatement(rExpr)
        return arrayOf(rStmt)
    }
}

class S_QueryBodyFull(val statements: List<S_Statement>): S_QueryBody() {
    override fun compile(ctx: ExprCompilationContext): Array<RStatement> {
        return statements.map { it.compile(ctx) }.toTypedArray()
    }
}

class S_QueryDefinition(
        name: String,
        val args: List<S_NameType>,
        val body: S_QueryBody
): S_Definition(name) {
    override fun compile(ctx: ModuleCompilationContext) {
        if (ctx.queryExists(name)) {
            throw CtError("dup_query_name:$name", "Duplicated query name: $name")
        }

        val rArgs = compileParams(ctx, args)

        val exprCtx = ExprCompilationContext(ctx, null)
        val rParams = mutableListOf<RExternalParam>()
        for (rArg in rArgs) {
            val entry = exprCtx.add(rArg)
            rParams.add(RExternalParam(rArg.name, rArg.type, entry.offset))
        }

        val statements = body.compile(exprCtx)

        val rQuery = RQuery(name, rParams.toTypedArray(), statements)
        ctx.addQuery(rQuery)
    }
}

class S_ModuleDefinition(val definitions: List<S_Definition>) {
    fun compile(): RModule {
        val ctx = ModuleCompilationContext()

        for (def in definitions) {
            def.compile(ctx)
        }

        return ctx.createModule()
    }
}

private fun compileParams(ctx: ModuleCompilationContext, params: List<S_NameType>): List<RAttrib> {
    val names = mutableSetOf<String>()
    val res = mutableListOf<RAttrib>()
    for (param in params) {
        val name = param.name
        if (!names.add(name)) {
            throw CtError("dup_param_name:$name", "Duplicated parameter name: $name")
        }
        val rAttr = param.compile(ctx)
        res.add(rAttr)
    }
    return res
}
