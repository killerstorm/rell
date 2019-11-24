package net.postchain.rell.model

import net.postchain.rell.parser.C_EntityAttrRef
import net.postchain.rell.parser.C_Utils
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlGen

abstract class R_Expr(val type: R_Type) {
    protected abstract fun evaluate0(frame: Rt_CallFrame): Rt_Value

    fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val res = evaluate0(frame)
        typeCheck(frame, type, res)
        return res
    }

    open fun constantValue(): Rt_Value? = null

    companion object {
        fun typeCheck(frame: Rt_CallFrame, type: R_Type, value: Rt_Value) {
            if (frame.defCtx.globalCtx.typeCheck) {
                val resType = value.type()
                check(type.isAssignableFrom(resType)) {
                    "${R_Expr::class.java.simpleName}: expected ${type.name}, actual ${resType.name}"
                }
            }
        }
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

class R_StructMemberExpr(val base: R_Expr, val attr: R_Attrib): R_DestinationExpr(attr.type) {
    override fun evaluateRef(frame: Rt_CallFrame): Rt_ValueRef? {
        val baseValue = base.evaluate(frame)
        if (baseValue is Rt_NullValue) {
            // Must be operator "?."
            return null
        }

        val structValue = baseValue.asStruct()
        return Rt_StructAttrRef(structValue, attr)
    }

    private class Rt_StructAttrRef(val struct: Rt_StructValue, val attr: R_Attrib): Rt_ValueRef() {
        override fun get(): Rt_Value {
            val value = struct.get(attr.index)
            return value
        }

        override fun set(value: Rt_Value) {
            struct.set(attr.index, value)
        }
    }
}

class R_ConstantExpr(val value: Rt_Value): R_Expr(value.type()) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value = value
    override fun constantValue() = value

    companion object {
        fun makeNull() = R_ConstantExpr(Rt_NullValue)
        fun makeBool(v: Boolean) = R_ConstantExpr(Rt_BooleanValue(v))
        fun makeInt(v: Long) = R_ConstantExpr(Rt_IntValue(v))
        fun makeText(v: String) = R_ConstantExpr(Rt_TextValue(v))
        fun makeBytes(v: ByteArray) = R_ConstantExpr(Rt_ByteArrayValue(v))
    }
}

class R_MemberExpr(val base: R_Expr, val safe: Boolean, val calculator: R_MemberCalculator)
    : R_Expr(C_Utils.effectiveMemberType(calculator.type, safe))
{
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val baseValue = base.evaluate(frame)
        if (safe && baseValue == Rt_NullValue) {
            return Rt_NullValue
        }
        check(baseValue != Rt_NullValue)
        check(baseValue != Rt_UnitValue)
        val value = calculator.calculate(frame, baseValue)
        return value
    }
}

sealed class R_MemberCalculator(val type: R_Type) {
    abstract fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value
}

class R_MemberCalculator_TupleAttr(type: R_Type, val attrIndex: Int): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val values = baseValue.asTuple()
        return values[attrIndex]
    }
}

class R_MemberCalculator_VirtualTupleAttr(type: R_Type, val fieldIndex: Int): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val tuple = baseValue.asVirtualTuple()
        val res = tuple.get(fieldIndex)
        return res
    }
}

class R_MemberCalculator_StructAttr(val attr: R_Attrib): R_MemberCalculator(attr.type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val structValue = baseValue.asStruct()
        return structValue.get(attr.index)
    }
}

class R_MemberCalculator_VirtualStructAttr(type: R_Type, val attr: R_Attrib): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val structValue = baseValue.asVirtualStruct()
        return structValue.get(attr.index)
    }
}

