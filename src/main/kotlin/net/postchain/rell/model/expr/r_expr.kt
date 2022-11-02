/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model.expr

import net.postchain.rell.compiler.base.utils.C_LateGetter
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.lib.type.C_Lib_Type_Decimal
import net.postchain.rell.model.*
import net.postchain.rell.model.stmt.R_ForIterator
import net.postchain.rell.model.stmt.R_Statement
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.RellInterpreterCrashException
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.sql.SqlGen
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immListOf

abstract class R_Expr(val type: R_Type) {
    protected abstract fun evaluate0(frame: Rt_CallFrame): Rt_Value

    fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val res = evaluate0(frame)
        typeCheck(frame, type, res)
        return res
    }

    companion object {
        fun typeCheck(frame: Rt_CallFrame, type: R_Type, value: Rt_Value) {
            if (frame.defCtx.globalCtx.typeCheck) {
                val resType = value.type()
                check(type == R_UnitType || type.isAssignableFrom(resType)) {
                    "${R_Expr::class.java.simpleName}: expected ${type.name}, actual ${resType.name}"
                }
            }
        }
    }
}

class R_ErrorExpr(type: R_Type, private val message: String): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        throw RellInterpreterCrashException(message)
    }
}

sealed class R_DestinationExpr(type: R_Type): R_Expr(type) {
    abstract fun evaluateRef(frame: Rt_CallFrame): Rt_ValueRef?

    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val ref = evaluateRef(frame)
        val value = ref?.get()
        return value ?: Rt_NullValue
    }
}

class R_VarExpr(type: R_Type, val ptr: R_VarPtr, val name: String): R_DestinationExpr(type) {
    override fun evaluateRef(frame: Rt_CallFrame): Rt_ValueRef {
        return Rt_VarValueRef(type, ptr, name, frame)
    }

    private class Rt_VarValueRef(
            val type: R_Type,
            val ptr: R_VarPtr,
            val name: String,
            val frame: Rt_CallFrame
    ): Rt_ValueRef() {
        override fun get(): Rt_Value {
            val value = frame.getOpt(ptr)
            if (value == null) {
                throw Rt_Error("expr_var_uninit:$name", "Variable '$name' has not been initialized")
            }
            return value
        }

        override fun set(value: Rt_Value) {
            frame.set(ptr, type, value, true)
        }
    }
}

class R_StructMemberExpr(val base: R_Expr, val attr: R_Attribute): R_DestinationExpr(attr.type) {
    override fun evaluateRef(frame: Rt_CallFrame): Rt_ValueRef? {
        val baseValue = base.evaluate(frame)
        if (baseValue is Rt_NullValue) {
            // Must be operator "?."
            return null
        }

        val structValue = baseValue.asStruct()
        return Rt_StructAttrRef(structValue, attr)
    }

    private class Rt_StructAttrRef(val struct: Rt_StructValue, val attr: R_Attribute): Rt_ValueRef() {
        override fun get(): Rt_Value {
            val value = struct.get(attr.index)
            return value
        }

        override fun set(value: Rt_Value) {
            struct.set(attr.index, value)
        }
    }
}

class R_ConstantValueExpr(type: R_Type, private val value: Rt_Value): R_Expr(type) {
    constructor(value: Rt_Value): this(value.type(), value)

    override fun evaluate0(frame: Rt_CallFrame): Rt_Value = value

    companion object {
        fun makeNull() = R_ConstantValueExpr(Rt_NullValue)
        fun makeBool(v: Boolean) = R_ConstantValueExpr(Rt_BooleanValue(v))
        fun makeInt(v: Long) = R_ConstantValueExpr(Rt_IntValue(v))
        fun makeText(v: String) = R_ConstantValueExpr(Rt_TextValue(v))
        fun makeBytes(v: ByteArray) = R_ConstantValueExpr(Rt_ByteArrayValue(v))
    }
}

class R_TupleExpr(private val tupleType: R_TupleType, private val exprs: List<R_Expr>): R_Expr(tupleType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val values = exprs.map { it.evaluate(frame) }
        return Rt_TupleValue(tupleType, values)
    }
}

class R_ListLiteralExpr(type: R_ListType, val exprs: List<R_Expr>): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val list = MutableList(exprs.size) { exprs[it].evaluate(frame) }
        return Rt_ListValue(type, list)
    }
}

