/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import com.github.h0tk3y.betterParse.parser.ParseException
import com.github.h0tk3y.betterParse.parser.Parser
import com.github.h0tk3y.betterParse.parser.parseToEnd
import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.parser.RellTokenizerError
import net.postchain.rell.compiler.parser.RellTokenizerState
import net.postchain.rell.compiler.parser.S_Grammar
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Error
import net.postchain.rell.runtime.toGtv
import net.postchain.rell.utils.*
import org.apache.commons.lang3.StringUtils
import org.jooq.impl.SQLDataType
import java.math.BigDecimal
import java.util.*

class C_CodeMsg(val code: String, val msg: String) {
    fun toPair() = code to msg

    override fun toString() = code
}

class C_PosCodeMsg(val pos: S_Pos, val code: String, val msg: String) {
    constructor(pos: S_Pos, codeMsg: C_CodeMsg): this(pos, codeMsg.code, codeMsg.msg)
}

class C_CommonError(val code: String, val msg: String): RuntimeException(msg) {
    constructor(codeMsg: C_CodeMsg): this(codeMsg.code, codeMsg.msg)
}

class C_Error: RuntimeException {
    val pos: S_Pos
    val code: String
    val errMsg: String

    private constructor(pos: S_Pos, code: String, errMsg: String): super("$pos $errMsg") {
        this.pos = pos
        this.code = code
        this.errMsg = errMsg
    }

    private constructor(pos: S_Pos, codeMsg: C_CodeMsg): this(pos, codeMsg.code, codeMsg.msg)

    companion object {
        fun more(pos: S_Pos, code: String, errMsg: String) = C_Error(pos, code, errMsg)
        fun stop(pos: S_Pos, code: String, errMsg: String) = C_Error(pos, code, errMsg)
        fun stop(pos: S_Pos, codeMsg: C_CodeMsg) = C_Error(pos, codeMsg)
        fun other(pos: S_Pos, code: String, errMsg: String) = C_Error(pos, code, errMsg)
    }
}

enum class C_MessageType(val text: String, val ignorable: Boolean) {
    WARNING("Warning", true),
    ERROR("ERROR", false)
}

class C_Message(
        val type: C_MessageType,
        val pos: S_Pos,
        val code: String,
        val text: String
) {
    override fun toString(): String {
        return "$pos ${type.text}: $text"
    }
}

object C_Constants {
    const val LOG_ANNOTATION = "log"
    const val MODULE_ARGS_STRUCT = "module_args"

    const val AT_PLACEHOLDER = "$"

    const val TRANSACTION_ENTITY = "transaction"
    const val BLOCK_ENTITY = "block"

    const val DECIMAL_INT_DIGITS = 131072
    const val DECIMAL_FRAC_DIGITS = 20
    const val DECIMAL_SQL_TYPE_STR = "NUMERIC"
    val DECIMAL_SQL_TYPE = SQLDataType.DECIMAL

    const val DECIMAL_PRECISION = DECIMAL_INT_DIGITS + DECIMAL_FRAC_DIGITS
    val DECIMAL_MIN_VALUE = BigDecimal.ONE.divide(BigDecimal.TEN.pow(DECIMAL_FRAC_DIGITS))
    val DECIMAL_MAX_VALUE = BigDecimal.TEN.pow(DECIMAL_PRECISION).subtract(BigDecimal.ONE)
            .divide(BigDecimal.TEN.pow(DECIMAL_FRAC_DIGITS))
}

class C_ParameterDefaultValue(
        private val pos: S_Pos,
        private val paramName: String,
        private val rExprGetter: C_LateGetter<R_Expr>,
        private val initFrameGetter: C_LateGetter<R_CallFrame>,
        val rGetter: C_LateGetter<R_DefaultValue>
) {
    fun createArgumentExpr(ctx: C_ExprContext, callPos: S_Pos, paramType: R_Type): V_Expr {
        val dbModRes = ctx.getDbModificationRestriction()
        if (dbModRes != null) {
            ctx.executor.onPass(C_CompilerPass.VALIDATION) {
                val rDefaultValue = rGetter.get()
                if (rDefaultValue.isDbModification) {
                    val code = "${dbModRes.code}:param:$paramName"
                    val msg = "${dbModRes.msg} (default value of parameter '$paramName')"
                    ctx.msgCtx.error(callPos, code, msg)
                }
            }
        }

        return V_ParameterDefaultValueExpr(ctx, pos, paramType, callPos.toFilePos(), initFrameGetter, rExprGetter)
    }
}

