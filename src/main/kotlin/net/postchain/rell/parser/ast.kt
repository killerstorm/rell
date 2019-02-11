package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.lexer.TokenMatch
import net.postchain.rell.model.*
import net.postchain.rell.module.GTX_OPERATION_HUMAN
import net.postchain.rell.module.GTX_QUERY_HUMAN
import net.postchain.rell.sql.ROWID_COLUMN

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
    fun compileType(ctx: C_ModuleContext): R_Type {
        if (type != null) {
            return type.compile(ctx)
        }

        val rType = ctx.getTypeOpt(name)
        if (rType == null) {
            throw C_Error(name.pos, "unknown_name_type:${name.str}",
                    "Type for '${name.str}' not specified and no type called '${name.str}'")
        }

        return rType
    }
}

sealed class S_RelClause {
    abstract fun compileAttributes(ctx: C_ClassContext)
    abstract fun compileRest(ctx: C_ClassContext)
}

class S_AttributeClause(val attr: S_NameTypePair, val mutable: Boolean, val expr: S_Expr?): S_RelClause() {
    override fun compileAttributes(ctx: C_ClassContext) {
        ctx.addAttribute(attr, mutable, expr)
    }

    override fun compileRest(ctx: C_ClassContext) {}
}

sealed class S_KeyIndexClause(val pos: S_Pos, val attrs: List<S_NameTypePair>): S_RelClause() {
    final override fun compileAttributes(ctx: C_ClassContext) {}

    abstract fun addToContext(ctx: C_ClassContext, pos: S_Pos, names: List<S_Name>)

