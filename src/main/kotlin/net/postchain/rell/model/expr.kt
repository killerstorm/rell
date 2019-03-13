package net.postchain.rell.model

import net.postchain.rell.parser.C_Utils
import net.postchain.rell.runtime.*

abstract class R_Expr(val type: R_Type) {
    abstract fun evaluate(frame: Rt_CallFrame): Rt_Value
}

sealed class R_DestinationExpr(type: R_Type): R_Expr(type) {
    abstract fun evaluateRef(frame: Rt_CallFrame): Rt_ValueRef?

    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val ref = evaluateRef(frame)
        val value = ref?.get()
        return value ?: Rt_NullValue
    }
}

class R_VarExpr(type: R_Type, val ptr: R_VarPtr, val name: String): R_DestinationExpr(type) {
    override fun evaluateRef(frame: Rt_CallFrame): Rt_ValueRef {
        return Rt_VarValueRef(ptr, name, frame)
    }

    private class Rt_VarValueRef(val ptr: R_VarPtr, val name: String, val frame: Rt_CallFrame): Rt_ValueRef() {
        override fun get(): Rt_Value {
            val value = frame.getOpt(ptr)
            if (value == null) {
                throw Rt_Error("expr_var_uninit:$name", "Variable '$name' has not been initialized")
            }
            return value
        }

        override fun set(value: Rt_Value) {
            frame.set(ptr, value, true)
        }
    }
}

class R_RecordMemberExpr(val base: R_Expr, val attr: R_Attrib): R_DestinationExpr(attr.type) {
    override fun evaluateRef(frame: Rt_CallFrame): Rt_ValueRef? {
        val baseValue = base.evaluate(frame)
        if (baseValue is Rt_NullValue) {
            // Must be operator "?."
            return null
        }

        val recordValue = baseValue.asRecord()
        return Rt_RecordAttrRef(recordValue, attr)
    }

    private class Rt_RecordAttrRef(val record: Rt_RecordValue, val attr: R_Attrib): Rt_ValueRef() {
        override fun get(): Rt_Value {
            val value = record.get(attr.index)
            return value
        }

        override fun set(value: Rt_Value) {
            record.set(attr.index, value)
        }
    }
}

class R_ConstantExpr(val value: Rt_Value): R_Expr(value.type()) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value = value

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
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
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

class R_MemberCalculator_TupleField(type: R_Type, val fieldIndex: Int): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val values = baseValue.asTuple()
        return values[fieldIndex]
    }
}

class R_MemberCalculator_RecordAttr(val attr: R_Attrib): R_MemberCalculator(attr.type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val recordValue = baseValue.asRecord()
        return recordValue.get(attr.index)
    }
}

class R_MemberCalculator_DataAttribute(type: R_Type, val atBase: R_AtExprBase): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val list = atBase.execute(frame, listOf(baseValue), null)
        R_AtExpr.checkCount(R_AtCardinality.ONE, list.size)

        check(list[0].size == 1)
        val res = list[0][0]
        return res
    }
}

class R_MemberCalculator_SysFn(type: R_Type, val fn: R_SysFunction, val args: List<R_Expr>): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val vArgs = args.map { it.evaluate(frame) }
        val vFullArgs = listOf(baseValue) + vArgs
        return fn.call(frame.entCtx.modCtx, vFullArgs)
    }
}

class R_TupleExpr(val tupleType: R_TupleType, val exprs: List<R_Expr>): R_Expr(tupleType) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val values = exprs.map { it.evaluate(frame) }
        return Rt_TupleValue(tupleType, values)
    }
}

class R_ListLiteralExpr(type: R_ListType, val exprs: List<R_Expr>): R_Expr(type) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val list = MutableList(exprs.size) { exprs[it].evaluate(frame) }
        return Rt_ListValue(type, list)
    }
}

class R_MapLiteralExpr(type: R_MapType, val entries: List<Pair<R_Expr, R_Expr>>): R_Expr(type) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
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
}

class R_ListExpr(type: R_Type, val arg: R_Expr?): R_Expr(type) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val list = mutableListOf<Rt_Value>()
        if (arg != null) {
            val c = arg.evaluate(frame).asCollection()
            list.addAll(c)
        }
        return Rt_ListValue(type, list)
    }
}

class R_SetExpr(type: R_Type, val arg: R_Expr?): R_Expr(type) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val set = mutableSetOf<Rt_Value>()
        if (arg != null) {
            val c = arg.evaluate(frame).asCollection()
            set.addAll(c)
        }
        return Rt_SetValue(type, set)
    }
}

class R_MapExpr(type: R_Type, val arg: R_Expr?): R_Expr(type) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
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

        if (index < 0 || index >= list.size) {
            throw Rt_Error("expr_list_lookup_index:${list.size}:$index",
                    "List index out of bounds: $index (size ${list.size})")
        }

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