object C_Utils {
    val ERROR_EXPR = errorRExpr()
    val ERROR_DB_EXPR = errorDbExpr()
    val ERROR_STATEMENT = R_ExprStatement(ERROR_EXPR)

    fun toDbExpr(errPos: S_Pos, rExpr: R_Expr): Db_Expr {
        val type = rExpr.type
        if (!type.sqlAdapter.isSqlCompatible()) {
            throw C_Errors.errExprNoDb(errPos, type)
        }
        return Db_InterpretedExpr(rExpr)
    }

    fun makeDbBinaryExpr(type: R_Type, rOp: R_BinaryOp, dbOp: Db_BinaryOp, left: Db_Expr, right: Db_Expr): Db_Expr {
        return if (left is Db_InterpretedExpr && right is Db_InterpretedExpr) {
            val rExpr = R_BinaryExpr(type, rOp, left.expr, right.expr)
            Db_InterpretedExpr(rExpr)
        } else {
            Db_BinaryExpr(type, dbOp, left, right)
        }
    }

    fun makeDbBinaryExprEq(left: Db_Expr, right: Db_Expr): Db_Expr {
        return makeDbBinaryExpr(R_BooleanType, R_BinaryOp_Eq, Db_BinaryOp_Eq, left, right)
    }

    fun makeDbBinaryExprChain(type: R_Type, rOp: R_BinaryOp, dbOp: Db_BinaryOp, exprs: List<Db_Expr>): Db_Expr {
        return CommonUtils.foldSimple(exprs) { left, right -> makeDbBinaryExpr(type, rOp, dbOp, left, right) }
    }

    fun makeVBinaryExprEq(ctx: C_ExprContext, pos: S_Pos, left: V_Expr, right: V_Expr): V_Expr {
        val vOp = C_BinOp_EqNe.createVOp(true, left.type)
        return V_BinaryExpr(ctx, pos, vOp, left, right, C_ExprVarFacts.EMPTY)
    }

    fun effectiveMemberType(formalType: R_Type, safe: Boolean): R_Type {
        if (!safe || formalType is R_NullableType || formalType == R_NullType) {
            return formalType
        } else {
            return R_NullableType(formalType)
        }
    }

    fun checkUnitType(pos: S_Pos, type: R_Type, errCode: String, errMsg: String) {
        C_Errors.check(type != R_UnitType, pos, errCode, errMsg)
    }

    fun checkUnitType(msgCtx: C_MessageContext, pos: S_Pos, type: R_Type, errCode: String, errMsg: String): Boolean {
        if (type == R_UnitType) {
            msgCtx.error(pos, errCode, errMsg)
            return false
        }
        return true
    }

    fun checkMapKeyType(ctx: C_NamespaceContext, pos: S_Pos, type: R_Type) {
        checkMapKeyType0(ctx.appCtx, pos, type, "expr_map_keytype", "as a map key")
    }

    fun checkSetElementType(ctx: C_NamespaceContext, pos: S_Pos, type: R_Type) {
        checkMapKeyType0(ctx.appCtx, pos, type, "expr_set_type", "as a set element")
    }

    fun checkGroupValueType(appCtx: C_AppContext, pos: S_Pos, type: R_Type) {
        checkMapKeyType0(appCtx, pos, type, "expr_at_group_type", "for grouping")
    }

    private fun checkMapKeyType0(appCtx: C_AppContext, pos: S_Pos, type: R_Type, errCode: String, errMsg: String) {
        appCtx.executor.onPass(C_CompilerPass.VALIDATION) {
            if (type.completeFlags().mutable) {
                val typeStr = type.toStrictString()
                appCtx.msgCtx.error(pos, "$errCode:$typeStr", "Mutable type cannot be used $errMsg: $typeStr")
            }
        }
    }

    fun createBlockEntity(appCtx: C_AppContext, chain: R_ExternalChainRef?): R_EntityDefinition {
        val attrs = listOf(
                C_SysAttribute("block_height", R_IntegerType, false),
                C_SysAttribute("block_rid", R_ByteArrayType, false),
                C_SysAttribute("timestamp", R_IntegerType, false)
        )
        val sqlMapping = R_EntitySqlMapping_Block(chain)
        return createSysEntity(appCtx, C_Constants.BLOCK_ENTITY, chain, sqlMapping, attrs)
    }