class R_MapLiteralExpr(type: R_MapType, private val entries: List<Pair<R_Expr, R_Expr>>): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val map = mutableMapOf<Rt_Value, Rt_Value>()
        for ((keyExpr, valueExpr) in entries) {
            val key = keyExpr.evaluate(frame)
            val value = valueExpr.evaluate(frame)
            if (key in map) {
                throw Rt_Error("expr_map_dupkey:${key.strCode()}", "Duplicate map key: ${key.str()}")
            }
            map.put(key, value)
        }
        return Rt_MapValue(type, map)
    }
}

sealed class R_CollectionKind(val type: R_Type) {
    abstract fun makeRtValue(col: Iterable<Rt_Value>): Rt_Value
}

class R_CollectionKind_List(type: R_Type): R_CollectionKind(type) {
    override fun makeRtValue(col: Iterable<Rt_Value>): Rt_Value {
        val list = mutableListOf<Rt_Value>()
        list.addAll(col)
        return Rt_ListValue(type, list)
    }
}

class R_CollectionKind_Set(type: R_Type): R_CollectionKind(type) {
    override fun makeRtValue(col: Iterable<Rt_Value>): Rt_Value {
        val set = mutableSetOf<Rt_Value>()
        set.addAll(col)
        return Rt_SetValue(type, set)
    }
}

class R_EmptyCollectionConstructorExpr(
        private val kind: R_CollectionKind
): R_Expr(kind.type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        return kind.makeRtValue(immListOf())
    }
}

class R_CopyCollectionConstructorExpr(
        private val kind: R_CollectionKind,
        private val arg: R_Expr,
        private val rIterator: R_ForIterator
): R_Expr(kind.type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val value = arg.evaluate(frame)
        val iterable = rIterator.list(value)
        return kind.makeRtValue(iterable)
    }
}

class R_EmptyMapConstructorExpr(type: R_Type): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        return Rt_MapValue(type, mutableMapOf())
    }
}

class R_MapCopyMapConstructorExpr(type: R_Type, private val arg: R_Expr): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val map = mutableMapOf<Rt_Value, Rt_Value>()
        val m = arg.evaluate(frame).asMap()
        map.putAll(m)
        return Rt_MapValue(type, map)
    }
}

class R_IteratorCopyMapConstructorExpr(
        type: R_Type,
        private val arg: R_Expr,
        private val rIterator: R_ForIterator
): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val map = mutableMapOf<Rt_Value, Rt_Value>()
        val value = arg.evaluate(frame)
        val iterable = rIterator.list(value)
        for (item in iterable) {
            val tuple = item.asTuple()
            val k = tuple.get(0)
            val v = tuple.get(1)
            val v0 = map.put(k, v)
            Rt_Utils.check(v0 == null) { "map:new:iterator:dupkey:${k.strCode()}" toCodeMsg "Duplicate key: ${k.str()}" }
        }
        return Rt_MapValue(type, map)
    }
}

class R_ListSubscriptExpr(type: R_Type, val base: R_Expr, val expr: R_Expr): R_DestinationExpr(type) {
    override fun evaluateRef(frame: Rt_CallFrame): Rt_ValueRef {
        val baseValue = base.evaluate(frame)
        val indexValue = expr.evaluate(frame)
        val list = baseValue.asList()
        val index = indexValue.asInteger()
        Rt_ListValue.checkIndex(list.size, index)
        return Rt_ListValueRef(list, index.toInt())
    }

    private class Rt_ListValueRef(val list: MutableList<Rt_Value>, val index: Int): Rt_ValueRef() {
        override fun get(): Rt_Value {
            return list[index]
        }

        override fun set(value: Rt_Value) {
            list[index] = value
        }
    }
}

class R_VirtualListSubscriptExpr(type: R_Type, val base: R_Expr, val expr: R_Expr): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val baseValue = base.evaluate(frame)
        val indexValue = expr.evaluate(frame)
        val list = baseValue.asVirtualList()
        val index = indexValue.asInteger()
        val res = list.get(index)
        return res
    }
}

class R_MapSubscriptExpr(type: R_Type, val base: R_Expr, val expr: R_Expr): R_DestinationExpr(type) {
    override fun evaluateRef(frame: Rt_CallFrame): Rt_ValueRef {
        val baseValue = base.evaluate(frame)
        val keyValue = expr.evaluate(frame)
        val map = baseValue.asMutableMap()
        return Rt_MapValueRef(map, keyValue)
    }

