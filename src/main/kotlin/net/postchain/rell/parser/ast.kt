package net.postchain.rell.parser

import net.postchain.rell.model.*

class CtError(val code: String, msg: String): Exception(msg)

class S_NameTypePair(val name: String, val type: S_Type)

class Ct_UserFunctionDeclaration(val name: String, val params: List<RExternalParam>, val type: RType)

sealed class Ct_Function {
    abstract fun compileCall(args: List<RExpr>): RExpr
    abstract fun compileCallDb(args: List<DbExpr>): DbExpr
}

class Ct_SysFunction(val fn: S_SysFunction): Ct_Function() {
    override fun compileCall(args: List<RExpr>): RExpr = fn.compileCall(args)
    override fun compileCallDb(args: List<DbExpr>): DbExpr = fn.compileCallDb(args)
}

class Ct_UserFunction(val fn: Ct_UserFunctionDeclaration, val fnKey: Int): Ct_Function() {
    override fun compileCall(args: List<RExpr>): RExpr {
        val params = fn.params.map { it.type }
        val args2 = S_SysFunction.matchArgs(fn.name, params, args)
        return RUserCallExpr(fn.type, fn.name, fnKey, args2)
    }

    override fun compileCallDb(args: List<DbExpr>): DbExpr = TODO()
}

internal class CtGlobalContext {
    private var frameBlockIdCtr = 0L

    fun nextFrameBlockId(): RFrameBlockId = RFrameBlockId(frameBlockIdCtr++)
}

internal class CtModuleContext(val globalCtx: CtGlobalContext) {
    private val typeMap = mutableMapOf<String, RType>(
            "boolean" to RBooleanType,
            "text" to RTextType,
            "byte_array" to RByteArrayType,
            "integer" to RIntegerType,
            "pubkey" to RByteArrayType,
            "name" to RTextType,
            "timestamp" to RIntegerType,
            "signer" to RSignerType,
            "guid" to RGUIDType,
            "tuid" to RTextType,
            "json" to RJSONType,
            "range" to RRangeType
    )

    private val classes = mutableMapOf<String, RClass>()
    private val operations = mutableMapOf<String, ROperation>()
    private val queries = mutableMapOf<String, RQuery>()
    private val functions = mutableMapOf<String, Ct_Function>()
    private val functionDecls = mutableListOf<Ct_UserFunctionDeclaration>()
    private val functionDefs = mutableListOf<RFunction>()

    private val secondPass = mutableListOf<() -> Unit>()

    init {
        for (fn in S_LibFunctions.getGlobalFunctions()) {
            check(!(fn.name in functions))
            functions[fn.name] = Ct_SysFunction(fn)
        }
    }

    fun typeExists(name: String): Boolean = name in typeMap
    fun functionExists(name: String): Boolean = name in functions || name in queries || name in operations

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

    fun getFunction(name: String): Ct_Function {
        val fn = functions[name]
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
        check(!functionExists(query.name))
        queries[query.name] = query
    }

    fun addOperation(operation: ROperation) {
        check(!functionExists(operation.name))
        operations[operation.name] = operation
    }

    fun addFunctionDeclaration(declaration: Ct_UserFunctionDeclaration): Int {
        check(!functionExists(declaration.name))
        val fnKey = functionDecls.size
        functionDecls.add(declaration)
        functions[declaration.name] = Ct_UserFunction(declaration, fnKey)
        return fnKey
    }

    fun addFunctionDefinition(function: RFunction) {
        check(functionDefs.size == function.fnKey)
        functionDefs.add(function)
    }

    fun onSecondPass(code: () -> Unit) {
        secondPass.add(code)
    }

    fun createModule(): RModule {
        return RModule(classes.values.toList(), operations.values.toList(), queries.values.toList(), functionDefs.toList())
    }

    fun runSecondPass() {
        for (code in secondPass) {
            code()
        }
    }
}

internal enum class CtEntityType {
    CLASS,
    QUERY,
    OPERATION,
    FUNCTION,
}