    fun createTransactionEntity(appCtx: C_AppContext, chain: R_ExternalChainRef?, blockEntity: R_EntityDefinition): R_EntityDefinition {
        val attrs = listOf(
                C_SysAttribute("tx_rid", R_ByteArrayType, false),
                C_SysAttribute("tx_hash", R_ByteArrayType, false),
                C_SysAttribute("tx_data", R_ByteArrayType, false),
                C_SysAttribute("block", blockEntity.type, false, sqlMapping = "block_iid")
        )
        val sqlMapping = R_EntitySqlMapping_Transaction(chain)
        return createSysEntity(appCtx, C_Constants.TRANSACTION_ENTITY, chain, sqlMapping, attrs)
    }

    private fun createSysEntity(
            appCtx: C_AppContext,
            simpleName: String,
            chain: R_ExternalChainRef?,
            sqlMapping: R_EntitySqlMapping,
            attrs: List<C_SysAttribute>
    ): R_EntityDefinition {
        val names = createDefNames(R_ModuleName.EMPTY, chain, null, listOf(simpleName))
        val mountName = R_MountName.of(simpleName)

        val flags = R_EntityFlags(
                isObject = false,
                canCreate = false,
                canUpdate = false,
                canDelete = false,
                gtv = false,
                log = false
        )

        val externalEntity = if (chain == null) null else R_ExternalEntity(chain, false)

        val entity = createEntity(
                appCtx,
                C_DefinitionType.ENTITY,
                names,
                R_CallFrame.NONE_INIT_FRAME_GETTER,
                mountName,
                flags,
                sqlMapping,
                externalEntity
        )

        val rAttrs = attrs.mapIndexed { i, attr -> attr.compile(i) }
        val rAttrMap = rAttrs.map { it.name to it }.toMap()

        appCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            setEntityBody(entity, R_EntityBody(listOf(), listOf(), rAttrMap))
        }

