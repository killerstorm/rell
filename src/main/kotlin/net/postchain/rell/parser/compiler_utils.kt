package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.parser.ParseException
import com.github.h0tk3y.betterParse.parser.parseToEnd
import net.postchain.rell.CommonUtils
import net.postchain.rell.model.*
import org.jooq.impl.SQLDataType
import java.math.BigDecimal
import java.util.*

class C_CommonError(val code: String, val msg: String): RuntimeException(msg)

object C_Constants {
    const val DECIMAL_INT_DIGITS = 131072
    const val DECIMAL_FRAC_DIGITS = 20
    const val DECIMAL_SQL_TYPE_STR = "NUMERIC"
    val DECIMAL_SQL_TYPE = SQLDataType.DECIMAL

    const val DECIMAL_PRECISION = DECIMAL_INT_DIGITS + DECIMAL_FRAC_DIGITS

    val DECIMAL_MIN_VALUE = BigDecimal.ONE.divide(BigDecimal.TEN.pow(DECIMAL_FRAC_DIGITS))
    val DECIMAL_MAX_VALUE = BigDecimal.TEN.pow(DECIMAL_PRECISION).subtract(BigDecimal.ONE)
            .divide(BigDecimal.TEN.pow(DECIMAL_FRAC_DIGITS))
}

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

    fun createBlockClass(namespace: String?, chain: R_ExternalChain?): R_Class {
        val attrs = listOf(
                R_Attrib(0, "block_height", R_IntegerType, false, false),
                R_Attrib(1, "block_rid", R_ByteArrayType, false, false),
                R_Attrib(2, "timestamp", R_IntegerType, false, false)
        )
        val sqlMapping = R_ClassSqlMapping_Block(chain)
        return createSysClass(namespace, C_Defs.BLOCK_CLASS, chain, sqlMapping, attrs)
    }

    fun createTransactionClass(namespace: String?, chain: R_ExternalChain?, blockClass: R_Class): R_Class {
        val attrs = listOf(
                R_Attrib(0, "tx_rid", R_ByteArrayType, false, false),
                R_Attrib(1, "tx_hash", R_ByteArrayType, false, false),
                R_Attrib(2, "tx_data", R_ByteArrayType, false, false),
                R_Attrib(3, "block", R_ClassType(blockClass), false, false, true, "block_iid")
        )
        val sqlMapping = R_ClassSqlMapping_Transaction(chain)
        return createSysClass(namespace, C_Defs.TRANSACTION_CLASS, chain, sqlMapping, attrs)
    }

    private fun createSysClass(
            namespace: String?,
            name: String,
            chain: R_ExternalChain?,
            sqlMapping: R_ClassSqlMapping,
            attrs: List<R_Attrib>
    ): R_Class {
        val fullName = C_Utils.fullName(namespace, name)
        val flags = R_ClassFlags(false, false, false, false, false, false)
        val externalCls = if (chain == null) null else R_ExternalClass(chain, name, false)
        val cls = R_Class(fullName, flags, sqlMapping, externalCls)
        val attrMap = attrs.map { Pair(it.name, it) }.toMap()
        cls.setBody(R_ClassBody(listOf(), listOf(), attrMap))
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

    fun fullName(namespaceName: String?, name: String) = if (namespaceName == null) name else (namespaceName + "." + name)

    fun nameStr(name: List<S_Name>): String = name.joinToString(".") { it.str }
}

object C_Parser {
    private val currentFileLocal = ThreadLocal.withInitial<C_SourcePath> { C_SourcePath(listOf("?")) }

    fun parse(filePath: C_SourcePath, sourceCode: String): S_ModuleDefinition {
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
        val oldFile = currentFileLocal.get()
        try {
            currentFileLocal.set(file)
            return code()
        } finally {
            currentFileLocal.set(oldFile)
        }
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

    fun errUnknownName(baseName: String, name: S_Name): C_Error {
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

        return mut.mapValues { (key, value) -> value.toList() }.toMap()
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
        val mutable: Set<R_RecordType>,
        val nonVirtualable: Set<R_RecordType>,
        val nonGtvFrom: Set<R_RecordType>,
        val nonGtvTo: Set<R_RecordType>,
        val graph: Map<R_RecordType, List<R_RecordType>>
)

object C_RecordUtils {
    fun buildRecordsStructure(records: Collection<R_RecordType>): C_RecordsStructure {
        val structMap = records.map { Pair(it, calcRecStruct(it)) }.toMap()
        val graph = structMap.mapValues { (_, v) -> v.dependencies.toList() }
        val mutable = structMap.filter { (_, v) -> v.directFlags.mutable }.keys
        val nonVirtualable = structMap.filter { (_, v) -> !v.directFlags.virtualable }.keys
        val nonGtvFrom = structMap.filter { (_, v) -> !v.directFlags.gtv.fromGtv }.keys
        val nonGtvTo = structMap.filter { (_, v) -> !v.directFlags.gtv.toGtv }.keys
        return C_RecordsStructure(mutable, nonVirtualable, nonGtvFrom, nonGtvTo, graph)
    }

    private fun calcRecStruct(type: R_Type): RecStruct {
        val flags = mutableListOf(type.directFlags())
        val deps = mutableSetOf<R_RecordType>()

        for (subType in type.componentTypes()) {
            val subStruct = discoverRecStruct(subType)
            flags.add(subStruct.directFlags)
            deps.addAll(subStruct.dependencies)
        }

        val resFlags = R_TypeFlags.combine(flags)
        return RecStruct(resFlags, deps.toSet())
    }

    private fun discoverRecStruct(type: R_Type): RecStruct {
        if (type is R_RecordType) {
            return RecStruct(type.directFlags(), setOf(type))
        }
        return calcRecStruct(type)
    }

    private class RecStruct(val directFlags: R_TypeFlags, val dependencies: Set<R_RecordType>)
}
