package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.lexer.TokenMatch
import com.github.h0tk3y.betterParse.parser.ParseException
import com.github.h0tk3y.betterParse.parser.parseToEnd
import net.postchain.rell.model.*

class CtError(val pos: S_Pos, val code: String, val errMsg: String): Exception("$pos $errMsg")

class S_Pos(val row: Int, val col: Int) {
    constructor(t: TokenMatch): this(t.row, t.column)

    override fun toString() = "($row:$col)"
}

class S_Node<T>(val pos: S_Pos, val value: T) {
    constructor(t: TokenMatch, value: T): this(S_Pos(t), value)
}

class S_Name(val pos: S_Pos, val str: String) {
    override fun toString() = str
}

class S_NameTypePair(val name: S_Name, val type: S_Type?) {
    internal fun compileType(ctx: CtModuleContext): RType {
        if (type != null) {
            return type.compile(ctx)
        }

        val rType = ctx.getTypeOpt(name.str)
        if (rType == null) {
            throw CtError(name.pos, "unknown_name_type:${name.str}",
                    "Type for '${name.str}' not specified and no type called '${name.str}'")
        }

        return rType
    }
}

class Ct_UserFunctionDeclaration(val name: String, val params: List<RExternalParam>, val type: RType)

sealed class Ct_Function {
    abstract fun compileCall(name: S_Name, args: List<RExpr>): RExpr
    abstract fun compileCallDb(name: S_Name, args: List<DbExpr>): DbExpr
}

class Ct_SysFunction(val fn: S_SysFunction): Ct_Function() {
    override fun compileCall(name: S_Name, args: List<RExpr>): RExpr = fn.compileCall(name.pos, args)
    override fun compileCallDb(name: S_Name, args: List<DbExpr>): DbExpr = fn.compileCallDb(name.pos, args)
}

class Ct_UserFunction(val fn: Ct_UserFunctionDeclaration, val fnKey: Int): Ct_Function() {
    override fun compileCall(name: S_Name, args: List<RExpr>): RExpr {
        val params = fn.params.map { it.type }
        S_SysFunction.matchArgs(name, params, args)
        return RUserCallExpr(fn.type, fn.name, fnKey, args)
    }

    override fun compileCallDb(name: S_Name, args: List<DbExpr>): DbExpr {
        throw CtError(name.pos, "call_userfn_nosql:${name.str}", "Cannot call function '${name.str}' in SQL")
    }
}

internal class CtGlobalContext {
    private var frameBlockIdCtr = 0L

    fun nextFrameBlockId(): RFrameBlockId = RFrameBlockId(frameBlockIdCtr++)
}