        return entity
    }

    fun createEntity(
            appCtx: C_AppContext,
            defType: C_DefinitionType,
            names: R_DefinitionNames,
            initFrameGetter: C_LateGetter<R_CallFrame>,
            mountName: R_MountName,
            flags: R_EntityFlags,
            sqlMapping: R_EntitySqlMapping,
            externalEntity: R_ExternalEntity?
    ): R_EntityDefinition {
        val mirrorStructs = createMirrorStructs(appCtx, names, initFrameGetter, defType)
        return R_EntityDefinition(names, initFrameGetter, mountName, flags, sqlMapping, externalEntity, mirrorStructs)
    }

    fun createMirrorStructs(
            appCtx: C_AppContext,
            names: R_DefinitionNames,
            initFrameGetter: C_LateGetter<R_CallFrame>,
            defType: C_DefinitionType,
            operation: R_MountName? = null
    ): R_MirrorStructs {
        val res = R_MirrorStructs(names, initFrameGetter, defType.name, operation)
        appCtx.defsAdder.addStruct(res.immutable)
        appCtx.defsAdder.addStruct(res.mutable)
        return res
    }

    fun setEntityBody(entity: R_EntityDefinition, body: R_EntityBody) {
        entity.setBody(body)
        setEntityMirrorStructAttrs(body, entity, false)
        setEntityMirrorStructAttrs(body, entity, true)
    }

    private fun setEntityMirrorStructAttrs(body: R_EntityBody, entity: R_EntityDefinition, mutable: Boolean) {
        val struct = entity.mirrorStructs.getStruct(mutable)
        val structAttrs = body.attributes.mapValues { it.value.copy(mutable = mutable) }
        struct.setAttributes(structAttrs)
    }

    fun createSysStruct(name: String, attrs: List<C_SysAttribute>): R_Struct {
        val rStruct = R_Struct(
                name,
                name.toGtv(),
                mirrorStructs = null,
                initFrameGetter = R_CallFrame.NONE_INIT_FRAME_GETTER
        )
        val rAttrs = attrs.mapIndexed { i, attr -> attr.name to attr.compile(i) }.toMap().toImmMap()
        rStruct.setAttributes(rAttrs)
        return rStruct
    }

    fun createSysStruct(name: String, vararg attrs: C_SysAttribute): R_Struct {
        return createSysStruct(name, attrs.toList())
    }

    fun createSysQuery(executor: C_CompilerExecutor, simpleName: String, type: R_Type, fn: R_SysFunction): R_QueryDefinition {
        val moduleName = R_ModuleName.of("rell")
        val names = createDefNames(moduleName, null, null, listOf(simpleName))

        val mountName = R_MountName(moduleName.parts + R_Name.of(simpleName))
        val query = R_QueryDefinition(names, R_CallFrame.NONE_INIT_FRAME_GETTER, mountName)

        executor.onPass(C_CompilerPass.EXPRESSIONS) {
            val body = R_SysQueryBody(type, listOf(), fn)
            query.setBody(body)
        }

        return query
    }

    fun createDefNames(
            moduleName: R_ModuleName,
            extChain: R_ExternalChainRef?,
            namespacePath: String?,
            qualifiedName: List<String>
    ): R_DefinitionNames {
        check(qualifiedName.isNotEmpty())

        val fullNamespacePath = if (qualifiedName.size == 1) namespacePath else {
            fullName(namespacePath, qualifiedName.subList(0, qualifiedName.size - 1).joinToString("."))
        }

        val simpleName = qualifiedName.last()

        var modName = moduleName.str()
        if (extChain != null) modName += "[${extChain.name}]"

        return R_DefinitionNames(modName, fullNamespacePath, simpleName)
    }

    fun createSysCallRExpr(type: R_Type, fn: R_SysFunction, args: List<R_Expr>, qualifiedName: List<S_Name>): R_Expr {
        val nameStr = nameStr(qualifiedName)
        return createSysCallRExpr(type, fn, args, qualifiedName[0].pos, nameStr)
    }

    fun createSysCallRExpr(type: R_Type, fn: R_SysFunction, args: List<R_Expr>, caseCtx: C_FuncCaseCtx): R_Expr {
        return createSysCallRExpr(type, fn, args, caseCtx.linkPos, caseCtx.qualifiedNameMsg())
    }

    fun createSysCallRExpr(type: R_Type, fn: R_SysFunction, args: List<R_Expr>, pos: S_Pos, nameMsg: String): R_Expr {
        val rCallTarget: R_FunctionCallTarget = R_FunctionCallTarget_SysGlobalFunction(fn, nameMsg)
        val filePos = pos.toFilePos()
        val rCallExpr: R_Expr = R_FullFunctionCallExpr(type, rCallTarget, filePos, listOf(), args, args.indices.toList())
        return R_StackTraceExpr(rCallExpr, filePos)
    }

    fun createSysGlobalPropExpr(
            exprCtx: C_ExprContext,
            type: R_Type,
            fn: R_SysFunction,
            qualifiedName: List<S_Name>,
            pure: Boolean
    ): V_Expr {
        val nameStr = nameStr(qualifiedName)
        return createSysGlobalPropExpr(exprCtx, type, fn, qualifiedName[0].pos, nameStr, pure)
    }

    fun createSysGlobalPropExpr(
            exprCtx: C_ExprContext,
            type: R_Type,
            fn: R_SysFunction,
            pos: S_Pos,
            nameMsg: String,
            pure: Boolean
    ): V_Expr {
        val desc = V_SysFunctionTargetDescriptor(type, fn, null, nameMsg, pure = pure, synth = true)
        val vCallTarget: V_FunctionCallTarget = V_FunctionCallTarget_SysGlobalFunction(desc)
        return V_FullFunctionCallExpr(exprCtx, pos, pos, type, vCallTarget, V_FunctionCallArgs.EMPTY)
    }

    fun errorRExpr(type: R_Type = R_CtErrorType, msg: String = "Compilation error"): R_Expr {
        return R_ErrorExpr(type, msg)
    }

    fun errorDbExpr(type: R_Type = R_CtErrorType, msg: String = "Compilation error"): Db_Expr {
        val rExpr = errorRExpr(type, msg)
        return Db_InterpretedExpr(rExpr)
    }

    fun errorVExpr(ctx: C_ExprContext, pos: S_Pos, type: R_Type = R_CtErrorType, msg: String = "Compilation error"): V_Expr {
        return V_ErrorExpr(ctx, pos, type, msg)
    }

    fun errorExpr(ctx: C_ExprContext, pos: S_Pos, type: R_Type = R_CtErrorType, msg: String = "Compilation error"): C_Expr {
        val value = errorVExpr(ctx, pos, type, msg)
        return C_VExpr(value)
    }

    fun fullName(namespacePath: String?, name: String): String {
        return if (namespacePath == null) name else "$namespacePath.$name"
    }

    fun nameStr(name: List<S_Name>): String = name.joinToString(".") { it.str }
    fun namePosStr(name: List<S_Name>): S_String = S_String(name[0].pos, nameStr(name))

    fun <T> evaluate(pos: S_Pos, code: () -> T): T {
        try {
            val v = code()
            return v
        } catch (e: Rt_Error) {
            throw C_Error.stop(pos, "eval_fail:${e.code}", e.message ?: "Evaluation failed")
        } catch (e: Throwable) {
            throw C_Error.stop(pos, "eval_fail:${e.javaClass.canonicalName}", "Evaluation failed")
        }
    }

    fun appLevelName(module: C_ModuleKey, path: List<S_Name>): String {
        val moduleName = module.keyStr()
        val qualifiedName = nameStr(path)
        return R_DefinitionId.appLevelName(moduleName, qualifiedName)
    }

    fun checkGtvCompatibility(
            msgCtx: C_MessageContext,
            pos: S_Pos,
            type: R_Type,
            from: Boolean,
            errCode: String,
            errMsg: String
    ) {
        val flags = type.completeFlags()
        val flag = if (from) flags.gtv.fromGtv else flags.gtv.toGtv
        if (!flag) {
            val fullMsg = "$errMsg is not Gtv-compatible: ${type.toStrictString()}"
            msgCtx.error(pos, "$errCode:${type.toStrictString()}", fullMsg)
        }
    }
}

