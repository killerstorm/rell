package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.parser.ParseException
import com.github.h0tk3y.betterParse.parser.parseToEnd
import net.postchain.rell.*
import net.postchain.rell.model.*
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
    const val MODULE_ARGS_RECORD = "module_args"

    const val TRANSACTION_CLASS = "transaction"
    const val BLOCK_CLASS = "block"

    const val DECIMAL_INT_DIGITS = 131072
    const val DECIMAL_FRAC_DIGITS = 20
    const val DECIMAL_SQL_TYPE_STR = "NUMERIC"
    val DECIMAL_SQL_TYPE = SQLDataType.DECIMAL

    const val DECIMAL_PRECISION = DECIMAL_INT_DIGITS + DECIMAL_FRAC_DIGITS
    val DECIMAL_MIN_VALUE = BigDecimal.ONE.divide(BigDecimal.TEN.pow(DECIMAL_FRAC_DIGITS))
    val DECIMAL_MAX_VALUE = BigDecimal.TEN.pow(DECIMAL_PRECISION).subtract(BigDecimal.ONE)
            .divide(BigDecimal.TEN.pow(DECIMAL_FRAC_DIGITS))
}

class C_ExternalParam(val name: S_Name, val rParam: R_ExternalParam)

object C_Utils {
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

    fun createBlockClass(executor: C_CompilerExecutor, chain: R_ExternalChainRef?): R_Class {
        val attrs = listOf(
                R_Attrib(0, "block_height", R_IntegerType, false, false),
                R_Attrib(1, "block_rid", R_ByteArrayType, false, false),
                R_Attrib(2, "timestamp", R_IntegerType, false, false)
        )
        val sqlMapping = R_ClassSqlMapping_Block(chain)
        return createSysClass(executor, C_Constants.BLOCK_CLASS, chain, sqlMapping, attrs)
    }

    fun createTransactionClass(executor: C_CompilerExecutor, chain: R_ExternalChainRef?, blockClass: R_Class): R_Class {
        val attrs = listOf(
                R_Attrib(0, "tx_rid", R_ByteArrayType, false, false),
                R_Attrib(1, "tx_hash", R_ByteArrayType, false, false),
                R_Attrib(2, "tx_data", R_ByteArrayType, false, false),
                R_Attrib(3, "block", blockClass.type, false, false, true, "block_iid")
        )
        val sqlMapping = R_ClassSqlMapping_Transaction(chain)
        return createSysClass(executor, C_Constants.TRANSACTION_CLASS, chain, sqlMapping, attrs)
    }

    private fun createSysClass(
            executor: C_CompilerExecutor,
            simpleName: String,
            chain: R_ExternalChainRef?,
            sqlMapping: R_ClassSqlMapping,
            attrs: List<R_Attrib>
    ): R_Class {
        val fullName = if (chain == null) simpleName else "external[${chain.name}].$simpleName"
        val names = R_DefinitionNames(simpleName, fullName, fullName)
        val mountName = R_MountName.of(simpleName)

        val flags = R_ClassFlags(
                isObject = false,
                canCreate = false,
                canUpdate = false,
                canDelete = false,
                gtv = false,
                log = false
        )

        val externalCls = if (chain == null) null else R_ExternalClass(chain, false)
        val cls = R_Class(names, mountName, flags, sqlMapping, externalCls)

        val attrMap = attrs.map { it.name to it }.toMap()
        executor.onPass(C_CompilerPass.MEMBERS) {
            cls.setBody(R_ClassBody(listOf(), listOf(), attrMap))
        }

        return cls
    }