    final override fun compileRest(ctx: C_ClassContext) {
        val names = mutableSetOf<String>()
        for (attr in attrs) {
            if (!names.add(attr.name.str)) {
                throw C_Error(attr.name.pos, "class_keyindex_dup:${attr.name.str}",
                        "Duplicate attribute: '${attr.name.str}'")
            }
        }

        for (attr in attrs) {
            if (ctx.hasAttribute(attr.name.str)) {
                if (attr.type != null) {
                    throw C_Error(attr.name.pos, "class_keyindex_def:${attr.name.str}",
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
    override fun addToContext(ctx: C_ClassContext, pos: S_Pos, names: List<S_Name>) {
        ctx.addKey(pos, names)
    }
}

class S_IndexClause(pos: S_Pos, attrs: List<S_NameTypePair>): S_KeyIndexClause(pos, attrs) {
    override fun addToContext(ctx: C_ClassContext, pos: S_Pos, names: List<S_Name>) {
        ctx.addIndex(pos, names)
    }
}

sealed class S_Definition(val name: S_Name) {
    abstract fun compile(ctx: C_ModuleContext, entityIndex: Int)
}

class S_ClassDefinition(name: S_Name, val annotations: List<S_Name>, val clauses: List<S_RelClause>): S_Definition(name) {
    override fun compile(ctx: C_ModuleContext, entityIndex: Int) {
        ctx.checkTypeName(name)

        val rFlags = compileFlags()

        val sqlTable = classNameToSqlTable(name.str)
        val rMapping = R_ClassSqlMapping(sqlTable, false, ROWID_COLUMN, true)

        val rClass = R_Class(name.str, rFlags, rMapping)
        ctx.addClass(name, rClass)

        ctx.classesPass.add {
            classesPass(ctx, entityIndex, rClass)
        }
    }

    private fun compileFlags(): R_ClassFlags {
        val set = mutableSetOf<String>()
        var log = false

        for (ann in annotations) {
            val annStr = ann.str
            if (!set.add(annStr)) {
                throw C_Error(ann.pos, "class_ann_dup:$annStr", "Duplicate annotation: '$annStr'")
            }

            if (annStr == C_Defs.LOG_ANNOTATION) {
                log = true
            } else {
                throw C_Error(ann.pos, "class_ann_bad:$annStr", "Invalid annotation: '$annStr'")
            }
        }

        return R_ClassFlags(false, true, true, !log, log)
    }

    private fun classesPass(ctx: C_ModuleContext, entityIndex: Int, rClass: R_Class) {
        val entCtx = C_EntityContext(ctx, C_EntityType.CLASS, entityIndex, null)
        val clsCtx = C_ClassContext(entCtx, name.str, rClass.flags.log)

        if (rClass.flags.log) {
            clsCtx.addAttribute0("transaction", ctx.transactionClassType, false, false) {
                C_Ns_OpContext.transactionExpr(clsCtx.entCtx)
            }
        }

        compileClauses(clsCtx, clauses)

        val body = clsCtx.createClassBody()
        rClass.setBody(body)
    }

    companion object {
        fun compileClauses(clsCtx: C_ClassContext, clauses: List<S_RelClause>) {
            for (clause in clauses) {
                clause.compileAttributes(clsCtx)
            }
            for (clause in clauses) {
                clause.compileRest(clsCtx)
            }
        }
    }
}

class S_ObjectDefinition(name: S_Name, val clauses: List<S_RelClause>): S_Definition(name) {
    override fun compile(ctx: C_ModuleContext, entityIndex: Int) {
        ctx.checkTypeName(name)

        val sqlTable = classNameToSqlTable(name.str)

        val classFlags = R_ClassFlags(true, false, true, false, false)
        val sqlMapping = R_ClassSqlMapping(sqlTable, false, ROWID_COLUMN, true)
        val rClass = R_Class(name.str, classFlags, sqlMapping)
        val rObject = R_Object(rClass, entityIndex)
        ctx.addObject(rObject)

        ctx.classesPass.add {
            classesPass(ctx, entityIndex, rObject)
        }
    }

    private fun classesPass(ctx: C_ModuleContext, entityIndex: Int, rObject: R_Object) {
        val entCtx = C_EntityContext(ctx, C_EntityType.OBJECT, entityIndex, null)
        val clsCtx = C_ClassContext(entCtx, name.str, false)
        S_ClassDefinition.compileClauses(clsCtx, clauses)

        val body = clsCtx.createClassBody()
        rObject.rClass.setBody(body)
    }
}

class S_RecordDefinition(name: S_Name, val attrs: List<S_AttributeClause>): S_Definition(name) {
    override fun compile(ctx: C_ModuleContext, entityIndex: Int) {
        ctx.checkRecordName(name)

        val rType = R_RecordType(name.str)
        ctx.addRecord(C_Record(name, rType))

        ctx.classesPass.add {
            classesPass(ctx, entityIndex, rType)
        }
    }

    private fun classesPass(ctx: C_ModuleContext, entityIndex: Int, rType: R_RecordType) {
        val entCtx = C_EntityContext(ctx, C_EntityType.RECORD, entityIndex, null)
        val clsCtx = C_ClassContext(entCtx, name.str, false)
        for (clause in attrs) {
            clause.compileAttributes(clsCtx)
        }

        val attributes = clsCtx.createRecordBody()
        rType.setAttributes(attributes)
    }
}

class S_OpDefinition(
        name: S_Name,
        val params: List<S_NameTypePair>,
        val body: S_Statement
): S_Definition(name)
{
    override fun compile(ctx: C_ModuleContext, entityIndex: Int) {
        ctx.functionsPass.add {
            doCompile(ctx, entityIndex)
        }
    }

    private fun doCompile(ctx: C_ModuleContext, entityIndex: Int) {
        ctx.checkOperationName(name)

        val entCtx = C_EntityContext(ctx, C_EntityType.OPERATION, entityIndex, null)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)
        val rBody = body.compile(entCtx.rootExprCtx)
        val rCallFrame = entCtx.makeCallFrame()

        if (ctx.globalCtx.gtx) {
            checkGtxParams(params, rParams, GTX_OPERATION_HUMAN)
        }

        val rOperation = R_Operation(name.str, rParams, rBody, rCallFrame)
        ctx.addOperation(rOperation)
    }
}

class S_QueryDefinition(
        name: S_Name,
        val params: List<S_NameTypePair>,
        val retType: S_Type?,
        val body: S_FunctionBody
): S_Definition(name) {
    override fun compile(ctx: C_ModuleContext, entityIndex: Int) {
        ctx.functionsPass.add {
            doCompile(ctx, entityIndex)
        }
    }

    private fun doCompile(ctx: C_ModuleContext, entityIndex: Int) {
        ctx.checkOperationName(name)

        val rExplicitRetType = retType?.compile(ctx)

        val entCtx = C_EntityContext(ctx, C_EntityType.QUERY, entityIndex, rExplicitRetType)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)
        val rBody = body.compileQuery(name, entCtx.rootExprCtx)
        val rCallFrame = entCtx.makeCallFrame()
        val rRetType = entCtx.actualReturnType()

        if (ctx.globalCtx.gtx) {
            checkGtxParams(params, rParams, GTX_QUERY_HUMAN)
            checkGtxResult(rRetType, GTX_QUERY_HUMAN)
        }

        val rQuery = R_Query(name.str, rRetType, rParams, rBody, rCallFrame)
        ctx.addQuery(rQuery)
    }

    private fun checkGtxResult(rType: R_Type, human: Boolean) {
        val flags = rType.completeFlags()
        val gtx = if (human) flags.gtxHuman else flags.gtxCompact
        if (!gtx) {
            throw C_Error(name.pos, "result_nogtx:${name.str}:${rType.toStrictString()}",
                    "Return type of query '${name.str}' is not GTX-conversible: ${rType.toStrictString()}")
        }
    }
}

private fun checkGtxParams(params: List<S_NameTypePair>, rParams: List<R_ExternalParam>, human: Boolean) {
    params.forEachIndexed { i, param ->
        val type = rParams[i].type
        val flags = type.completeFlags()
        val gtx = if (human) flags.gtxHuman else flags.gtxCompact
        if (!gtx) {
            val name = param.name.str
            val typeStr = type.toStrictString()
            throw C_Error(param.name.pos, "param_nogtx:$name:$typeStr",
                    "Type of parameter '$name' is not GTX-conversible: $typeStr")
        }
    }
}

abstract class S_FunctionBody {
    abstract fun compileQuery(name: S_Name, ctx: C_ExprContext): R_Statement
    abstract fun compileFunction(name: S_Name, ctx: C_ExprContext): R_Statement
}

class S_FunctionBodyShort(val expr: S_Expr): S_FunctionBody() {
    override fun compileQuery(name: S_Name, ctx: C_ExprContext): R_Statement {
        val cExpr = expr.compile(ctx)
        val rExpr = cExpr.toRExpr()
        C_Utils.checkUnitType(name.pos, rExpr.type, "query_exprtype_unit", "Query expressions returns nothing")
        ctx.blkCtx.entCtx.matchReturnType(name.pos, rExpr.type)
        return R_ReturnStatement(rExpr)
    }

    override fun compileFunction(name: S_Name, ctx: C_ExprContext): R_Statement {
        val rExpr = expr.compile(ctx).toRExpr()
        ctx.blkCtx.entCtx.matchReturnType(name.pos, rExpr.type)

        if (rExpr.type != R_UnitType) {
            return R_ReturnStatement(rExpr)
        }

        val blkCtx = ctx.blkCtx
        val subBlkCtx = C_BlockContext(blkCtx.entCtx, blkCtx, blkCtx.insideLoop)
        val rBlock = subBlkCtx.makeFrameBlock()

        return R_BlockStatement(listOf(R_ExprStatement(rExpr), R_ReturnStatement(null)), rBlock)
    }
}

class S_FunctionBodyFull(val body: S_Statement): S_FunctionBody() {
    override fun compileQuery(name: S_Name, ctx: C_ExprContext): R_Statement {
        val rBody = body.compile(ctx)

        val ret = body.returns()
        if (!ret) {
            throw C_Error(name.pos, "query_noreturn:${name.str}", "Query '${name.str}': not all code paths return value")
        }

        return rBody
    }

    override fun compileFunction(name: S_Name, ctx: C_ExprContext): R_Statement {
        val rBody = body.compile(ctx)

        val retType = ctx.blkCtx.entCtx.actualReturnType()
        if (retType != R_UnitType) {
            val ret = body.returns()
            if (!ret) {
                throw C_Error(name.pos, "fun_noreturn:${name.str}", "Function '${name.str}': not all code paths return value")
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
    override fun compile(ctx: C_ModuleContext, entityIndex: Int) {
        ctx.checkFunctionName(name)

        val rRetType = if (retType != null) retType.compile(ctx) else R_UnitType

        val entCtx = C_EntityContext(ctx, C_EntityType.FUNCTION, entityIndex, rRetType)
        val rParams = compileExternalParams(ctx, entCtx.rootExprCtx, params)

        val declaration = C_UserFunctionDeclaration(name.str, rParams, rRetType)
        val fnKey = ctx.addFunctionDeclaration(declaration)

        ctx.functionsPass.add {
            secondPass(ctx, entCtx, declaration, fnKey)
        }
    }

    private fun secondPass(
            ctx: C_ModuleContext,
            entCtx: C_EntityContext,
            declaration: C_UserFunctionDeclaration,
            fnKey: Int
    ){
        val rBody = body.compileFunction(name, entCtx.rootExprCtx)

        val rCallFrame = entCtx.makeCallFrame()
        val rFunction = R_Function(name.str, declaration.params, rBody, rCallFrame, declaration.type, fnKey)
        ctx.addFunctionDefinition(rFunction)
    }
}

class S_ModuleDefinition(val definitions: List<S_Definition>) {
    fun compile(gtx: Boolean): R_Module {
        val globalCtx = C_GlobalContext(gtx)
        val ctx = C_ModuleContext(globalCtx)

        for ((index, def) in definitions.withIndex()) {
            def.compile(ctx, index)
        }

        return ctx.createModule()
    }
}

private fun compileExternalParams(
        ctx: C_ModuleContext,
        exprCtx: C_ExprContext,
        params: List<S_NameTypePair>
) : List<R_ExternalParam>
{
    val rParams = compileParams(ctx, params)

    val rExtParams = rParams.map { (name, rParam) ->
        val ptr = exprCtx.blkCtx.add(name, rParam.type, false)
        R_ExternalParam(name.str, rParam.type, ptr)
    }

    return rExtParams.toList()
}

private fun compileParams(ctx: C_ModuleContext, params: List<S_NameTypePair>): List<Pair<S_Name, R_Variable>> {
    val names = mutableSetOf<String>()

    val res = params.map { param ->
        val nameStr = param.name.str
        if (!names.add(nameStr)) {
            throw C_Error(param.name.pos, "dup_param_name:$nameStr", "Duplicate parameter: '$nameStr'")
        }
        val rType = param.compileType(ctx)
        Pair(param.name, R_Variable(nameStr, rType))
    }

    return res
}

private fun classNameToSqlTable(className: String) = className