sealed class C_ParserResult<T> {
    abstract fun getAst(): T
}

private class C_SuccessParserResult<T>(private val ast: T): C_ParserResult<T>() {
    override fun getAst() = ast
}

private class C_ErrorParserResult<T>(val error: C_Error, val eof: Boolean): C_ParserResult<T>() {
    override fun getAst() = throw error
}

object C_Parser {
    private val currentFileLocal = ThreadLocalContext(C_SourcePath.parse("?"))

    fun parse(filePath: C_SourcePath, sourceCode: String): S_RellFile {
        val res = parse0(filePath, sourceCode, S_Grammar)
        val ast = res.getAst()
        return ast
    }

    fun parseRepl(code: String): S_ReplCommand {
        val path = C_SourcePath.parse("<console>")
        val res = parse0(path, code, S_Grammar.replParser)
        val ast = res.getAst()
        return ast
    }

    fun checkEofErrorRepl(code: String): C_Error? {
        val path = C_SourcePath.parse("<console>")
        val res = parse0(path, code, S_Grammar.replParser)
        return when (res) {
            is C_SuccessParserResult -> null
            is C_ErrorParserResult -> if (res.eof) res.error else null
        }
    }

    private fun <T> parse0(filePath: C_SourcePath, sourceCode: String, parser: Parser<T>): C_ParserResult<T> {
        // The syntax error position returned by the parser library is misleading: if there is an error in the middle
        // of an operation, it returns the position of the beginning of the operation.
        // Following workaround handles this by tracking the position of the farthest reached token (seems to work fine).

        val state = RellTokenizerState()
        val tokenSeq = S_Grammar.tokenizer.tokenize(sourceCode, state)

        try {
            val ast = overrideCurrentFile(filePath) {
                parser.parseToEnd(tokenSeq)
            }
            return C_SuccessParserResult(ast)
        } catch (e: RellTokenizerError) {
            val error = C_Error.other(e.pos, e.code, e.msg)
            return C_ErrorParserResult(error, e.eof)
        } catch (e: ParseException) {
            val pos = S_BasicPos(filePath, state.lastRow, state.lastCol)
            val error = C_Error.other(pos, "syntax", "Syntax error")
            return C_ErrorParserResult(error, state.lastEof)
        }
    }

    private fun <T> overrideCurrentFile(file: C_SourcePath, code: () -> T): T {
        return currentFileLocal.set(file, code)
    }

    fun currentFile() = currentFileLocal.get()
}

object C_GraphUtils {
    fun <T> findCycles(graph: Map<T, Collection<T>>): List<List<T>> {
        val graphEx = graph.mapValues { vert ->
            vert.value.map { 0 to it }.toImmList()
        }

        val cyclesEx = findCyclesEx(graphEx)

        return cyclesEx.map { cycle ->
            cycle.map { it.second }.toImmList()
        }.toImmList()
    }