class R_MapLookupExpr(type: R_Type, val base: R_Expr, val expr: R_Expr): R_DestinationExpr(type) {
    override fun evaluateRef(frame: Rt_CallFrame): Rt_ValueRef {
        val baseValue = base.evaluate(frame)
        val keyValue = expr.evaluate(frame)
        val map = baseValue.asMap()
        return Rt_MapValueRef(map, keyValue)
    }

    private class Rt_MapValueRef(val map: MutableMap<Rt_Value, Rt_Value>, val key: Rt_Value): Rt_ValueRef() {
        override fun get(): Rt_Value {
            val value = map[key]
            if (value == null) {
                throw Rt_Error("fn_map_get_novalue:${key.toStrictString()}", "Key not in map: $key")
            }
            return value
        }

        override fun set(value: Rt_Value) {
            map.put(key, value)
        }
    }
}

class R_TextSubscriptExpr(val base: R_Expr, val expr: R_Expr): R_Expr(R_TextType) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
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

class R_ByteArraySubscriptExpr(val base: R_Expr, val expr: R_Expr): R_Expr(R_TextType) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
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
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val leftVal = left.evaluate(frame)
        if (leftVal != Rt_NullValue) {
            return leftVal
        }

        val rightVal = right.evaluate(frame)
        return rightVal
    }
}

class R_NotNullExpr(type: R_Type, val expr: R_Expr): R_Expr(type) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val v = expr.evaluate(frame)
        if (v == Rt_NullValue) {
            throw Rt_Error("null_value", "Null value")
        }
        return v
    }
}

class R_IfExpr(type: R_Type, val cond: R_Expr, val trueExpr: R_Expr, val falseExpr: R_Expr): R_Expr(type) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val v = cond.evaluate(frame)
        val b = v.asBoolean()
        val expr = if (b) trueExpr else falseExpr
        val res = expr.evaluate(frame)
        return res
    }
}

sealed class R_RequireExpr(type: R_Type, val expr: R_Expr, val msgExpr: R_Expr?): R_Expr(type) {
    abstract fun calculate(v: Rt_Value): Rt_Value?

    final override fun evaluate(frame: Rt_CallFrame): Rt_Value {
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

class R_CreateExpr(type: R_Type, val rClass: R_Class, val attrs: List<R_CreateExprAttr>): R_Expr(type) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        frame.entCtx.checkDbUpdateAllowed()
        val sqlCtx = frame.entCtx.modCtx.sqlCtx
        val rowidFunc = sqlCtx.mainChainMapping.rowidFunction
        val rtSql = buildSql(sqlCtx, rClass, attrs, "\"$rowidFunc\"()")
        val rtSel = SqlSelect(rtSql, listOf(type))
        val res = rtSel.execute(frame)
        check(res.size == 1)
        check(res[0].size == 1)
        return res[0][0]
    }

    companion object {
        fun buildSql(sqlCtx: Rt_SqlContext, rClass: R_Class, attrs: List<R_CreateExprAttr>, rowidExpr: String): ParameterizedSql {
            val builder = SqlBuilder()

            val table = rClass.sqlMapping.table(sqlCtx)
            val rowid = rClass.sqlMapping.rowidColumn()

            builder.append("INSERT INTO ")
            builder.appendName(table)

            builder.append("(")
            builder.appendName(rowid)
            builder.append(attrs, "") { attr ->
                builder.append(", ")
                builder.appendName(attr.attr.sqlMapping)
            }
            builder.append(")")

            builder.append(" VALUES (")
            builder.append(rowidExpr)
            builder.append(attrs, "") { attr ->
                builder.append(", ")
                builder.append(attr.expr())
            }
            builder.append(")")

            builder.append(" RETURNING ")
            builder.appendName(rowid)

            return builder.build()
        }
    }
}

class R_RecordExpr(val record: R_RecordType, val attrs: List<R_CreateExprAttr>): R_Expr(record) {
    init {
        check(attrs.size == record.attributesList.size)
        check(attrs.map { it.attr.index }.toSet() == record.attributesList.indices.toSet())
    }

    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val values = MutableList<Rt_Value>(attrs.size) { Rt_UnitValue }
        for (attr in attrs) {
            val value = attr.expr().evaluate(frame)
            values[attr.attr.index] = value
        }
        return Rt_RecordValue(record, values)
    }
}

class R_ObjectExpr(val objType: R_ObjectType): R_Expr(objType) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        return Rt_ObjectValue(objType)
    }
}

class R_ObjectAttrExpr(type: R_Type, val rObject: R_Object, val atBase: R_AtExprBase): R_Expr(type) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val records = atBase.execute(frame, listOf(), null)
        val count = records.size

        if (count == 0) {
            val name = rObject.rClass.name
            throw Rt_Error("obj_norec:$name", "No record for object '$name' in database")
        } else if (count > 1) {
            val name = rObject.rClass.name
            throw Rt_Error("obj_multirec:$name:$count", "Multiple records for object '$name' in database: $count")
        }

        var record = records[0]
        check(record.size == 1)
        val value = record[0]
        return value
    }
}