class R_MemberCalculator_DataAttribute(type: R_Type, val atBase: R_AtExprBase): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val list = atBase.execute(frame, listOf(baseValue), null)

        if (list.size != 1) {
            val msg = if (list.size == 0) {
                "Object not found in the database: $baseValue (was deleted?)"
            } else {
                "Found more than one object $baseValue in the database: ${list.size}"
            }
            throw Rt_Error("expr_entity_attr_count:${list.size}", msg)
        }

        check(list[0].size == 1)
        val res = list[0][0]
        return res
    }
}

class R_MemberCalculator_SysFn(type: R_Type, val fn: R_SysFunction, val args: List<R_Expr>): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val vArgs = args.map { it.evaluate(frame) }
        val vFullArgs = listOf(baseValue) + vArgs
        return fn.call(frame.defCtx.callCtx, vFullArgs)
    }
}

object R_MemberCalculator_Rowid: R_MemberCalculator(C_EntityAttrRef.ROWID_TYPE) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val id = baseValue.asObjectId()
        return Rt_RowidValue(id)
    }
}

class R_TupleExpr(val tupleType: R_TupleType, val exprs: List<R_Expr>): R_Expr(tupleType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val values = exprs.map { it.evaluate(frame) }
        return Rt_TupleValue(tupleType, values)
    }

    override fun constantValue(): Rt_Value? {
        val values = exprs.map { it.constantValue() }
        if (values.any { it == null }) return null
        return Rt_TupleValue(tupleType, values.map { it!! })
    }
}

class R_ListLiteralExpr(type: R_ListType, val exprs: List<R_Expr>): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val list = MutableList(exprs.size) { exprs[it].evaluate(frame) }
        return Rt_ListValue(type, list)
    }

    override fun constantValue(): Rt_Value? {
        val values = exprs.map { it.constantValue() }
        if (values.any { it == null }) return null
        return Rt_ListValue(type, values.map { it!! }.toMutableList())
    }
}

class R_MapLiteralExpr(type: R_MapType, val entries: List<Pair<R_Expr, R_Expr>>): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val map = mutableMapOf<Rt_Value, Rt_Value>()
        for ((keyExpr, valueExpr) in entries) {
            val key = keyExpr.evaluate(frame)
            val value = valueExpr.evaluate(frame)
            if (key in map) {
                throw Rt_Error("expr_map_dupkey:${key.toStrictString()}", "Duplicate map key: $key")
            }
            map.put(key, value)
        }
        return Rt_MapValue(type, map)
    }

    override fun constantValue(): Rt_Value? {
        val values = entries.map { (k, v) -> Pair(k.constantValue(), v.constantValue()) }
        if (values.any { (k, v) -> k == null || v == null }) return null
        return Rt_MapValue(type, values.map { (k, v) -> Pair(k!!, v!!) }.toMap().toMutableMap())
    }
}

class R_ListExpr(type: R_Type, val arg: R_Expr?): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val list = mutableListOf<Rt_Value>()
        if (arg != null) {
            val c = arg.evaluate(frame).asCollection()
            list.addAll(c)
        }
        return Rt_ListValue(type, list)
    }
}

class R_SetExpr(type: R_Type, val arg: R_Expr?): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val set = mutableSetOf<Rt_Value>()
        if (arg != null) {
            val c = arg.evaluate(frame).asCollection()
            set.addAll(c)
        }
        return Rt_SetValue(type, set)
    }
}

class R_MapExpr(type: R_Type, val arg: R_Expr?): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val map = mutableMapOf<Rt_Value, Rt_Value>()
        if (arg != null) {
            val m = arg.evaluate(frame).asMap()
            map.putAll(m)
        }
        return Rt_MapValue(type, map)
    }
}

class R_ListLookupExpr(type: R_Type, val base: R_Expr, val expr: R_Expr): R_DestinationExpr(type) {
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

class R_VirtualListLookupExpr(type: R_Type, val base: R_Expr, val expr: R_Expr): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val baseValue = base.evaluate(frame)
        val indexValue = expr.evaluate(frame)
        val list = baseValue.asVirtualList()
        val index = indexValue.asInteger()
        val res = list.get(index)
        return res
    }
}