    /** Returns some, not all cycles (at least one cycle for each cyclic vertex). */
    fun <V, E> findCyclesEx(graph: Map<V, Collection<Pair<E, V>>>): List<List<Pair<E, V>>> {
        class VertEntry<E, V>(val vert: V, val edge: E?, val enter: Boolean, val parent: VertEntry<E, V>?)

        val queue = LinkedList<VertEntry<E, V>>()
        val visiting = mutableSetOf<V>()
        val visited = mutableSetOf<V>()
        val cycles = mutableListOf<List<Pair<E, V>>>()

        for (vert in graph.keys) {
            queue.add(VertEntry(vert, null, true, null))
        }

        while (!queue.isEmpty()) {
            val entry = queue.remove()

            if (!entry.enter) {
                check(visiting.remove(entry.vert))
                check(visited.add(entry.vert))
                continue
            } else if (entry.vert in visited) {
                check(entry.vert !in visiting)
                continue
            } else if (entry.vert in visiting) {
                var cycleEntry = entry
                val cycle = mutableListOf<Pair<E, V>>()
                while (true) {
                    cycle.add(cycleEntry.edge!! to cycleEntry.vert)
                    cycleEntry = cycleEntry.parent
                    check(cycleEntry != null)
                    if (cycleEntry.vert == entry.vert) break
                }
                cycles.add(cycle.toList())
                continue
            }

            queue.addFirst(VertEntry(entry.vert, entry.edge, false, entry.parent))
            visiting.add(entry.vert)

            for ((adjEdge, adjVert) in graph.getValue(entry.vert)) {
                queue.addFirst(VertEntry(adjVert, adjEdge, true, entry))
            }
        }

        return cycles.toImmList()
    }

    fun <T> topologicalSort(graph: Map<T, Collection<T>>): List<T> {
        class VertEntry<T>(val vert: T, val enter: Boolean, val parent: VertEntry<T>?)

        val queue = LinkedList<VertEntry<T>>()
        val visiting = mutableSetOf<T>()
        val visited = mutableSetOf<T>()
        val result = mutableListOf<T>()

        for (vert in graph.keys) {
            queue.add(VertEntry(vert, true, null))
        }

        while (!queue.isEmpty()) {
            val entry = queue.remove()

            if (!entry.enter) {
                check(visiting.remove(entry.vert))
                check(visited.add(entry.vert))
                result.add(entry.vert)
                continue
            } else if (entry.vert in visited) {
                check(entry.vert !in visiting)
                continue
            }

            check(entry.vert !in visiting) // Cycle
            queue.addFirst(VertEntry(entry.vert, false, entry.parent))
            visiting.add(entry.vert)

            for (adjVert in graph.getValue(entry.vert)) {
                queue.addFirst(VertEntry(adjVert, true, entry))
            }
        }

        return result.toList()
    }

    fun <T> findCyclicVertices(graph: Map<T, Collection<T>>): List<T> {
        val cycles = findCycles(graph)
        val cyclicVertices = mutableSetOf<T>()
        for (cycle in cycles) {
            cyclicVertices.addAll(cycle)
        }
        return cyclicVertices.toList()
    }

    fun <T> transpose(graph: Map<T, Collection<T>>): Map<T, Collection<T>> {
        val mut = mutableMapOf<T, MutableCollection<T>>()

        for (vert in graph.keys) {
            mut[vert] = mutableSetOf()
        }

        for (vert in graph.keys) {
            for (adjVert in graph.getValue(vert)) {
                val set = mut.computeIfAbsent(adjVert) { mutableSetOf() }
                set.add(vert)
            }
        }

        return mut.mapValues { (_, v) -> v.toList() }.toMap()
    }

    fun <T> closure(graph: Map<T, Collection<T>>, vertices: Collection<T>): Collection<T> {
        val queue = LinkedList(vertices)
        val visited = mutableSetOf<T>()

        while (!queue.isEmpty()) {
            val vert = queue.remove()
            if (visited.add(vert)) {
                for (adjVert in graph.getValue(vert)) {
                    queue.add(adjVert)
                }
            }
        }

        return visited.toList()
    }
}

private class C_LateInitContext(executor: C_CompilerExecutor) {
    private var internals: Internals? = Internals(executor)

