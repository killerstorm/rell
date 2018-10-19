package net.postchain.rell.parser

import net.postchain.rell.model.*

internal typealias TypeMap = Map<String, RType>

class CtError(val code: String, msg: String): Exception(msg)

class S_NameTypePair(val name: String, val type: String)

internal class ModuleCompilationContext {
    private val typeMap = mutableMapOf<String, RType>(
            "boolean" to RBooleanType,
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

    private val functionMap = mutableMapOf<String, S_SysFunction>()

    private val classes = mutableMapOf<String, RClass>()
    private val operations = mutableMapOf<String, ROperation>()
    private val queries = mutableMapOf<String, RQuery>()

    init {
        for (fn in S_SYS_FUNCTIONS) {
            check(!(fn.name in functionMap))
            functionMap[fn.name] = fn
        }
    }

    fun typeExists(name: String): Boolean = name in typeMap
    fun functionExists(name: String): Boolean = name in queries || name in operations

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

    fun getFunction(name: String): S_SysFunction {
        val fn = functionMap[name]
        if (fn == null) {
            throw CtError("unknown_function:$name", "Unknown function: '$name'")
        }
        return fn
    }

    fun addClass(cls: RClass) {
        check(!(cls.name in typeMap))
        check(!(cls.name in classes))
        classes[cls.name] = cls
        typeMap[cls.name] = RInstanceRefType(cls)
    }

    fun addQuery(query: RQuery) {
        check(!(query.name in queries))
        check(!(query.name in operations))
        queries[query.name] = query
    }

    fun addOperation(operation: ROperation) {
        check(!(operation.name in queries))
        check(!(operation.name in operations))
        operations[operation.name] = operation
    }

    fun createModule(): RModule {
        return RModule(classes.values.toList(), operations.values.toList(), queries.values.toList())
    }
}

internal class CompilationScopeEntry(val type: RType, val modifiable: Boolean, val offset: Int) {
    fun toVarExpr(): RVarExpr = RVarExpr(type, offset)
}

internal class ExprCompilationContext(
        val modCtx: ModuleCompilationContext,
        private val parent: ExprCompilationContext?,
        val dbUpdateAllowed: Boolean,
        val insideLoop: Boolean
){
    val startOffset: Int = if (parent == null) 0 else parent.startOffset + parent.locals.size
    val locals = mutableMapOf<String, CompilationScopeEntry>()

    fun add(name: String, type: RType, modifiable: Boolean): CompilationScopeEntry {
        if (name in locals) {
            throw CtError("var_dupname:$name", "Duplicated variable name: $name")
        }

        val ofs = startOffset + locals.size
        val entry = CompilationScopeEntry(type, modifiable, ofs)
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

    fun checkDbUpdateAllowed() {
        if (!dbUpdateAllowed) {
            throw CtError("no_db_update", "Database modifications are not allowed in this context")
        }
    }
}

sealed class S_RelClause {
    internal abstract fun compile(ctx: ClassCompilationContext)
}

class S_AttributeClause(val attr: S_NameTypePair, val mutable: Boolean, val expr: S_Expression?): S_RelClause() {
    override fun compile(ctx: ClassCompilationContext) {
        ctx.addAttribute(attr.name, attr.type, mutable, expr)
    }
}

class S_KeyClause(val attrs: List<S_NameTypePair>): S_RelClause() {
    override fun compile(ctx: ClassCompilationContext) {
        for (attr in attrs) {
            ctx.addAttribute(attr.name, attr.type, false, null)
        }
        ctx.addKey(attrs.map { it.name })
    }
}

class S_IndexClause(val attrs: List<S_NameTypePair>): S_RelClause() {
    override fun compile(ctx: ClassCompilationContext) {
        for (attr in attrs) {
            ctx.addAttribute(attr.name, attr.type, false, null)
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

    fun addAttribute(name: String, type: String, mutable: Boolean, expr: S_Expression?) {
        if (name in attributes) {
            throw CtError("dup_attr:$name", "Duplicated attribute name: $name")
        }

        val rType = ctx.getType(type)

        val rExpr = if (expr == null) null else {
            val exprCtx = ExprCompilationContext(ctx, null, false, false)
            val rExpr = expr.compile(exprCtx)
            if (!rType.accepts(rExpr.type)) {
                throw CtError("attr_type:$name:${rType.toStrictString()}:${rExpr.type.toStrictString()}",
                        "Default value type missmatch for '$name': ${rExpr.type.toStrictString()} instead of ${rType.toStrictString()}")
            }
            rExpr
        }

        attributes[name] = RAttrib(name, rType, mutable, rExpr)
    }

    fun addKey(attrs: List<String>) {
        keys.add(RKey(attrs))
    }

    fun addIndex(attrs: List<String>) {
        indices.add(RIndex(attrs))
    }

    fun createClass(name: String): RClass {
        return RClass(name, keys.toList(), indices.toList(), attributes.toMap())
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
        val params: List<S_NameTypePair>,
        val body: S_Statement
): S_Definition(name)
{
    override fun compile(ctx: ModuleCompilationContext) {
        if (ctx.functionExists(name)) {
            throw CtError("dup_operation_name:$name", "Duplicated operation name: $name")
        }

        val exprCtx = ExprCompilationContext(ctx, null, true, false)
        val rParams = compileExternalParams(ctx, exprCtx, params)
        val rBody = body.compile(exprCtx)

        val rOperation = ROperation(name, rParams, rBody)
        ctx.addOperation(rOperation)
    }
}

abstract class S_QueryBody {
    internal abstract fun compile(ctx: ExprCompilationContext): RStatement
}

class S_QueryBodyShort(val expr: S_Expression): S_QueryBody() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        return RReturnStatement(rExpr)
    }
}

class S_QueryBodyFull(val body: S_Statement): S_QueryBody() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        return body.compile(ctx)
    }
}

class S_QueryDefinition(
        name: String,
        val params: List<S_NameTypePair>,
        val body: S_QueryBody
): S_Definition(name) {
    override fun compile(ctx: ModuleCompilationContext) {
        if (ctx.functionExists(name)) {
            throw CtError("dup_query_name:$name", "Duplicated query name: $name")
        }

        val exprCtx = ExprCompilationContext(ctx, null, false, false)
        val rParams = compileExternalParams(ctx, exprCtx, params)
        val rBody = body.compile(exprCtx)

        val rQuery = RQuery(name, rParams, rBody)
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

private fun compileExternalParams(
        ctx: ModuleCompilationContext,
        exprCtx: ExprCompilationContext,
        params: List<S_NameTypePair>
) : List<RExternalParam>
{
    val rParams = compileParams(ctx, params)

    val rExtParams = mutableListOf<RExternalParam>()
    for (rArg in rParams) {
        val entry = exprCtx.add(rArg.name, rArg.type, false)
        rExtParams.add(RExternalParam(rArg.name, rArg.type, entry.offset))
    }

    return rExtParams.toList()
}

private fun compileParams(ctx: ModuleCompilationContext, params: List<S_NameTypePair>): List<RVariable> {
    val names = mutableSetOf<String>()
    val res = mutableListOf<RVariable>()
    for (param in params) {
        val name = param.name
        if (!names.add(name)) {
            throw CtError("dup_param_name:$name", "Duplicated parameter name: $name")
        }
        val rType = ctx.getType(param.type)
        val rAttr = RVariable(param.name, rType)
        res.add(rAttr)
    }
    return res
}