class R_MapLookupExpr(type: R_Type, val base: R_Expr, val expr: R_Expr): R_DestinationExpr(type) {
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
                throw Rt_Error("fn_map_get_novalue:${key.toStrictString()}", "Key not in map: $key")
            }
            return value
        }
    }
}

class R_VirtualMapLookupExpr(type: R_Type, val base: R_Expr, val expr: R_Expr): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val baseValue = base.evaluate(frame)
        val keyValue = expr.evaluate(frame)
        val map = baseValue.asMap()
        val res = R_MapLookupExpr.getValue(map, keyValue)
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
        val res = if (v > 0) v else v + 256
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

sealed class R_RequireExpr(type: R_Type, val expr: R_Expr, val msgExpr: R_Expr?): R_Expr(type) {
    abstract fun calculate(v: Rt_Value): Rt_Value?

    final override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val value = expr.evaluate(frame)
        val res = calculate(value)
        if (res != null) {
            return res
        }

        val msg = if (msgExpr == null) null else {
            val msgValue = msgExpr.evaluate(frame)
            msgValue.asString()
        }

        throw Rt_RequireError(msg)
    }
}

class R_RequireExpr_Boolean(expr: R_Expr, msgExpr: R_Expr?): R_RequireExpr(R_UnitType, expr, msgExpr) {
    override fun calculate(v: Rt_Value) = if (v.asBoolean()) Rt_UnitValue else null
}

class R_RequireExpr_Nullable(type: R_Type, expr: R_Expr, msgExpr: R_Expr?): R_RequireExpr(type, expr, msgExpr) {
    override fun calculate(v: Rt_Value) = if (v != Rt_NullValue) v else null
}

class R_RequireExpr_Collection(type: R_Type, expr: R_Expr, msgExpr: R_Expr?): R_RequireExpr(type, expr, msgExpr) {
    override fun calculate(v: Rt_Value) = if (v != Rt_NullValue && !v.asCollection().isEmpty()) v else null
}

class R_RequireExpr_Map(type: R_Type, expr: R_Expr, msgExpr: R_Expr?): R_RequireExpr(type, expr, msgExpr) {
    override fun calculate(v: Rt_Value) = if (v != Rt_NullValue && !v.asMap().isEmpty()) v else null
}

sealed class R_CreateExprAttr(val attr: R_Attrib) {
    abstract fun expr(): R_Expr
}

class R_CreateExprAttr_Specified(attr: R_Attrib, private val expr: R_Expr): R_CreateExprAttr(attr) {
    override fun expr() = expr
}

class R_CreateExprAttr_Default(attr: R_Attrib): R_CreateExprAttr(attr) {
    override fun expr() = attr.expr!!
}