    private class Rt_MapValueRef(val map: MutableMap<Rt_Value, Rt_Value>, val key: Rt_Value): Rt_ValueRef() {
        override fun get() = getValue(map, key)

        override fun set(value: Rt_Value) {
            map.put(key, value)
        }
    }

    companion object {
        fun getValue(map: Map<Rt_Value, Rt_Value>, key: Rt_Value): Rt_Value {
            val value = map[key]
            if (value == null) {
                throw Rt_Error("fn_map_get_novalue:${key.strCode()}", "Key not in map: ${key.str()}")
            }
            return value
        }
    }
}

class R_VirtualMapSubscriptExpr(type: R_Type, val base: R_Expr, val expr: R_Expr): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val baseValue = base.evaluate(frame)
        val keyValue = expr.evaluate(frame)
        val map = baseValue.asMap()
        val res = R_MapSubscriptExpr.getValue(map, keyValue)
        return res
    }
}

class R_TextSubscriptExpr(val base: R_Expr, val expr: R_Expr): R_Expr(R_TextType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val baseValue = base.evaluate(frame)
        val indexValue = expr.evaluate(frame)
        val str = baseValue.asString()
        val index = indexValue.asInteger()

        if (index < 0 || index >= str.length) {
            throw Rt_Error("expr_text_subscript_index:${str.length}:$index",
                    "Index out of bounds: $index (length ${str.length})")
        }

        val i = index.toInt()
        val res = str.substring(i, i + 1)
        return Rt_TextValue(res)
    }
}

class R_ByteArraySubscriptExpr(val base: R_Expr, val expr: R_Expr): R_Expr(R_IntegerType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val baseValue = base.evaluate(frame)
        val indexValue = expr.evaluate(frame)
        val array = baseValue.asByteArray()
        val index = indexValue.asInteger()

        if (index < 0 || index >= array.size) {
            throw Rt_Error("expr_bytearray_subscript_index:${array.size}:$index",
                    "Index out of bounds: $index (length ${array.size})")
        }

        val i = index.toInt()
        val v = array[i].toLong()
        val res = if (v >= 0) v else v + 256
        return Rt_IntValue(res)
    }
}

class R_ElvisExpr(type: R_Type, val left: R_Expr, val right: R_Expr): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val leftVal = left.evaluate(frame)
        if (leftVal != Rt_NullValue) {
            return leftVal
        }

        val rightVal = right.evaluate(frame)
        return rightVal
    }
}

class R_NotNullExpr(type: R_Type, val expr: R_Expr): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val v = expr.evaluate(frame)
        if (v == Rt_NullValue) {
            throw Rt_Error("null_value", "Null value")
        }
        return v
    }
}

class R_IfExpr(type: R_Type, val cond: R_Expr, val trueExpr: R_Expr, val falseExpr: R_Expr): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val v = cond.evaluate(frame)
        val b = v.asBoolean()
        val expr = if (b) trueExpr else falseExpr
        val res = expr.evaluate(frame)
        return res
    }
}

sealed class R_WhenChooser {
    abstract fun choose(frame: Rt_CallFrame): Int?
}

class R_IterativeWhenChooser(val keyExpr: R_Expr, val exprs: List<IndexedValue<R_Expr>>, val elseIdx: Int?): R_WhenChooser() {
    override fun choose(frame: Rt_CallFrame): Int? {
        val keyValue = keyExpr.evaluate(frame)
        for ((i, expr) in exprs) {
            val value = expr.evaluate(frame)
            if (value == keyValue) {
                return i
            }
        }
        return elseIdx
    }
}

class R_LookupWhenChooser(val keyExpr: R_Expr, val map: Map<Rt_Value, Int>, val elseIdx: Int?): R_WhenChooser() {
    override fun choose(frame: Rt_CallFrame): Int? {
        val keyValue = keyExpr.evaluate(frame)
        val idx = map[keyValue]
        return idx ?: elseIdx
    }
}

class R_WhenExpr(type: R_Type, val chooser: R_WhenChooser, val exprs: List<R_Expr>): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val choice = chooser.choose(frame)
        check(choice != null)
        val expr = exprs[choice]
        val res = expr.evaluate(frame)
        return res
    }
}

class R_CreateExprAttr(val attr: R_Attribute, private val expr: R_Expr) {
    fun evaluate(frame: Rt_CallFrame) = expr.evaluate(frame)
}

sealed class R_CreateExpr(val rEntity: R_EntityDefinition): R_Expr(rEntity.type) {
    protected abstract fun evaluateValues(frame: Rt_CallFrame): List<Pair<R_Attribute, Rt_Value>>

