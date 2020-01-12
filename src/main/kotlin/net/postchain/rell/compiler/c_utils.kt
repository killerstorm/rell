package net.postchain.rell.compiler

import com.github.h0tk3y.betterParse.parser.ParseException
import com.github.h0tk3y.betterParse.parser.parseToEnd
import net.postchain.rell.*
import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.parser.S_Grammar
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Error
import org.jooq.impl.SQLDataType
import java.math.BigDecimal
import java.util.*

class C_CommonError(val code: String, val msg: String): RuntimeException(msg)

class C_Error: RuntimeException {
    val pos: S_Pos
    val code: String
    val errMsg: String

    constructor(pos: S_Pos, code: String, errMsg: String): super("$pos $errMsg") {
        this.pos = pos
        this.code = code
        this.errMsg = errMsg
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

class C_ExternalParam(val name: S_Name, val type: R_Type?) {
    fun nameCode(index: Int) = "$index:${name.str}"
    fun nameMsg(index: Int) = "'${name.str}'"
}

object C_Utils {
    val ERROR_STATEMENT = R_ExprStatement(crashExpr(R_UnitType))

    fun toDbExpr(pos: S_Pos, rExpr: R_Expr): Db_Expr {
        val type = rExpr.type
        if (!type.sqlAdapter.isSqlCompatible()) {
            throw C_Errors.errExprNoDb(pos, type)
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

    fun effectiveMemberType(formalType: R_Type, safe: Boolean): R_Type {
        if (!safe || formalType is R_NullableType || formalType == R_NullType) {
            return formalType
        } else {
            return R_NullableType(formalType)
        }
    }

    fun checkUnitType(pos: S_Pos, type: R_Type, errCode: String, errMsg: String) {
        if (type == R_UnitType) {
            throw C_Error(pos, errCode, errMsg)
        }
    }

    fun checkMapKeyType(pos: S_Pos, type: R_Type) {
        checkMapKeyType0(pos, type, "expr_map_keytype", "map key")
    }

    fun checkSetElementType(pos: S_Pos, type: R_Type) {
        checkMapKeyType0(pos, type, "expr_set_type", "set element")
    }

    private fun checkMapKeyType0(pos: S_Pos, type: R_Type, errCode: String, errMsg: String) {
        if (type.completeFlags().mutable) {
            val typeStr = type.toStrictString()
            throw C_Error(pos, "$errCode:$typeStr", "Mutable type cannot be used as $errMsg: $typeStr")
        }
    }

    fun createBlockEntity(executor: C_CompilerExecutor, chain: R_ExternalChainRef?): R_Entity {
        val attrs = listOf(
                R_Attrib(0, "block_height", R_IntegerType, false, false),
                R_Attrib(1, "block_rid", R_ByteArrayType, false, false),
                R_Attrib(2, "timestamp", R_IntegerType, false, false)
        )
        val sqlMapping = R_EntitySqlMapping_Block(chain)
        return createSysEntity(executor, C_Constants.BLOCK_ENTITY, chain, sqlMapping, attrs)
    }

    fun createTransactionEntity(executor: C_CompilerExecutor, chain: R_ExternalChainRef?, blockEntity: R_Entity): R_Entity {
        val attrs = listOf(
                R_Attrib(0, "tx_rid", R_ByteArrayType, false, false),
                R_Attrib(1, "tx_hash", R_ByteArrayType, false, false),
                R_Attrib(2, "tx_data", R_ByteArrayType, false, false),
                R_Attrib(3, "block", blockEntity.type, false, false, true, "block_iid")
        )
        val sqlMapping = R_EntitySqlMapping_Transaction(chain)
        return createSysEntity(executor, C_Constants.TRANSACTION_ENTITY, chain, sqlMapping, attrs)
    }

    private fun createSysEntity(
            executor: C_CompilerExecutor,
            simpleName: String,
            chain: R_ExternalChainRef?,
            sqlMapping: R_EntitySqlMapping,
            attrs: List<R_Attrib>
    ): R_Entity {
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
        val entity = R_Entity(names, mountName, flags, sqlMapping, externalEntity)

        val attrMap = attrs.map { it.name to it }.toMap()
        executor.onPass(C_CompilerPass.MEMBERS) {
            entity.setBody(R_EntityBody(listOf(), listOf(), attrMap))
        }

        return entity
    }

    fun createSysQuery(executor: C_CompilerExecutor, simpleName: String, type: R_Type, fn: R_SysFunction): R_Query {
        val moduleName = R_ModuleName.of("rell")
        val names = createDefNames(moduleName, null, null, listOf(simpleName))

        val mountName = R_MountName(moduleName.parts + R_Name.of(simpleName))
        val query = R_Query(names, mountName)

        executor.onPass(C_CompilerPass.EXPRESSIONS) {
            val body = R_SysQueryBody(listOf(), fn)
            query.setInternals(type, body)
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

    fun createSysCallExpr(type: R_Type, fn: R_SysFunction, args: List<R_Expr>, name: S_String): R_Expr {
        return createSysCallExpr(type, fn, args, name.pos, name.str)
    }

    fun createSysCallExpr(type: R_Type, fn: R_SysFunction, args: List<R_Expr>, qualifiedName: List<S_Name>): R_Expr {
        val nameStr = nameStr(qualifiedName)
        return createSysCallExpr(type, fn, args, qualifiedName[0].pos, nameStr)
    }

    fun createSysCallExpr(type: R_Type, fn: R_SysFunction, args: List<R_Expr>, pos: S_Pos, nameStr: String): R_Expr {
        val rCallExpr = R_SysCallExpr(type, fn, args, nameStr)
        val filePos = pos.toFilePos()
        return R_StackTraceExpr(rCallExpr, filePos)
    }

    fun crashExpr(type: R_Type, msg: String = "Compilation error"): R_Expr {
        val arg = R_ConstantExpr.makeText(msg)
        return R_SysCallExpr(type, R_SysFn_Internal.Crash, listOf(arg), null)
    }

    fun integerToDecimalPromotion(value: C_Value): C_Value {
        val type = value.type()
        if (type == R_DecimalType) {
            return value
        }
        check(type == R_IntegerType) { "Expected $R_DecimalType, but was $type" }

        if (value.isDb()) {
            val dbExpr = value.toDbExpr()
            val dbResExpr = Db_CallExpr(R_DecimalType, Db_SysFn_Decimal.FromInteger, listOf(dbExpr))
            return C_DbValue(value.pos, dbResExpr, value.varFacts())
        } else {
            val rExpr = value.toRExpr()
            val rResExpr = createSysCallExpr(R_DecimalType, R_SysFn_Decimal.FromInteger, listOf(rExpr), value.pos, "decimal")
            return C_RValue(value.pos, rResExpr, value.varFacts())
        }
    }

    fun fullName(namespacePath: String?, name: String): String {
        return if (namespacePath == null) name else (namespacePath + "." + name)
    }

    fun nameStr(name: List<S_Name>): String = name.joinToString(".") { it.str }
    fun namePosStr(name: List<S_Name>): S_String = S_String(name[0].pos, nameStr(name))

    fun <T> evaluate(pos: S_Pos, code: () -> T): T {
        try {
            val v = code()
            return v
        } catch (e: Rt_Error) {
            throw C_Error(pos, "eval_fail:${e.code}", e.message ?: "Evaluation failed")
        } catch (e: Throwable) {
            throw C_Error(pos, "eval_fail:${e.javaClass.canonicalName}", "Evaluation failed")
        }
    }

    fun appLevelName(module: C_ModuleKey, path: List<S_Name>): String {
        val moduleName = module.name.str()
        val qualifiedName = nameStr(path)
        return R_DefinitionPos.appLevelName(moduleName, qualifiedName)
    }
}

object C_Parser {
    private val currentFileLocal = ThreadLocalContext(C_SourcePath(listOf("?")))

    fun parse(filePath: C_SourcePath, sourceCode: String): S_RellFile {
        // The syntax error position returned by the parser library is misleading: if there is an error in the middle
        // of an operation, it returns the position of the beginning of the operation.
        // Following workaround handles this by tracking the position of the farthest reached token (seems to work fine).

        var maxRow = 1
        var maxCol = 1

        val tokenSeq = S_Grammar.tokenizer.tokenize(sourceCode) {
            if (!it.type.ignored) {
                maxRow = it.row
                maxCol = it.column
            }
        }

        try {
            val ast = overrideCurrentFile(filePath) {
                S_Grammar.parseToEnd(tokenSeq)
            }
            return ast
        } catch (e: ParseException) {
            val pos = S_BasicPos(filePath, maxRow, maxCol)
            throw C_Error(pos, "syntax", "Syntax error")
        }
    }

    private fun <T> overrideCurrentFile(file: C_SourcePath, code: () -> T): T {
        return currentFileLocal.set(file, code)
    }

    fun currentFile() = currentFileLocal.get()
}

object C_Errors {
    fun errTypeMismatch(pos: S_Pos, srcType: R_Type, dstType: R_Type, errCode: String, errMsg: String): C_Error {
        return C_Error(pos, "$errCode:[${dstType.toStrictString()}]:[${srcType.toStrictString()}]",
                "$errMsg: ${srcType.toStrictString()} instead of ${dstType.toStrictString()}")
    }

    fun errMultipleAttrs(pos: S_Pos, attrs: List<C_ExprContextAttr>, errCode: String, errMsg: String): C_Error {
        val attrsLst = attrs.map { it.entity.alias + "." + it.attrRef.name }
        return C_Error(pos, "$errCode:${attrsLst.joinToString(",")}", "$errMsg: ${attrsLst.joinToString()}")
    }

    fun errUnknownName(name: S_Name): C_Error {
        return C_Error(name.pos, "unknown_name:${name.str}", "Unknown name: '${name.str}'")
    }

    fun errUnknownName(baseType: R_Type, name: S_Name): C_Error {
        val baseName = baseType.name
        return C_Error(name.pos, "unknown_name:$baseName.${name.str}", "Unknown name: '$baseName.${name.str}'")
    }

    fun errUnknownName(baseName: List<S_Name>, name: S_Name): C_Error {
        val fullName = baseName + listOf(name)
        val nameStr = C_Utils.nameStr(fullName)
        return C_Error(name.pos, "unknown_name:$nameStr", "Unknown name: '$nameStr'")
    }

    fun errUnknownAttr(name: S_Name): C_Error {
        val nameStr = name.str
        return C_Error(name.pos, "expr_attr_unknown:$nameStr", "Unknown attribute: '$nameStr'")
    }

    fun errUnknownFunction(name: S_Name): C_Error {
        return C_Error(name.pos, "unknown_fn:${name.str}", "Unknown function: '${name.str}'")
    }

    fun errUnknownMember(type: R_Type, name: S_Name): C_Error {
        return C_Error(name.pos, "unknown_member:[${type.toStrictString()}]:${name.str}",
                "Type ${type.toStrictString()} has no member '${name.str}'")

    }

    fun errFunctionNoSql(pos: S_Pos, name: String): C_Error {
        return C_Error(pos, "expr_call_nosql:$name", "Function '$name' cannot be converted to SQL")
    }

    fun errBadDestination(pos: S_Pos): C_Error {
        return C_Error(pos, "expr_bad_dst", "Invalid assignment destination")
    }

    fun errBadDestination(name: S_Name): C_Error {
        return C_Error(name.pos, "expr_bad_dst:${name.str}", "Cannot modify '${name.str}'")
    }

    fun errAttrNotMutable(pos: S_Pos, name: String): C_Error {
        return C_Error(pos, "update_attr_not_mutable:$name", "Attribute '$name' is not mutable")
    }

    fun errExprNoDb(pos: S_Pos, type: R_Type): C_Error {
        val typeStr = type.toStrictString()
        return C_Error(pos, "expr_nosql:$typeStr", "Value of type $typeStr cannot be converted to SQL")
    }

    fun errExprDbNotAllowed(pos: S_Pos): C_Error {
        return C_Error(pos, "expr_sqlnotallowed", "Database expression not allowed here")
    }

    fun errCannotUpdate(pos: S_Pos, name: String): C_Error {
        return C_Error(pos, "stmt_update_cant:$name", "Not allowed to update objects of entity '$name'")
    }

    fun errCannotDelete(pos: S_Pos, name: String): C_Error {
        return C_Error(pos, "stmt_delete_cant:$name", "Not allowed to delete objects of entity '$name'")
    }

    fun errNameConflictAliasLocal(name: S_Name): C_Error {
        val nameStr = name.str
        throw C_Error(name.pos, "expr_name_entity_local:$nameStr",
                "Name '$nameStr' is ambiguous: can be entity alias or local variable")
    }

    fun errNameConflict(name: S_Name, otherType: C_DeclarationType, otherPos: S_Pos?): C_Error {
        val baseCode = "name_conflict"
        val baseMsg = "Name conflict"
        return if (otherPos != null) {
            val code = "$baseCode:user:${name.str}:$otherType:$otherPos"
            val msg = "$baseMsg: ${otherType.msg} '${name.str}' defined at ${otherPos.strLine()}"
            C_Error(name.pos, code, msg)
        } else {
            val code = "$baseCode:sys:${name.str}:$otherType"
            val msg = "$baseMsg: system ${otherType.msg} '${name.str}'"
            C_Error(name.pos, code, msg)
        }
    }

    fun errMountConflict(
            chain: String?,
            mountName: R_MountName,
            def: R_Definition,
            pos: S_Pos,
            otherEntry: C_MntEntry
    ): C_Error {
        val baseCode = "mnt_conflict"
        val commonCode = "[${def.appLevelName}]:$mountName:${otherEntry.type}:[${otherEntry.def.appLevelName}]"
        val baseMsg = "Mount name conflict" + if (chain == null) "" else "(external chain '$chain')"
        val otherNameMsg = otherEntry.def.simpleName

        if (otherEntry.pos != null) {
            val code = "$baseCode:user:$commonCode:${otherEntry.pos}"
            val msg = "$baseMsg: ${otherEntry.type.msg} '$otherNameMsg' has mount name '$mountName' " +
                    "(defined at ${otherEntry.pos.strLine()})"
            return C_Error(pos, code, msg)
        } else {
            val code = "$baseCode:sys:$commonCode"
            val msg = "$baseMsg: system ${otherEntry.type.msg} '$otherNameMsg' has mount name '$mountName'"
            return C_Error(pos, code, msg)
        }
    }

    fun errMountConflictSystem(mountName: R_MountName, def: R_Definition, pos: S_Pos): C_Error {
        val code = "mnt_conflict:sys:[${def.appLevelName}]:$mountName"
        val msg = "Mount name conflict: '$mountName' is a system mount name"
        return C_Error(pos, code, msg)
    }
}

object C_GraphUtils {
    /** Returns some, not all cycles (at least one cycle for each cyclic vertex). */
    fun <T> findCycles(graph: Map<T, Collection<T>>): List<List<T>> {
        class VertEntry<T>(val vert: T, val enter: Boolean, val parent: VertEntry<T>?)

        val queue = LinkedList<VertEntry<T>>()
        val visiting = mutableSetOf<T>()
        val visited = mutableSetOf<T>()
        val cycles = mutableListOf<List<T>>()

        for (vert in graph.keys) {
            queue.add(VertEntry(vert, true, null))
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
                val cycle = mutableListOf<T>()
                while (true) {
                    cycle.add(cycleEntry.vert)
                    cycleEntry = cycleEntry.parent
                    check(cycleEntry != null)
                    if (cycleEntry.vert == entry.vert) break
                }
                cycles.add(cycle.toList())
                continue
            }

            queue.addFirst(VertEntry(entry.vert, false, entry.parent))
            visiting.add(entry.vert)

            for (adjVert in graph.getValue(entry.vert)) {
                queue.addFirst(VertEntry(adjVert, true, entry))
            }
        }

        return cycles.toList()
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
                mut.getValue(adjVert).add(vert)
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

class C_StructsInfo(
        val mutable: Set<R_Struct>,
        val nonVirtualable: Set<R_Struct>,
        val nonGtvFrom: Set<R_Struct>,
        val nonGtvTo: Set<R_Struct>,
        val graph: Map<R_Struct, List<R_Struct>>
)

object C_StructUtils {
    fun buildStructsInfo(structs: Collection<R_Struct>): C_StructsInfo {
        val infoMap = structs.map { Pair(it, calcStructInfo(it.type)) }.toMap()
        val graph = infoMap.mapValues { (_, v) -> v.dependencies.toList() }
        val mutable = infoMap.filter { (_, v) -> v.directFlags.mutable }.keys
        val nonVirtualable = infoMap.filter { (_, v) -> !v.directFlags.virtualable }.keys
        val nonGtvFrom = infoMap.filter { (_, v) -> !v.directFlags.gtv.fromGtv }.keys
        val nonGtvTo = infoMap.filter { (_, v) -> !v.directFlags.gtv.toGtv }.keys
        return C_StructsInfo(mutable, nonVirtualable, nonGtvFrom, nonGtvTo, graph)
    }

    private fun calcStructInfo(type: R_Type): StructInfo {
        val flags = mutableListOf(type.directFlags())
        val deps = mutableSetOf<R_Struct>()

        for (subType in type.componentTypes()) {
            val subStruct = discoverStructInfo(subType)
            flags.add(subStruct.directFlags)
            deps.addAll(subStruct.dependencies)
        }

        val resFlags = R_TypeFlags.combine(flags)
        return StructInfo(resFlags, deps.toImmSet())
    }

    private fun discoverStructInfo(type: R_Type): StructInfo {
        if (type is R_StructType) {
            return StructInfo(type.directFlags(), setOf(type.struct))
        }
        return calcStructInfo(type)
    }

    private class StructInfo(val directFlags: R_TypeFlags, val dependencies: Set<R_Struct>)
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

class C_LateInit<T>(private val pass: C_CompilerPass, fallback: T) {
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

    val getter: Getter<T> = { get() }
    val setter: Setter<T> = { set(it) }

    fun set(value: T) {
        ctx.checkPass(pass, pass)
        check(this.value == null)
        this.value = value
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