internal class CtModuleContext(val globalCtx: CtGlobalContext) {
    private val typeMap = mutableMapOf(
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
            check(fn.name !in functions)
            functions[fn.name] = Ct_SysFunction(fn)
        }
    }

    fun typeExists(name: String): Boolean = name in typeMap
    fun functionExists(name: String): Boolean = name in functions || name in queries || name in operations

    fun getType(name: S_Name): RType {
        val nameStr = name.str
        val type = getTypeOpt(nameStr)
        if (type == null) {
            throw CtError(name.pos, "unknown_type:$nameStr", "Unknown type: '$nameStr'")
        }
        return type
    }

    fun getTypeOpt(name: String): RType? = typeMap[name]

    fun getClass(name: S_Name): RClass {
        val nameStr = name.str
        val  cls = classes[nameStr]
        if (cls == null) {
            throw CtError(name.pos, "unknown_class:$nameStr", "Unknown class: '$nameStr'")
        }
        return cls
    }

    fun getFunction(name: S_Name): Ct_Function {
        val nameStr = name.str
        val fn = functions[nameStr]
        if (fn == null) {
            throw CtUtils.errUnknownFunction(name)
        }
        return fn
    }

    fun addClass(cls: RClass) {
        check(cls.name !in typeMap)
        check(cls.name !in classes)
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
        return RModule(classes.toMap(), operations.toMap(), queries.toMap(), functionDefs.toList())
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
        explicitReturnType: RType?
){
    private val retTypeTracker =
            if (explicitReturnType != null) RetTypeTracker.Explicit(explicitReturnType) else RetTypeTracker.Implicit()

    private var callFrameSize = 0

    val rootExprCtx = CtExprContext(this, null, false)

    fun checkDbUpdateAllowed(pos: S_Pos) {
        if (entityType == CtEntityType.QUERY) {
            throw CtError(pos, "no_db_update", "Database modifications are not allowed in this context")
        }
    }

    fun matchReturnType(pos: S_Pos, type: RType) {
        retTypeTracker.match(pos, type)
    }

    fun actualReturnType(): RType = retTypeTracker.getRetType()

    fun adjustCallFrameSize(size: Int) {
        check(size >= 0)
        callFrameSize = Math.max(callFrameSize, size)
    }

    fun makeCallFrame(): RCallFrame {
        val rootBlock = rootExprCtx.makeFrameBlock()
        return RCallFrame(callFrameSize, rootBlock)
    }

    private sealed class RetTypeTracker {
        abstract fun getRetType(): RType
        abstract fun match(pos: S_Pos, type: RType)

        class Implicit: RetTypeTracker() {
            private var impType: RType? = null

            override fun getRetType(): RType {
                val t = impType
                if (t != null) return t
                val res = RUnitType
                impType = res
                return res
            }

            override fun match(pos: S_Pos, type: RType) {
                val t = impType
                if (t == null) {
                    impType = type
                } else if (t == RUnitType) {
                    if (type != RUnitType) {
                        throw errRetTypeMiss(pos, t, type)
                    }
                } else {
                    val comType = RType.commonTypeOpt(t, type)
                    if (comType == null) {
                        throw errRetTypeMiss(pos, t, type)
                    }
                    impType = comType
                }
            }
        }

        class Explicit(val expType: RType): RetTypeTracker() {
            override fun getRetType() = expType

            override fun match(pos: S_Pos, type: RType) {
                val m = if (expType == RUnitType) type == RUnitType else expType.isAssignableFrom(type)
                if (!m) {
                    throw errRetTypeMiss(pos, expType, type)
                }
            }
        }
    }

    companion object {
        private fun errRetTypeMiss(pos: S_Pos, dstType: RType, srcType: RType): CtError =
                CtUtils.errTypeMissmatch(pos, srcType, dstType, "entity_rettype", "Return type missmatch")
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

    fun add(name: S_Name, type: RType, modifiable: Boolean): RVarPtr {
        val nameStr = name.str
        if (lookupOpt(nameStr) != null) {
            throw CtError(name.pos, "var_dupname:$nameStr", "Duplicate variable: '$nameStr'")
        }

        val ofs = startOffset + locals.size
        entCtx.adjustCallFrameSize(ofs + 1)

        val entry = CtScopeEntry0(nameStr, type, modifiable, ofs)
        locals.put(nameStr, entry)

        return entry.toVarPtr(blockId)
    }

    fun lookup(name: S_Name): CtScopeEntry {
        val nameStr = name.str
        val local = lookupOpt(nameStr)
        if (local == null) {
            throw CtError(name.pos, "unknown_name:$nameStr", "Unknown name: '$nameStr'")
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
    internal abstract fun compileAttributes(ctx: CtClassContext)
    internal abstract fun compileRest(ctx: CtClassContext)
}

class S_AttributeClause(val attr: S_NameTypePair, val mutable: Boolean, val expr: S_Expression?): S_RelClause() {
    override fun compileAttributes(ctx: CtClassContext) {
        ctx.addAttribute(attr, mutable, expr)
    }

    override fun compileRest(ctx: CtClassContext) {}
}

sealed class S_KeyIndexClause(val pos: S_Pos, val attrs: List<S_NameTypePair>): S_RelClause() {
    final override fun compileAttributes(ctx: CtClassContext) {}

    internal abstract fun addToContext(ctx: CtClassContext, pos: S_Pos, names: List<S_Name>)

    final override fun compileRest(ctx: CtClassContext) {
        val names = mutableSetOf<String>()
        for (attr in attrs) {
            if (!names.add(attr.name.str)) {
                throw CtError(attr.name.pos, "class_keyindex_dup:${attr.name.str}",
                        "Duplicate attribute: '${attr.name.str}'")
            }
        }

        for (attr in attrs) {
            if (ctx.hasAttribute(attr.name.str)) {
                if (attr.type != null) {
                    throw CtError(attr.name.pos, "class_keyindex_def:${attr.name.str}",
                            "Attribute '${attr.name.str}' is defined elsewhere, cannot specify type")
                }
            } else {
                ctx.addAttribute(attr, false, null)
            }
        }

        addToContext(ctx, pos, attrs.map { it.name })
    }
}

class S_KeyClause(pos: S_Pos, attrs: List<S_NameTypePair>): S_KeyIndexClause(pos, attrs) {
    override fun addToContext(ctx: CtClassContext, pos: S_Pos, names: List<S_Name>) {
        ctx.addKey(pos, names)
    }
}

class S_IndexClause(pos: S_Pos, attrs: List<S_NameTypePair>): S_KeyIndexClause(pos, attrs) {
    override fun addToContext(ctx: CtClassContext, pos: S_Pos, names: List<S_Name>) {
        ctx.addIndex(pos, names)
    }
}

sealed class S_Definition(val name: S_Name) {
    internal abstract fun compile(ctx: CtModuleContext)
}

internal class CtClassContext(private val modCtx: CtModuleContext) {
    private val entCtx = CtEntityContext(modCtx, CtEntityType.CLASS, null)

    private val attributes = mutableMapOf<String, RAttrib>()
    private val keys = mutableListOf<RKey>()
    private val indices = mutableListOf<RIndex>()
    private val uniqueKeys = mutableSetOf<Set<String>>()
    private val uniqueIndices = mutableSetOf<Set<String>>()

    fun hasAttribute(name: String): Boolean = name in attributes

    fun addAttribute(attr: S_NameTypePair, mutable: Boolean, expr: S_Expression?) {
        val name = attr.name

        val nameStr = name.str
        if (nameStr in attributes) {
            throw CtError(name.pos, "dup_attr:$nameStr", "Duplicate attribute: '$nameStr'")
        }

        val rType = attr.compileType(modCtx)
        if (!rType.allowedForAttributes()) {
            throw CtError(name.pos, "class_attr_type:$nameStr:${rType.toStrictString()}",
                    "Attribute '$nameStr' has unallowed type: ${rType.toStrictString()}")
        }

        val rExpr = expr?.compile(entCtx.rootExprCtx)
        if (rExpr != null) {
            S_Type.match(rType, rExpr.type, name.pos, "attr_type:$nameStr", "Default value type missmatch for '$nameStr'")
        }

        attributes[nameStr] = RAttrib(nameStr, rType, mutable, rExpr)
    }

    fun addKey(pos: S_Pos, attrs: List<S_Name>) {
        val names = attrs.map { it.str }
        addUniqueKeyIndex(pos, uniqueKeys, names, "class_key_dup", "Duplicate key")
        keys.add(RKey(names))
    }

    fun addIndex(pos: S_Pos, attrs: List<S_Name>) {
        val names = attrs.map { it.str }
        addUniqueKeyIndex(pos, uniqueIndices, names, "class_index_dup", "Duplicate index")
        indices.add(RIndex(names))
    }

    fun createClass(name: String): RClass {
        return RClass(name, keys.toList(), indices.toList(), attributes.toMap())
    }

    private fun addUniqueKeyIndex(pos: S_Pos, set: MutableSet<Set<String>>, names: List<String>, errCode: String, errMsg: String) {
        val nameSet = names.toSet()
        if (!set.add(nameSet)) {
            val nameLst = names.sorted()
            throw CtError(pos, "$errCode:${nameLst.joinToString(",")}", "$errMsg: ${nameLst.joinToString()}")
        }
    }
}

class S_ClassDefinition(name: S_Name, val clauses: List<S_RelClause>): S_Definition(name) {
    override fun compile(ctx: CtModuleContext) {
        val nameStr = name.str
        if (ctx.typeExists(nameStr)) {
            throw CtError(name.pos, "dup_type_name:$nameStr", "Duplicate type: '$nameStr'")
        }

        val clsCtx = CtClassContext(ctx)
        for (clause in clauses) {
            clause.compileAttributes(clsCtx)
        }
        for (clause in clauses) {
            clause.compileRest(clsCtx)
        }

        val rClass = clsCtx.createClass(nameStr)
        ctx.addClass(rClass)
    }
}

class S_OpDefinition(
        name: S_Name,
        val params: List<S_NameTypePair>,
        val body: S_Statement
): S_Definition(name)
{
    override fun compile(ctx: CtModuleContext) {
        val nameStr = name.str
        if (ctx.functionExists(nameStr)) {
            throw CtError(name.pos, "oper_name_dup:$nameStr", "Duplicate function: '$nameStr'")
        }

        val entCtx = CtEntityContext(ctx, CtEntityType.OPERATION, null)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)
        val rBody = body.compile(entCtx.rootExprCtx)

        val rCallFrame = entCtx.makeCallFrame()
        val rOperation = ROperation(nameStr, rParams, rBody, rCallFrame)
        ctx.addOperation(rOperation)
    }
}

class S_QueryDefinition(
        name: S_Name,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition(name) {
    override fun compile(ctx: CtModuleContext) {
        val nameStr = name.str
        if (ctx.functionExists(nameStr)) {
            throw CtError(name.pos, "query_name_dup:$nameStr", "Duplicate function: '$nameStr'")
        }

        val rRetType = retType?.compile(ctx)

        val entCtx = CtEntityContext(ctx, CtEntityType.QUERY, rRetType)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)
        val rBody = body.compileQuery(name, entCtx.rootExprCtx)

        val rCallFrame = entCtx.makeCallFrame()
        val rQuery = RQuery(nameStr, entCtx.actualReturnType(), rParams, rBody, rCallFrame)
        ctx.addQuery(rQuery)
    }
}

abstract class S_FunctionBody {
    internal abstract fun compileQuery(name: S_Name, ctx: CtExprContext): RStatement
    internal abstract fun compileFunction(name: S_Name, ctx: CtExprContext): RStatement
}

class S_FunctionBodyShort(val expr: S_Expression): S_FunctionBody() {
    override fun compileQuery(name: S_Name, ctx: CtExprContext): RStatement {
        val rExpr = expr.compile(ctx)
        CtUtils.checkUnitType(name.pos, rExpr.type, "query_exprtype_unit", "Query expressions returns nothing")
        ctx.entCtx.matchReturnType(name.pos, rExpr.type)
        return RReturnStatement(rExpr)
    }

    override fun compileFunction(name: S_Name, ctx: CtExprContext): RStatement {
        val rExpr = expr.compile(ctx)
        ctx.entCtx.matchReturnType(name.pos, rExpr.type)

        if (rExpr.type == RUnitType) {
            val subCtx = CtExprContext(ctx.entCtx, ctx, ctx.insideLoop)
            val rBlock = subCtx.makeFrameBlock()
            return RBlockStatement(listOf(RExprStatement(rExpr), RReturnStatement(null)), rBlock)
        } else {
            return RReturnStatement(rExpr)
        }
    }
}

class S_FunctionBodyFull(val body: S_Statement): S_FunctionBody() {
    override fun compileQuery(name: S_Name, ctx: CtExprContext): RStatement {
        val rBody = body.compile(ctx)

        val ret = body.returns()
        if (!ret) {
            throw CtError(name.pos, "query_noreturn:${name.str}", "Query '${name.str}': not all code paths return value")
        }

        return rBody
    }

    override fun compileFunction(name: S_Name, ctx: CtExprContext): RStatement {
        val rBody = body.compile(ctx)

        val retType = ctx.entCtx.actualReturnType()
        if (retType != RUnitType) {
            val ret = body.returns()
            if (!ret) {
                throw CtError(name.pos, "fun_noreturn:${name.str}", "Function '${name.str}': not all code paths return value")
            }
        }

        return rBody
    }
}

class S_FunctionDefinition(
        name: S_Name,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition(name) {
    override fun compile(ctx: CtModuleContext) {
        val nameStr = name.str
        if (ctx.functionExists(nameStr)) {
            throw CtError(name.pos, "fun_name_dup:$nameStr", "Duplicate function: '$nameStr'")
        }

        val rRetType = if (retType != null) retType.compile(ctx) else RUnitType

        val entCtx = CtEntityContext(ctx, CtEntityType.FUNCTION, rRetType)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)

        val declaration = Ct_UserFunctionDeclaration(nameStr, rParams, rRetType)
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
        val rBody = body.compileFunction(name, entCtx.rootExprCtx)

        val rCallFrame = entCtx.makeCallFrame()
        val rFunction = RFunction(name.str, declaration.params, rBody, rCallFrame, declaration.type, fnKey)
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

    val rExtParams = rParams.map { (name, rParam) ->
        val ptr = exprCtx.add(name, rParam.type, false)
        RExternalParam(name.str, rParam.type, ptr)
    }

    return rExtParams.toList()
}

private fun compileParams(ctx: CtModuleContext, params: List<S_NameTypePair>): List<Pair<S_Name, RVariable>> {
    val names = mutableSetOf<String>()

    val res = params.map { param ->
        val nameStr = param.name.str
        if (!names.add(nameStr)) {
            throw CtError(param.name.pos, "dup_param_name:$nameStr", "Duplicate parameter: '$nameStr'")
        }
        val rType = param.compileType(ctx)
        Pair(param.name, RVariable(nameStr, rType))
    }

    return res
}

internal object CtUtils {
    fun parse(sourceCode: String): S_ModuleDefinition {
        val tokenSeq = S_Grammar.tokenizer.tokenize(sourceCode)

        // The syntax error position returned by the parser library is misleading: if there is an error in the middle
        // of an operation, it returns the position of the beginning of the operation.
        // Following workaround handles this by tracking the position of the farthest reached token (seems to work fine).

        var maxPos = 0
        var maxRowCol = S_Pos(1, 1)
        val tokenSeq2 = tokenSeq.map {
            if (!it.type.ignored && it.position > maxPos) {
                maxPos = it.position
                maxRowCol = S_Pos(it)
            }
            it
        }

        try {
            val ast = S_Grammar.parseToEnd(tokenSeq2)
            return ast
        } catch (e: ParseException) {
            throw CtError(maxRowCol, "syntax", "Syntax error")
        }
    }

    fun checkUnitType(pos: S_Pos, type: RType, errCode: String, errMsg: String) {
        if (type == RUnitType) {
            throw CtError(pos, errCode, errMsg)
        }
    }

    fun errTypeMissmatch(pos: S_Pos, srcType: RType, dstType: RType, errCode: String, errMsg: String): CtError {
        return CtError(pos, "$errCode:${dstType.toStrictString()}:${srcType.toStrictString()}",
                "$errMsg: ${srcType.toStrictString()} instead of ${dstType.toStrictString()}")
    }

    fun errMutlipleAttrs(pos: S_Pos, attrs: List<DbClassAttr>, errCode: String, errMsg: String): CtError {
        val attrsLst = attrs.map { it.cls.alias + "." + it.attr.name }
        return CtError(pos, "$errCode:${attrsLst.joinToString(",")}", "$errMsg: ${attrsLst.joinToString()}")
    }

    fun errUnknownName(name: S_Name): CtError {
        return CtError(name.pos, "unknown_name:${name.str}", "Unknown name: '${name.str}'")
    }

    fun errUnknownName(name1: S_Name, name2: S_Name): CtError {
        return CtError(name1.pos, "unknown_name:${name1.str}.${name2.str}", "Unknown name: '${name1.str}.${name2.str}'")
    }

    fun errUnknownFunction(name: S_Name): CtError {
        return CtError(name.pos, "unknown_fn:${name.str}", "Unknown function: '${name.str}'")
    }

    fun errUnknownFunction(name1: S_Name, name2: S_Name): CtError {
        return CtError(name1.pos, "unknown_fn:${name1.str}.${name2.str}", "Unknown function: '${name1.str}.${name2.str}'")
    }

    fun errUnknownMember(type: RType, name: S_Name): CtError {
        return CtError(name.pos, "unknown_member:${type.toStrictString()}:${name.str}",
                "Type ${type.toStrictString()} has no member '${name.str}'")

    }

    fun errUnknownMemberFunction(type: RType, name: S_Name): CtError {
        return CtError(name.pos, "unknown_member_fn:${type.toStrictString()}:${name.str}",
                "Type ${type.toStrictString()} has no member function '${name.str}'")

    }

    fun errFunctionNoSql(pos: S_Pos, name: String): CtError {
        return CtError(pos, "expr_call_nosql:$name", "Function '$name' cannot be converted to SQL")
    }
}