    final override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        frame.checkDbUpdateAllowed()

        val values = evaluateValues(frame)

        val sqlCtx = frame.defCtx.sqlCtx
        val rowidFunc = sqlCtx.mainChainMapping().rowidFunction
        val rtSql = buildSql(sqlCtx, rEntity, values, "\"$rowidFunc\"()")
        val rtSel = SqlSelect(rtSql, listOf(type))
        val res = rtSel.execute(frame.sqlExec)

        checkEquals(res.size, 1)
        checkEquals(res[0].size, 1)
        return res[0][0]
    }

    companion object {
        fun buildSql(
                sqlCtx: Rt_SqlContext,
                rEntity: R_EntityDefinition,
                values: List<Pair<R_Attribute, Rt_Value>>,
                rowidExpr: String
        ): ParameterizedSql {
            val b = SqlBuilder()

            val table = rEntity.sqlMapping.table(sqlCtx)
            val rowid = rEntity.sqlMapping.rowidColumn()

            b.append("INSERT INTO ")
            b.appendName(table)

            b.append("(")
            b.appendName(rowid)
            b.append(values, "") { pair ->
                b.append(", ")
                b.appendName(pair.first.sqlMapping)
            }
            b.append(")")

            b.append(" VALUES (")
            b.append(rowidExpr)
            b.append(values, "") { pair ->
                b.append(", ")
                b.append(pair.second)
            }
            b.append(")")

            b.append(" RETURNING ")
            b.appendName(rowid)

            return b.build()
        }

        fun buildAddColumnsSql(
                frame: Rt_CallFrame,
                rEntity: R_EntityDefinition,
                attrs: List<R_CreateExprAttr>,
                existingRecs: Boolean
        ): ParameterizedSql {
            val sqlCtx = frame.defCtx.sqlCtx
            val table = rEntity.sqlMapping.table(sqlCtx)

            val b = SqlBuilder()

            for (attr in attrs) {
                val columnSql = SqlGen.genAddColumnSql(table, attr.attr, existingRecs)
                b.append(columnSql)
                b.append(";\n")
            }

            if (existingRecs) {
                b.append("UPDATE ")
                b.appendName(table)
                b.append(" SET ")
                b.append(attrs, ", ") { attr ->
                    val value = attr.evaluate(frame)
                    b.appendName(attr.attr.sqlMapping)
                    b.append(" = ")
                    b.append(value)
                }
                b.append(";\n")

                for (attr in attrs) {
                    b.append("ALTER TABLE ")
                    b.appendName(table)
                    b.append(" ALTER COLUMN ")
                    b.appendName(attr.attr.sqlMapping)
                    b.append(" SET NOT NULL;\n")
                }
            }

            val constraintsSql = SqlGen.genAddAttrConstraintsSql(sqlCtx, table, attrs.map { it.attr })
            if (!constraintsSql.isEmpty()) {
                b.append(constraintsSql)
                b.append(";\n")
            }

            return b.build()
        }
    }
}

class R_RegularCreateExpr(rEntity: R_EntityDefinition, val attrs: List<R_CreateExprAttr>): R_CreateExpr(rEntity) {
    override fun evaluateValues(frame: Rt_CallFrame): List<Pair<R_Attribute, Rt_Value>> {
        return attrs.map {
            val value = it.evaluate(frame)
            it.attr to value
        }
    }
}

class R_StructCreateExpr(
        rEntity: R_EntityDefinition,
        val structType: R_StructType,
        val structExpr: R_Expr
): R_CreateExpr(rEntity) {
    override fun evaluateValues(frame: Rt_CallFrame): List<Pair<R_Attribute, Rt_Value>> {
        val structValue = structExpr.evaluate(frame).asStruct()
        return structType.struct.attributesList.mapIndexed { i, attr -> attr to structValue.get(i) }
    }
}

class R_StructExpr(private val struct: R_Struct, private val attrs: List<R_CreateExprAttr>): R_Expr(struct.type) {
    init {
        checkEquals(attrs.map { it.attr.index }.sorted(), struct.attributesList.indices.toList())
    }

    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val b = Rt_StructValue.Builder(struct.type)
        for (attr in attrs) {
            val value = attr.evaluate(frame)
            b.set(attr.attr, value)
        }
        return b.build()
    }
}

class R_ObjectExpr(val objType: R_ObjectType): R_Expr(objType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        return Rt_ObjectValue(objType)
    }
}