class R_CreateExpr(val rEntity: R_Entity, val attrs: List<R_CreateExprAttr>): R_Expr(rEntity.type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        frame.defCtx.checkDbUpdateAllowed()
        val sqlCtx = frame.defCtx.sqlCtx
        val rowidFunc = sqlCtx.mainChainMapping.rowidFunction
        val rtSql = buildSql(sqlCtx, rEntity, attrs, "\"$rowidFunc\"()")
        val rtSel = SqlSelect(rtSql, listOf(type))
        val res = rtSel.execute(frame)
        check(res.size == 1)
        check(res[0].size == 1)
        return res[0][0]
    }

    companion object {
        fun buildSql(
                sqlCtx: Rt_SqlContext,
                rEntity: R_Entity,
                attrs: List<R_CreateExprAttr>,
                rowidExpr: String
        ): ParameterizedSql {
            val b = SqlBuilder()

            val table = rEntity.sqlMapping.table(sqlCtx)
            val rowid = rEntity.sqlMapping.rowidColumn()

            b.append("INSERT INTO ")
            b.appendName(table)

            b.append("(")
            b.appendName(rowid)
            b.append(attrs, "") { attr ->
                b.append(", ")
                b.appendName(attr.attr.sqlMapping)
            }
            b.append(")")

            b.append(" VALUES (")
            b.append(rowidExpr)
            b.append(attrs, "") { attr ->
                b.append(", ")
                b.append(attr.expr())
            }
            b.append(")")

            b.append(" RETURNING ")
            b.appendName(rowid)

            return b.build()
        }

        fun buildAddColumnsSql(
                sqlCtx: Rt_SqlContext,
                rEntity: R_Entity,
                attrs: List<R_CreateExprAttr>,
                existingRecs: Boolean
        ): ParameterizedSql {
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
                    b.appendName(attr.attr.sqlMapping)
                    b.append(" = ")
                    b.append(attr.expr())
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

class R_StructExpr(private val struct: R_Struct, private val attrs: List<R_CreateExprAttr>): R_Expr(struct.type) {
    init {
        check(attrs.size == struct.attributesList.size)
        check(attrs.map { it.attr.index }.toSet() == struct.attributesList.indices.toSet())
    }

    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val values = MutableList<Rt_Value>(attrs.size) { Rt_UnitValue }
        for (attr in attrs) {
            val value = attr.expr().evaluate(frame)
            values[attr.attr.index] = value
        }
        return Rt_StructValue(struct.type, values)
    }
}

class R_ObjectExpr(val objType: R_ObjectType): R_Expr(objType) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        return Rt_ObjectValue(objType)
    }
}

class R_ObjectAttrExpr(type: R_Type, val rObject: R_Object, val atBase: R_AtExprBase): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        var records = atBase.execute(frame, listOf(), null)

        if (records.isEmpty()) {
            val forced = frame.defCtx.appCtx.forceObjectInit(rObject)
            if (forced) {
                records = atBase.execute(frame, listOf(), null)
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

        var record = records[0]
        check(record.size == 1)
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

    override fun constantValue() = subExpr.constantValue()
}

class R_TypeAdapterExpr(type: R_Type, private val expr: R_Expr, private val adapter: R_TypeAdapter): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val value = expr.evaluate(frame)
        val value2 = adapter.adaptValue(value)
        return value2
    }

    override fun constantValue(): Rt_Value? {
        val value = expr.constantValue()
        val value2 = if (value == null) null else adapter.adaptValue(value)
        return value2
    }
}

sealed class R_TypeAdapter {
    abstract fun adaptValue(value: Rt_Value): Rt_Value
    abstract fun adaptExpr(expr: R_Expr): R_Expr
    abstract fun adaptExpr(expr: Db_Expr): Db_Expr
}

object R_TypeAdapter_Direct: R_TypeAdapter() {
    override fun adaptValue(value: Rt_Value) = value
    override fun adaptExpr(expr: R_Expr) = expr
    override fun adaptExpr(expr: Db_Expr) = expr
}

object R_TypeAdapter_IntegerToDecimal: R_TypeAdapter() {
    override fun adaptValue(value: Rt_Value): Rt_Value {
        val r = R_SysFn_Decimal.FromInteger.call(value)
        return r
    }

    override fun adaptExpr(expr: R_Expr): R_Expr {
        return R_TypeAdapterExpr(R_DecimalType, expr, this)
    }

    override fun adaptExpr(expr: Db_Expr): Db_Expr {
        return Db_CallExpr(R_DecimalType, Db_SysFn_Decimal.FromInteger, listOf(expr))
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

    override fun adaptExpr(expr: R_Expr): R_Expr {
        return R_TypeAdapterExpr(dstType, expr, this)
    }

    override fun adaptExpr(expr: Db_Expr): Db_Expr {
        // Not completely right, but Db_Exprs do not support nullable anyway.
        return Db_CallExpr(R_DecimalType, Db_SysFn_Decimal.FromInteger, listOf(expr))
    }
}