    fun onDestroy(code: () -> Unit) {
        val ints = internals
        check(ints != null)
        ints.uninits.add(code)
    }

    fun checkPass(minPass: C_CompilerPass?, maxPass: C_CompilerPass?) {
        val ints = internals
        if (ints != null) {
            ints.executor.checkPass(minPass, maxPass)
        } else {
            C_CompilerExecutor.checkPass(C_CompilerPass.LAST, minPass, maxPass)
        }
    }

    fun destroy() {
        val ints = internals
        check(ints != null)
        internals = null

        for (code in ints.uninits) {
            code()
        }
        ints.uninits.clear()
    }

    private class Internals(val executor: C_CompilerExecutor) {
        val uninits = mutableListOf<() -> Unit>()
    }

    companion object {
        private val LOCAL_CTX = ThreadLocalContext<C_LateInitContext>()

        fun getContext() = LOCAL_CTX.get()

        fun <T> runInContext(executor: C_CompilerExecutor, code: () -> T): T {
            val ctx = C_LateInitContext(executor)
            try {
                val res = LOCAL_CTX.set(ctx, code)
                return res
            } finally {
                ctx.destroy()
            }
        }
    }
}

sealed class C_LateGetter<T> {
    abstract fun get(): T

    fun <R> transform(transformer: (T) -> R): C_LateGetter<R> = C_TransformingLateGetter(this, transformer)
}

private class C_DirectLateGetter<T>(private val init: C_LateInit<T>): C_LateGetter<T>() {
    override fun get() = init.get()
}

private class C_TransformingLateGetter<T, R>(
        private val getter: C_LateGetter<T>,
        private val transformer: (T) -> R
): C_LateGetter<R>() {
    override fun get(): R {
        val value = getter.get()
        return transformer(value)
    }
}

class C_LateInit<T>(val pass: C_CompilerPass, fallback: T) {
    private val ctx = C_LateInitContext.getContext()
    private var value: T? = null
    private var fallback: T? = fallback

    init {
        ctx.checkPass(null, pass.prev())
        ctx.onDestroy {
            if (value == null) value = this.fallback
            this.fallback = null
        }
    }

    val getter: C_LateGetter<T> = C_DirectLateGetter(this)

    fun set(value: T, allowEarly: Boolean = false) {
        val minPass = if (allowEarly) null else pass
        ctx.checkPass(minPass, pass)
        check(this.value == null) { "value already set" }
        this.value = value
        fallback = null
    }

    fun get(): T {
        ctx.checkPass(pass.next(), null)
        if (value == null) {
            check(fallback != null)
            value = fallback
        }
        return value!!
    }

    companion object {
        fun <T> context(executor: C_CompilerExecutor, code: () -> T): T {
            return C_LateInitContext.runInContext(executor, code)
        }
    }
}

class C_ListBuilder<T> {
    private val list = mutableListOf<T>()
    private var commit: List<T>? = null

    fun add(value: T) {
        check(commit == null)
        list.add(value)
    }

    fun commit(): List<T> {
        if (commit == null) {
            commit = list.toImmList()
        }
        return commit!!
    }
}

class C_UidGen<T>(private val factory: (Long, String) -> T) {
    private var nextUid = 0L

    fun next(name: String): T {
        val uid = nextUid++
        return factory(uid, name)
    }
}

sealed class C_Symbol(val code: String) {
    abstract fun msgNormal(): String

    fun msgCapital(): String = StringUtils.capitalize(msgNormal())

    final override fun toString() = msgNormal()
}

class C_Symbol_Name(private val name: String): C_Symbol(name) {
    override fun msgNormal() = "name '$name'"
}

object C_Symbol_Placeholder: C_Symbol(C_Constants.AT_PLACEHOLDER) {
    override fun msgNormal() = "symbol '$code'"
}

class C_ValidationExecutor(private val manager: C_ValidationManager) {
    fun onValidation(code: () -> Unit) {
        manager.onValidation(code)
    }
}

class C_ValidationManager(private val msgCtx: C_MessageContext) {
    val executor = C_ValidationExecutor(this)

    private val queue = queueOf<() -> Unit>()
    private var done = false

    fun onValidation(code: () -> Unit) {
        check(!done)
        queue.add(code)
    }

    fun execute() {
        check(!done)
        done = true

        for (code in queue) {
            msgCtx.consumeError(code)
        }
    }
}