class R_ObjectAttrExpr(type: R_Type, val rObject: R_ObjectDefinition, val atBase: Db_AtExprBase): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        var records = atBase.execute(frame, Rt_AtExprExtras.NULL)

        if (records.isEmpty()) {
            val forced = frame.defCtx.appCtx.forceObjectInit(rObject)
            if (forced) {
                records = atBase.execute(frame, Rt_AtExprExtras.NULL)
            }
        }

        val count = records.size

        if (count == 0) {
            val name = rObject.appLevelName
            throw Rt_Error("obj_norec:$name", "No record for object '$name' in database")
        } else if (count > 1) {
            val name = rObject.appLevelName
            throw Rt_Error("obj_multirec:$name:$count", "Multiple records for object '$name' in database: $count")
        }

        val record = records[0]
        checkEquals(record.size, 1)
        val value = record[0]
        return value
    }
}

class R_AssignExpr(
        type: R_Type,
        val op: R_BinaryOp,
        val dstExpr: R_DestinationExpr,
        val srcExpr: R_Expr,
        val post: Boolean
): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val ref = dstExpr.evaluateRef(frame)
        ref ?: return Rt_NullValue // Null-safe access operator

        val oldValue = ref.get()
        val srcValue = srcExpr.evaluate(frame)
        val newValue = op.evaluate(oldValue, srcValue)
        ref.set(newValue)

        return if (post) oldValue else newValue
    }
}

class R_StatementExpr(val stmt: R_Statement): R_Expr(R_UnitType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val res = stmt.execute(frame)
        check(res == null)
        return Rt_UnitValue
    }
}

class R_ChainHeightExpr(val chain: R_ExternalChainRef): R_Expr(R_IntegerType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val rtChain = frame.defCtx.sqlCtx.linkedChain(chain)
        return Rt_IntValue(rtChain.height)
    }
}

class R_StackTraceExpr(private val subExpr: R_Expr, private val filePos: R_FilePos): R_Expr(subExpr.type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        return Rt_StackTraceError.trackStack(frame, filePos) {
            subExpr.evaluate(frame)
        }
    }
}

class R_TypeAdapterExpr(type: R_Type, private val expr: R_Expr, private val adapter: R_TypeAdapter): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val value = expr.evaluate(frame)
        val value2 = adapter.adaptValue(value)
        return value2
    }
}

sealed class R_TypeAdapter {
    abstract fun adaptValue(value: Rt_Value): Rt_Value
}

object R_TypeAdapter_Direct: R_TypeAdapter() {
    override fun adaptValue(value: Rt_Value) = value
}

object R_TypeAdapter_IntegerToDecimal: R_TypeAdapter() {
    override fun adaptValue(value: Rt_Value): Rt_Value {
        val r = C_Lib_Type_Decimal.calcFromInteger(value)
        return r
    }
}

class R_TypeAdapter_Nullable(private val dstType: R_Type, private val innerAdapter: R_TypeAdapter): R_TypeAdapter() {
    override fun adaptValue(value: Rt_Value): Rt_Value {
        return if (value == Rt_NullValue) {
            Rt_NullValue
        } else {
            innerAdapter.adaptValue(value)
        }
    }
}

class R_ParameterDefaultValueExpr(
        type: R_Type,
        private val callFilePos: R_FilePos,
        private val initFrameGetter: C_LateGetter<R_CallFrame>,
        private val exprGetter: C_LateGetter<R_Expr>
): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val expr = exprGetter.get()
        val res = Rt_Utils.evaluateInNewFrame(frame.defCtx, frame, expr, callFilePos, initFrameGetter)
        return res
    }
}

class R_AttributeDefaultValueExpr(
        private val attr: R_Attribute,
        private val createFilePos: R_FilePos?,
        private val initFrameGetter: C_LateGetter<R_CallFrame>
): R_Expr(attr.type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        return Rt_Utils.evaluateInNewFrame(frame.defCtx, frame, attr.expr!!, createFilePos, initFrameGetter)
    }
}

class R_BlockCheckExpr(private val expr: R_Expr, private val blockUid: R_FrameBlockUid): R_Expr(expr.type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        frame.checkBlock(blockUid)
        return expr.evaluate(frame)
    }
}

class R_GlobalConstantExpr(
        type: R_Type,
        private val constId: R_GlobalConstantId
): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        return frame.appCtx.getGlobalConstant(constId)
    }
}