internal class CtEntityContext(
        val modCtx: CtModuleContext,
        val entityType: CtEntityType,
        returnType: RType?
){
    private var actualReturnType: RType? = returnType
    private var callFrameSize = 0

    val rootExprCtx = CtExprContext(this, null, false)

    fun checkDbUpdateAllowed() {
        if (entityType == CtEntityType.QUERY) {
            throw CtError("no_db_update", "Database modifications are not allowed in this context")
        }
    }

    fun matchReturnTypeUnit() {
        val retType = actualReturnType
        if (retType == null) {
            actualReturnType = RUnitType
        } else if (retType != RUnitType) {
            throw CtUtils.errTypeMissmatch(retType, RUnitType, "entity_rettype", "Return type missmatch")
        }
    }

    fun matchReturnType(expr: RExpr): RExpr {
        val retType = actualReturnType
        if (retType == null) {
            actualReturnType = expr.type
            return expr
        }
        return retType.match(expr, "entity_rettype", "Return type missmatch")
    }

    fun actualReturnType(): RType {
        var t = actualReturnType
        if (t == null) {
            t = RUnitType
            actualReturnType = t
        }
        return t
    }

    fun adjustCallFrameSize(size: Int) {
        check(size >= 0)
        callFrameSize = Math.max(callFrameSize, size)
    }

    fun makeCallFrame(): RCallFrame {
        val rootBlock = rootExprCtx.makeFrameBlock()
        return RCallFrame(callFrameSize, rootBlock)
    }
}

internal class CtScopeEntry(val name: String, val type: RType, val modifiable: Boolean, val ptr: RVarPtr) {
    fun toVarExpr(): RVarExpr = RVarExpr(type, ptr, name)
}

internal class CtExprContext(
        val entCtx: CtEntityContext,
        private val parent: CtExprContext?,
        val insideLoop: Boolean
){
    private val startOffset: Int = if (parent == null) 0 else parent.startOffset + parent.locals.size
    private val locals = mutableMapOf<String, CtScopeEntry0>()

    val blockId = entCtx.modCtx.globalCtx.nextFrameBlockId()

    fun add(name: String, type: RType, modifiable: Boolean): RVarPtr {
        if (lookupOpt(name) != null) {
            throw CtError("var_dupname:$name", "Duplicated variable name: $name")
        }

        val ofs = startOffset + locals.size
        entCtx.adjustCallFrameSize(ofs + 1)

        val entry = CtScopeEntry0(name, type, modifiable, ofs)
        locals.put(name, entry)

        return entry.toVarPtr(blockId)
    }

    fun lookup(name: String): CtScopeEntry {
        val local = lookupOpt(name)
        if (local == null) {
            throw CtError("unknown_name:$name", "Unknown name: $name")
        }
        return local
    }

    fun lookupOpt(name: String): CtScopeEntry? {
        var ctx: CtExprContext? = this
        while (ctx != null) {
            val local = ctx.locals[name]
            if (local != null) {
                return local.toScopeEntry(blockId)
            }
            ctx = ctx.parent
        }
        return null
    }

    fun makeFrameBlock(): RFrameBlock {
        val parentId = if (parent != null) parent.blockId else null
        return RFrameBlock(parentId, blockId, startOffset, locals.size)
    }

    companion object {
        private class CtScopeEntry0(val name: String, val type: RType, val modifiable: Boolean, val offset: Int) {
            fun toVarPtr(blockId: RFrameBlockId): RVarPtr = RVarPtr(blockId, offset)

            fun toScopeEntry(blockId: RFrameBlockId): CtScopeEntry {
                return CtScopeEntry(name, type, modifiable, toVarPtr(blockId))
            }
        }
    }
}

sealed class S_RelClause {
    internal abstract fun compile(ctx: CtClassContext)
}

class S_AttributeClause(val attr: S_NameTypePair, val mutable: Boolean, val expr: S_Expression?): S_RelClause() {
    override fun compile(ctx: CtClassContext) {
        ctx.addAttribute(attr.name, attr.type, mutable, expr)
    }
}