    fun crashExpr(type: R_Type, msg: String): R_Expr {
        val fn = R_SysFn_Internal.ThrowCrash(msg)
        return R_SysCallExpr(type, fn, listOf())
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
            val rResExpr = R_SysCallExpr(R_DecimalType, R_SysFn_Decimal.FromInteger, listOf(rExpr))
            return C_RValue(value.pos, rResExpr, value.varFacts())
        }
    }

    fun fullName(namespacePath: String?, name: String) = if (namespacePath == null) name else (namespacePath + "." + name)

    fun nameStr(name: List<S_Name>): String = name.joinToString(".") { it.str }

    fun findFile(rootDir: C_SourceDir, fullPath: C_SourcePath): C_SourceFile {
        val file = rootDir.file(fullPath)
        if (file == null) {
            throw C_CommonError("file_not_found:$fullPath", "File not found: '$fullPath'")
        }
        return file
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
        return C_Error(pos, "$errCode:${dstType.toStrictString()}:${srcType.toStrictString()}",
                "$errMsg: ${srcType.toStrictString()} instead of ${dstType.toStrictString()}")
    }

    fun errMutlipleAttrs(pos: S_Pos, attrs: List<C_ExprContextAttr>, errCode: String, errMsg: String): C_Error {
        val attrsLst = attrs.map { it.cls.alias + "." + it.attrRef.name }
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
        return C_Error(name.pos, "unknown_member:${type.toStrictString()}:${name.str}",
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
        return C_Error(pos, "stmt_update_cant:$name", "Not allowed to update objects of class '$name'")
    }

    fun errCannotDelete(pos: S_Pos, name: String): C_Error {
        return C_Error(pos, "stmt_delete_cant:$name", "Not allowed to delete objects of class '$name'")
    }

    fun errNameConflictAliasLocal(name: S_Name): C_Error {
        val nameStr = name.str
        throw C_Error(name.pos, "expr_name_clsloc:$nameStr",
                "Name '$nameStr' is ambiguous: can be class alias or local variable")
    }

    fun errNameConflict(name: S_Name, otherType: C_DefType, otherPos: S_Pos?): C_Error {
        val baseCode = "name_conflict"
        val baseMsg = "Name conflict"
        return if (otherPos != null) {
            val code = "$baseCode:user:${name.str}:$otherType:$otherPos"
            val msg = "$baseMsg: ${otherType.description} '${name.str}' defined at ${otherPos.strLine()}"
            C_Error(name.pos, code, msg)
        } else {
            val code = "$baseCode:sys:${name.str}:$otherType"
            val msg = "$baseMsg: system ${otherType.description} '${name.str}'"
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
        val otherNameCode = otherEntry.def.appLevelName
        val otherNameMsg = otherEntry.def.simpleName

        val baseCode = "mnt_conflict"
        val commonCode = "${def.appLevelName}:$mountName:${otherEntry.type}:$otherNameCode"
        val baseMsg = "Mount name conflict" + if (chain == null) "" else "(external chain '$chain')"

        if (otherEntry.pos != null) {
            val code = "$baseCode:user:$commonCode:${otherEntry.pos}"
            val msg = "$baseMsg: ${otherEntry.type.description} '$otherNameMsg' has mount name '$mountName' " +
                    "(defined at ${otherEntry.pos.strLine()})"
            return C_Error(pos, code, msg)
        } else {
            val code = "$baseCode:sys:$commonCode"
            val msg = "$baseMsg: system ${otherEntry.type.description} '$otherNameMsg' has mount name '$mountName'"
            return C_Error(pos, code, msg)
        }
    }

    fun errMountConflictSystem(mountName: R_MountName, def: R_Definition, pos: S_Pos): C_Error {
        val code = "mnt_conflict:sys:${def.appLevelName}:$mountName"
        val msg = "Mount name conflict: '$mountName' is a system mount name"
        return C_Error(pos, code, msg)
    }

    fun errTypeNotGtvCompatible(pos: S_Pos, type: R_Type, reason: String?, code: String, msg: String): C_Error {
        val extra = if (reason == null) "" else "; reason: $reason"
        val fullMsg = "$msg is not Gtv-compatible: ${type.toStrictString()}$extra"
        throw C_Error(pos, "$code:${type.toStrictString()}", fullMsg)
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

class C_RecordsStructure(
        val mutable: Set<R_Record>,
        val nonVirtualable: Set<R_Record>,
        val nonGtvFrom: Set<R_Record>,
        val nonGtvTo: Set<R_Record>,
        val graph: Map<R_Record, List<R_Record>>
)

object C_RecordUtils {
    fun buildRecordsStructure(records: Collection<R_Record>): C_RecordsStructure {
        val structMap = records.map { Pair(it, calcRecStruct(it.type)) }.toMap()
        val graph = structMap.mapValues { (_, v) -> v.dependencies.toList() }
        val mutable = structMap.filter { (_, v) -> v.directFlags.mutable }.keys
        val nonVirtualable = structMap.filter { (_, v) -> !v.directFlags.virtualable }.keys
        val nonGtvFrom = structMap.filter { (_, v) -> !v.directFlags.gtv.fromGtv }.keys
        val nonGtvTo = structMap.filter { (_, v) -> !v.directFlags.gtv.toGtv }.keys
        return C_RecordsStructure(mutable, nonVirtualable, nonGtvFrom, nonGtvTo, graph)
    }

    private fun calcRecStruct(type: R_Type): RecStruct {
        val flags = mutableListOf(type.directFlags())
        val deps = mutableSetOf<R_Record>()

        for (subType in type.componentTypes()) {
            val subStruct = discoverRecStruct(subType)
            flags.add(subStruct.directFlags)
            deps.addAll(subStruct.dependencies)
        }

        val resFlags = R_TypeFlags.combine(flags)
        return RecStruct(resFlags, deps.toImmSet())
    }

    private fun discoverRecStruct(type: R_Type): RecStruct {
        if (type is R_RecordType) {
            return RecStruct(type.directFlags(), setOf(type.record))
        }
        return calcRecStruct(type)
    }

    private class RecStruct(val directFlags: R_TypeFlags, val dependencies: Set<R_Record>)
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
}

class C_LateInit<T>(private val pass: C_CompilerPass, fallback: T) {
    private val ctx = LOCAL_CTX.get()
    private var value: T? = null
    private var fallback: T? = fallback

    init {
        ctx.checkPass(null, pass.prev())
        ctx.onDestroy { this.fallback = null }
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
        private val LOCAL_CTX = ThreadLocalContext<C_LateInitContext>()

        fun <T> context(executor: C_CompilerExecutor, code: () -> T): T {
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
