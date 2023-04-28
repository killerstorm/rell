/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.utils

import com.github.h0tk3y.betterParse.parser.ParseException
import com.github.h0tk3y.betterParse.parser.Parser
import com.github.h0tk3y.betterParse.parser.parseToEnd
import net.postchain.rell.compiler.ast.S_BasicPos
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_RellFile
import net.postchain.rell.compiler.ast.S_ReplCommand
import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.def.C_AttrUtils
import net.postchain.rell.compiler.base.def.C_SysAttribute
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.module.C_ModuleKey
import net.postchain.rell.compiler.parser.RellTokenizerException
import net.postchain.rell.compiler.parser.RellTokenizerState
import net.postchain.rell.compiler.parser.S_Grammar
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_ParameterDefaultValueExpr
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.runtime.utils.toGtv
import net.postchain.rell.tools.api.IdeFilePath
import net.postchain.rell.utils.*
import java.util.*

typealias C_CodeMsgSupplier = () -> C_CodeMsg

class C_CodeMsg(val code: String, val msg: String) {
    override fun toString() = code
}

infix fun String.toCodeMsg(that: String): C_CodeMsg = C_CodeMsg(this, that)

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
        fun stop(err: C_PosCodeMsg) = C_Error(err.pos, err.code, err.msg)
        fun other(pos: S_Pos, code: String, errMsg: String) = C_Error(pos, code, errMsg)
    }
}

object C_Constants {
    const val LOG_ANNOTATION = "log"
    const val MODULE_ARGS_STRUCT = "module_args"

    const val AT_PLACEHOLDER = "$"

    const val TRANSACTION_ENTITY = "transaction"
    const val BLOCK_ENTITY = "block"

    val TRANSACTION_ENTITY_RNAME = R_Name.of(TRANSACTION_ENTITY)
    val BLOCK_ENTITY_RNAME = R_Name.of(BLOCK_ENTITY)
}

// Operations and queries defined in Postchain (StandardOpsGTXModule). Shall be reserved (not allowed) in Rell.
object C_ReservedMountNames {
    val OPERATIONS: Set<R_MountName> = listOf(
        "__nop",
        "nop",
        "timeb",
    ).map { R_MountName.of(it) }.toImmSet()

    val QUERIES: Set<R_MountName> = listOf(
        "last_block_info",
        "tx_confirmation_time",
    ).map { R_MountName.of(it) }.toImmSet()
}