class S_KeyClause(val attrs: List<S_NameTypePair>): S_RelClause() {
    override fun compile(ctx: CtClassContext) {
        for (attr in attrs) {
            ctx.addAttribute(attr.name, attr.type, false, null)
        }
        ctx.addKey(attrs.map { it.name })
    }
}

class S_IndexClause(val attrs: List<S_NameTypePair>): S_RelClause() {
    override fun compile(ctx: CtClassContext) {
        for (attr in attrs) {
            ctx.addAttribute(attr.name, attr.type, false, null)
        }
        ctx.addIndex(attrs.map { it.name })
    }
}

sealed class S_Definition(val name: String) {
    internal abstract fun compile(ctx: CtModuleContext)
}

internal class CtClassContext(private val modCtx: CtModuleContext) {
    private val entCtx = CtEntityContext(modCtx, CtEntityType.CLASS, null)

    private val attributes = mutableMapOf<String, RAttrib>()
    private val keys = mutableListOf<RKey>()
    private val indices = mutableListOf<RIndex>()

    fun addAttribute(name: String, type: S_Type, mutable: Boolean, expr: S_Expression?) {
        if (name in attributes) {
            throw CtError("dup_attr:$name", "Duplicated attribute name: $name")
        }

        val rType = type.compile(modCtx)
        if (!rType.allowedForAttributes()) {
            throw CtError("class_attr_type:$name:${rType.toStrictString()}",
                    "Attribute '$name' has unallowed type: ${rType.toStrictString()}")
        }

        val rExpr = if (expr == null) null else {
            val rExpr = expr.compile(entCtx.rootExprCtx)
            rType.match(rExpr, "attr_type:$name", "Default value type missmatch for '$name'")
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
    override fun compile(ctx: CtModuleContext) {
        if (ctx.typeExists(name)) {
            throw CtError("dup_type_name:$name", "Duplicated type name: $name")
        }

        val clsCtx = CtClassContext(ctx)
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
    override fun compile(ctx: CtModuleContext) {
        if (ctx.functionExists(name)) {
            throw CtError("oper_name_dup:$name", "Duplicated function name: $name")
        }

        val entCtx = CtEntityContext(ctx, CtEntityType.OPERATION, null)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)
        val rBody = body.compile(entCtx.rootExprCtx)

        val rCallFrame = entCtx.makeCallFrame()
        val rOperation = ROperation(name, rParams, rBody, rCallFrame)
        ctx.addOperation(rOperation)
    }
}

class S_QueryDefinition(
        name: String,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition(name) {
    override fun compile(ctx: CtModuleContext) {
        if (ctx.functionExists(name)) {
            throw CtError("query_name_dup:$name", "Duplicated function name: $name")
        }

        val rRetType = retType?.compile(ctx)

        val entCtx = CtEntityContext(ctx, CtEntityType.QUERY, rRetType)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)
        val rBody = body.compileQuery(entCtx.rootExprCtx)

        val rCallFrame = entCtx.makeCallFrame()
        val rQuery = RQuery(name, rParams, rBody, rCallFrame)
        ctx.addQuery(rQuery)
    }
}

abstract class S_FunctionBody {
    internal abstract fun compileQuery(ctx: CtExprContext): RStatement
    internal abstract fun compileFunction(ctx: CtExprContext): RStatement
}

class S_FunctionBodyShort(val expr: S_Expression): S_FunctionBody() {
    override fun compileQuery(ctx: CtExprContext): RStatement {
        val rExpr = expr.compile(ctx)
        CtUtils.checkUnitType(rExpr.type, "query_exprtype_unit", "Query expressions returns nothing")
        val rExpr2 = ctx.entCtx.matchReturnType(rExpr)
        return RReturnStatement(rExpr2)
    }

    override fun compileFunction(ctx: CtExprContext): RStatement {
        val rExpr = expr.compile(ctx)
        val rExpr2 = ctx.entCtx.matchReturnType(rExpr)

        if (rExpr2.type == RUnitType) {
            val subCtx = CtExprContext(ctx.entCtx, ctx, ctx.insideLoop)
            val rBlock = subCtx.makeFrameBlock()
            return RBlockStatement(listOf(RExprStatement(rExpr2), RReturnStatement(null)), rBlock)
        } else {
            return RReturnStatement(rExpr2)
        }
    }
}

class S_FunctionBodyFull(val body: S_Statement): S_FunctionBody() {
    override fun compileQuery(ctx: CtExprContext): RStatement {
        val rBody = body.compile(ctx)

        val ret = body.returns()
        if (!ret) {
            throw CtError("query_noreturn", "Not all code paths return value")
        }

        return rBody
    }

    override fun compileFunction(ctx: CtExprContext): RStatement {
        val rBody = body.compile(ctx)

        val retType = ctx.entCtx.actualReturnType()
        if (retType != RUnitType) {
            val ret = body.returns()
            if (!ret) {
                throw CtError("fun_noreturn", "Not all code paths return value")
            }
        }

        return rBody
    }
}

class S_FunctionDefinition(
        name: String,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition(name) {
    override fun compile(ctx: CtModuleContext) {
        if (ctx.functionExists(name)) {
            throw CtError("fun_name_dup:$name", "Duplicated function name: $name")
        }

        val rRetType = if (retType != null) retType.compile(ctx) else RUnitType

        val entCtx = CtEntityContext(ctx, CtEntityType.FUNCTION, rRetType)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)

        val declaration = Ct_UserFunctionDeclaration(name, rParams, rRetType)
        val fnKey = ctx.addFunctionDeclaration(declaration)

        ctx.onSecondPass {
            secondPass(ctx, entCtx, declaration, fnKey)
        }
    }

    private fun secondPass(
            ctx: CtModuleContext,
            entCtx: CtEntityContext,
            declaration: Ct_UserFunctionDeclaration,
            fnKey: Int
    ){
        val rBody = body.compileFunction(entCtx.rootExprCtx)

        val rCallFrame = entCtx.makeCallFrame()
        val rFunction = RFunction(name, declaration.params, rBody, rCallFrame, declaration.type, fnKey)
        ctx.addFunctionDefinition(rFunction)
    }
}

class S_ModuleDefinition(val definitions: List<S_Definition>) {
    fun compile(): RModule {
        val globalCtx = CtGlobalContext()
        val ctx = CtModuleContext(globalCtx)

        for (def in definitions) {
            def.compile(ctx)
        }

        ctx.runSecondPass()

        return ctx.createModule()
    }
}

private fun compileExternalParams(
        ctx: CtModuleContext,
        exprCtx: CtExprContext,
        params: List<S_NameTypePair>
) : List<RExternalParam>
{
    val rParams = compileParams(ctx, params)

    val rExtParams = mutableListOf<RExternalParam>()
    for (rArg in rParams) {
        val ptr = exprCtx.add(rArg.name, rArg.type, false)
        rExtParams.add(RExternalParam(rArg.name, rArg.type, ptr))
    }

    return rExtParams.toList()
}

private fun compileParams(ctx: CtModuleContext, params: List<S_NameTypePair>): List<RVariable> {
    val names = mutableSetOf<String>()
    val res = mutableListOf<RVariable>()
    for (param in params) {
        val name = param.name
        if (!names.add(name)) {
            throw CtError("dup_param_name:$name", "Duplicated parameter name: $name")
        }
        val rType = param.type.compile(ctx)
        val rAttr = RVariable(param.name, rType)
        res.add(rAttr)
    }
    return res
}

object CtUtils {
    fun checkUnitType(type: RType, errCode: String, errMsg: String) {
        if (type == RUnitType) {
            throw CtError(errCode, errMsg)
        }
    }

    fun errTypeMissmatch(srcType: RType, dstType: RType, errCode: String, errMsg: String): CtError {
        return CtError("$errCode:${dstType.toStrictString()}:${srcType.toStrictString()}",
                "$errMsg: ${srcType.toStrictString()} instead of ${dstType.toStrictString()}")
    }
}