class C_ParameterDefaultValue(
        private val pos: S_Pos,
        private val paramName: R_Name,
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
    fun effectiveMemberType(formalType: R_Type, safe: Boolean): R_Type {
        if (!safe || formalType is R_NullableType || formalType == R_NullType) {
            return formalType
        } else {
            return R_NullableType(formalType)
        }
    }

    fun checkUnitType(pos: S_Pos, type: R_Type, errSupplier: C_CodeMsgSupplier) {
        C_Errors.check(type != R_UnitType, pos, errSupplier)
    }

    fun checkUnitType(msgCtx: C_MessageContext, pos: S_Pos, type: R_Type, errSupplier: C_CodeMsgSupplier): Boolean {
        if (type == R_UnitType) {
            val codeMsg = errSupplier()
            msgCtx.error(pos, codeMsg)
            return false
        }
        return true
    }

    fun checkMapKeyType(ctx: C_DefinitionContext, pos: S_Pos, type: R_Type) {
        checkMapKeyType0(ctx.appCtx, pos, type, "expr_map_keytype", "as a map key")
    }

    fun checkSetElementType(ctx: C_DefinitionContext, pos: S_Pos, type: R_Type) {
        checkMapKeyType0(ctx.appCtx, pos, type, "expr_set_type", "as a set element")
    }

    fun checkGroupValueType(appCtx: C_AppContext, pos: S_Pos, type: R_Type) {
        checkMapKeyType0(appCtx, pos, type, "expr_at_group_type", "for grouping")
    }

    private fun checkMapKeyType0(appCtx: C_AppContext, pos: S_Pos, type: R_Type, errCode: String, errMsg: String) {
        appCtx.executor.onPass(C_CompilerPass.VALIDATION) {
            if (type.completeFlags().mutable) {
                val typeStr = type.strCode()
                appCtx.msgCtx.error(pos, "$errCode:$typeStr", "Mutable type cannot be used $errMsg: $typeStr")
            }
        }
    }

    fun createBlockEntity(appCtx: C_AppContext, chain: R_ExternalChainRef?): R_EntityDefinition {
        val attrs = listOf(
                C_SysAttribute("block_height", R_IntegerType, isKey = true),
                C_SysAttribute("block_rid", R_ByteArrayType, isKey = true),
                C_SysAttribute("timestamp", R_IntegerType)
        )
        val sqlMapping = R_EntitySqlMapping_Block(chain)
        return createSysEntity(appCtx, C_Constants.BLOCK_ENTITY_RNAME, chain, sqlMapping, attrs)
    }

    fun createTransactionEntity(appCtx: C_AppContext, chain: R_ExternalChainRef?, blockEntity: R_EntityDefinition): R_EntityDefinition {
        val attrs = listOf(
                C_SysAttribute("tx_rid", R_ByteArrayType, isKey = true),
                C_SysAttribute("tx_hash", R_ByteArrayType),
                C_SysAttribute("tx_data", R_ByteArrayType),
                C_SysAttribute("block", blockEntity.type, sqlMapping = "block_iid")
        )
        val sqlMapping = R_EntitySqlMapping_Transaction(chain)
        return createSysEntity(appCtx, C_Constants.TRANSACTION_ENTITY_RNAME, chain, sqlMapping, attrs)
    }

    private fun createSysEntity(
            appCtx: C_AppContext,
            simpleName: R_Name,
            chain: R_ExternalChainRef?,
            sqlMapping: R_EntitySqlMapping,
            attrs: List<C_SysAttribute>
    ): R_EntityDefinition {
        val moduleKey = R_ModuleKey(C_LibUtils.DEFAULT_MODULE, chain?.name)
        val cDefBase = createDefBase(moduleKey, C_StringQualifiedName.of(simpleName.str))
        val defBase = cDefBase.rBase(R_CallFrame.NONE_INIT_FRAME_GETTER)

        val mountName = R_MountName.of(simpleName.str)

        val flags = R_EntityFlags(
                isObject = false,
                canCreate = false,
                canUpdate = false,
                canDelete = false,
                gtv = true,
                log = false
        )

        val externalEntity = if (chain == null) null else R_ExternalEntity(chain, false)

        val entity = createEntity(
                appCtx,
                C_DefinitionType.ENTITY,
                defBase,
                simpleName,
                mountName,
                flags,
                sqlMapping,
                externalEntity
        )

        val rAttrs = attrs.mapIndexed { i, attr -> attr.compile(i, true) }
        val rAttrMap = rAttrs.map { it.rName to it }.toMap()

        appCtx.executor.onPass(C_CompilerPass.MEMBERS) {
            setEntityBody(entity, R_EntityBody(listOf(), listOf(), rAttrMap))
        }

        return entity
    }

    fun createEntity(
            appCtx: C_AppContext,
            defType: C_DefinitionType,
            defBase: R_DefinitionBase,
            name: R_Name,
            mountName: R_MountName,
            flags: R_EntityFlags,
            sqlMapping: R_EntitySqlMapping,
            externalEntity: R_ExternalEntity?
    ): R_EntityDefinition {
        val mirrorStructs = createMirrorStructs(appCtx, defBase, defType)
        return R_EntityDefinition(defBase, name, mountName, flags, sqlMapping, externalEntity, mirrorStructs)
    }

    fun createMirrorStructs(
            appCtx: C_AppContext,
            defBase: R_DefinitionBase,
            defType: C_DefinitionType,
            operation: R_MountName? = null,
    ): R_MirrorStructs {
        val res = R_MirrorStructs(defBase, defType.name, operation)
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

        val structAttrs = body.attributes.mapValues { (_, attr) ->
            val ideKind = C_AttrUtils.getIdeSymbolKind(false, mutable, attr.keyIndexKind)
            val ideInfo = attr.ideInfo.update(kind = ideKind, defId = null)
            attr.copy(mutable = mutable, ideInfo = ideInfo)
        }

        struct.setAttributes(structAttrs)
    }

    fun createSysStruct(name: String, attrs: List<C_SysAttribute>): R_Struct {
        val rStruct = R_Struct(
                name,
                name.toGtv(),
                mirrorStructs = null,
                initFrameGetter = R_CallFrame.NONE_INIT_FRAME_GETTER,
        )
        val rAttrs = attrs.mapIndexed { i, attr -> attr.name to attr.compile(i, false) }.toMap().toImmMap()
        rStruct.setAttributes(rAttrs)
        return rStruct
    }

    fun createSysStruct(name: String, vararg attrs: C_SysAttribute): R_Struct {
        return createSysStruct(name, attrs.toList())
    }

    private val RELL_MODULE_NAME = R_ModuleName.of("rell")

    fun createSysQuery(executor: C_CompilerExecutor, simpleName: String, type: R_Type, fn: R_SysFunction): R_QueryDefinition {
        val moduleName = RELL_MODULE_NAME
        val moduleKey = R_ModuleKey(moduleName, null)
        val cDefBase = createDefBase(moduleKey, C_StringQualifiedName.of(simpleName))

        val mountName = R_MountName(moduleName.parts + R_Name.of(simpleName))

        val defBase = cDefBase.rBase(R_CallFrame.NONE_INIT_FRAME_GETTER)
        val query = R_QueryDefinition(defBase, mountName)

        executor.onPass(C_CompilerPass.EXPRESSIONS) {
            val body = R_SysQueryBody(type, listOf(), fn)
            query.setBody(body)
        }

        return query
    }

    fun createDefBase(module: R_ModuleKey, qualifiedName: C_StringQualifiedName): C_DefinitionBase {
        val cDefName = createDefName(module, qualifiedName)
        val defName = cDefName.toRDefName()
        val defId = R_DefinitionId(defName.module, defName.qualifiedName)
        return C_DefinitionBase(defId, cDefName, defName)
    }

    private fun createDefName(module: R_ModuleKey, qualifiedName: C_StringQualifiedName): C_DefinitionName {
        return C_DefinitionName(module.str(), qualifiedName)
    }

    fun fullName(namespacePath: String?, name: String): String {
        return if (namespacePath == null) name else "$namespacePath.$name"
    }

    fun appLevelName(module: C_ModuleKey, name: C_QualifiedName): String {
        val nameStr = name.str()
        return R_DefinitionName.appLevelName(module.keyStr(), nameStr)
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
            val fullMsg = "$errMsg is not Gtv-compatible: ${type.strCode()}"
            msgCtx.error(pos, "$errCode:${type.strCode()}", fullMsg)
        }
    }

    fun getFullNameLazy(type: R_Type, name: R_Name): LazyString {
        return LazyString.of {
            val baseType = C_Types.removeNullable(type)
            "${baseType.strCode()}.$name"
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

data class C_ParserFilePath(val sourcePath: C_SourcePath, val idePath: IdeFilePath) {
    override fun toString() = sourcePath.toString()
}

object C_Parser {
    private val currentFileLocal = let {
        val sourcePath = C_SourcePath.parse("?")
        val idePath = IdeSourcePathFilePath(sourcePath)
        ThreadLocalContext(C_ParserFilePath(sourcePath, idePath))
    }

    private val replParserPath: C_ParserFilePath = let {
        val path = C_SourcePath.parse("<console>")
        C_ParserFilePath(path, IdeSourcePathFilePath(path))
    }

    fun parse(filePath: C_SourcePath, idePath: IdeFilePath, sourceCode: String): S_RellFile {
        val parserPath = C_ParserFilePath(filePath, idePath)
        val res = parse0(parserPath, sourceCode, S_Grammar)
        val ast = res.getAst()
        return ast
    }

    fun parseRepl(code: String): S_ReplCommand {
        val res = parse0(replParserPath, code, S_Grammar.replParser)
        val ast = res.getAst()
        return ast
    }

    fun checkEofErrorRepl(code: String): C_Error? {
        val path = C_SourcePath.parse("<console>")
        val res = parse0(replParserPath, code, S_Grammar.replParser)
        return when (res) {
            is C_SuccessParserResult -> null
            is C_ErrorParserResult -> if (res.eof) res.error else null
        }
    }

    private fun <T> parse0(filePath: C_ParserFilePath, sourceCode: String, parser: Parser<T>): C_ParserResult<T> {
        // The syntax error position returned by the parser library is misleading: if there is an error in the middle
        // of an operation, it returns the position of the beginning of the operation.
        // Following workaround handles this by tracking the position of the farthest reached token (seems to work fine).

        val state = RellTokenizerState()
        val tokenSeq = S_Grammar.tokenizer.tokenize(sourceCode, state)

        return try {
            val ast = overrideCurrentFile(filePath) {
                parser.parseToEnd(tokenSeq)
            }
            C_SuccessParserResult(ast)
        } catch (e: RellTokenizerException) {
            val error = e.toCError()
            C_ErrorParserResult(error, e.eof)
        } catch (e: ParseException) {
            val pos = S_BasicPos(filePath, state.lastRow, state.lastCol)
            val error = C_Error.other(pos, "syntax", "Syntax error")
            C_ErrorParserResult(error, state.lastEof)
        }
    }

    private fun <T> overrideCurrentFile(file: C_ParserFilePath, code: () -> T): T {
        return currentFileLocal.set(file, code)
    }

    fun currentFile(): C_ParserFilePath = currentFileLocal.get()
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

    fun <T> closure(vertices: Collection<T>, graph: (T) -> Collection<T>): Collection<T> {
        val queue = LinkedList(vertices)
        val visited = mutableSetOf<T>()

        while (!queue.isEmpty()) {
            val vert = queue.remove()
            if (visited.add(vert)) {
                val adjVerts = graph(vert)
                for (adjVert in adjVerts) {
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

        fun inContext() = LOCAL_CTX.getOpt() != null
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

    companion object {
        fun <T> const(value: T): C_LateGetter<T> = C_ConstLateGetter(value)
    }
}

private class C_ConstLateGetter<T>(private val value: T): C_LateGetter<T>() {
    override fun get() = value
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
        fun inContext(): Boolean = C_LateInitContext.inContext()

        fun <T> context(executor: C_CompilerExecutor, code: () -> T): T {
            return C_LateInitContext.runInContext(executor, code)
        }

        fun checkPass(minPass: C_CompilerPass?, maxPass: C_CompilerPass?) {
            val ctx = C_LateInitContext.getContext()
            ctx.checkPass(minPass, maxPass)
        }

        fun checkPass(pass: C_CompilerPass) {
            checkPass(pass, pass)
        }
    }
}

class C_ListBuilder<T>(proto: List<T> = immListOf()) {
    private val list = proto.toMutableList()
    private var commit: List<T>? = null

    val size: Int get() = list.size

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

    fun msgCapital(): String = msgNormal().capitalize()

    final override fun toString() = msgNormal()
}

class C_Symbol_Name(private val name: R_Name): C_Symbol(name.str) {
    override fun msgNormal() = "name '$name'"
}

object C_Symbol_Placeholder: C_Symbol(C_Constants.AT_PLACEHOLDER) {
    override fun msgNormal() = "symbol '$code'"
}
