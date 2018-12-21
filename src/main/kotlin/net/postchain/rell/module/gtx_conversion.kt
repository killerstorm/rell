package net.postchain.rell.module

import net.postchain.core.UserMistake
import net.postchain.gtx.*
import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.ROWID_COLUMN
import net.postchain.rell.sql.SqlExecutor
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.HashSetValuedHashMap
import java.lang.UnsupportedOperationException

val GTX_QUERY_HUMAN = true
val GTX_OPERATION_HUMAN = false

class GtxToRtContext {
    private val objectIds: MultiValuedMap<RClass, Long> = HashSetValuedHashMap()

    fun trackObject(cls: RClass, rowid: Long) {
        objectIds.put(cls, rowid)
    }

    fun finish(sqlExec: SqlExecutor) {
        for (rClass in objectIds.keySet()) {
            val rowids = objectIds.get(rClass)
            checkRowids(sqlExec, rClass, rowids)
        }
    }

    private fun checkRowids(sqlExec: SqlExecutor, rClass: RClass, rowids: Collection<Long>) {
        val existingIds = selectExistingIds(sqlExec, rClass, rowids)
        val missingIds = rowids.toSet() - existingIds
        if (!missingIds.isEmpty()) {
            val s = missingIds.toList().sorted()
            throw RtGtxValueError("obj_missing:${rClass.name}:${missingIds.joinToString(",")}",
                    "Missing objects of class '${rClass.name}': $s")
        }
    }

    private fun selectExistingIds(sqlExec: SqlExecutor, rClass: RClass, rowids: Collection<Long>): Set<Long> {
        val buf = StringBuilder()
        buf.append("SELECT \"").append(ROWID_COLUMN).append("\"")
        buf.append(" FROM \"").append(rClass.name).append("\"")
        buf.append(" WHERE \"").append(ROWID_COLUMN).append("\" IN (")
        rowids.joinTo(buf, ",")
        buf.append(")")
        val sql = buf.toString()

        val existingIds = mutableSetOf<Long>()
        sqlExec.executeQuery(sql, {}) { existingIds.add(it.getLong(1)) }
        return existingIds
    }
}

sealed class GtxRtConversion {
    abstract fun directHuman(): Boolean
    abstract fun directCompact(): Boolean
    abstract fun rtToGtx(rt: RtValue, human: Boolean): GTXValue
    abstract fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean): RtValue
}

object GtxRtConversion_None: GtxRtConversion() {
    override fun directHuman() = false
    override fun directCompact() = false
    override fun rtToGtx(rt: RtValue, human: Boolean) = throw UnsupportedOperationException()
    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean) = throw UnsupportedOperationException()
}

object GtxRtConversion_Null: GtxRtConversion() {
    override fun directHuman() = true
    override fun directCompact() = true

    override fun rtToGtx(rt: RtValue, human: Boolean): GTXValue {
        check(rt == RtNullValue)
        return GTXNull
    }

    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean): RtValue {
        check(gtx.isNull())
        return RtNullValue
    }
}

object GtxRtConversion_Boolean: GtxRtConversion() {
    override fun directHuman() = true
    override fun directCompact() = true
    override fun rtToGtx(rt: RtValue, human: Boolean) = IntegerGTXValue(if (rt.asBoolean()) 1L else 0L)
    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean) = RtBooleanValue(gtxToBoolean(gtx))
}

object GtxRtConversion_Text: GtxRtConversion() {
    override fun directHuman() = true
    override fun directCompact() = true
    override fun rtToGtx(rt: RtValue, human: Boolean) = StringGTXValue(rt.asString())
    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean) = RtTextValue(gtxToString(gtx))
}

object GtxRtConversion_Integer: GtxRtConversion() {
    override fun directHuman() = true
    override fun directCompact() = true
    override fun rtToGtx(rt: RtValue, human: Boolean) = IntegerGTXValue(rt.asInteger())
    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean) = RtIntValue(gtxToInteger(gtx))
}

object GtxRtConversion_ByteArray: GtxRtConversion() {
    override fun directHuman() = true
    override fun directCompact() = true
    override fun rtToGtx(rt: RtValue, human: Boolean) = ByteArrayGTXValue(rt.asByteArray())
    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean) = RtByteArrayValue(gtxToByteArray(gtx))
}

object GtxRtConversion_Json: GtxRtConversion() {
    override fun directHuman() = true
    override fun directCompact() = true
    override fun rtToGtx(rt: RtValue, human: Boolean) = StringGTXValue(rt.asJsonString())
    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean) = gtxToJson(gtx)
}

class GtxRtConversion_Object(val type: RInstanceRefType): GtxRtConversion() {
    override fun directHuman() = true
    override fun directCompact() = true

    override fun rtToGtx(rt: RtValue, human: Boolean) = IntegerGTXValue(rt.asObjectId())

    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean): RtValue {
        val rowid = gtxToInteger(gtx)
        ctx.trackObject(type.rClass, rowid)
        return RtObjectValue(type, rowid)
    }
}

class GtxRtConversion_Record(val type: RRecordType): GtxRtConversion() {
    override fun directHuman() = true
    override fun directCompact() = true

    override fun rtToGtx(rt: RtValue, human: Boolean): GTXValue {
        val attrs = type.attributesList
        if (human) {
            val record = rt.asRecord()
            val gtxFields = attrs.mapIndexed { i, attr -> Pair(attr.name, attr.type.rtToGtx(record.get(i), human)) }.toMap()
            return DictGTXValue(gtxFields)
        } else {
            val record = rt.asRecord()
            val gtxFields = attrs.mapIndexed { i, attr -> attr.type.rtToGtx(record.get(i), human) }.toTypedArray()
            return ArrayGTXValue(gtxFields)
        }
    }

    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean): RtValue {
        return if (human) gtxToRtHuman(ctx, gtx) else gtxToRtCompact(ctx, gtx)
    }

    private fun gtxToRtHuman(ctx: GtxToRtContext, gtx: GTXValue): RtValue {
        val gtxFields = gtxToMap(gtx)
        checkFieldCount(gtxFields.size)

        val attrs = type.attributesList
        val rtFields = attrs.mapIndexed { i, attr ->
            val key = attr.name
            if (key !in gtxFields) {
                val typeName = type.name
                throw RtGtxValueError("record_nokey:$typeName:$key", "Key missing in GTX dictionary: field $typeName.$key")
            }
            val gtxField = gtxFields.getValue(key)
            attr.type.gtxToRt(ctx, gtxField, true)
        }.toMutableList()

        return RtRecordValue(type, rtFields)
    }

    private fun gtxToRtCompact(ctx: GtxToRtContext, gtx: GTXValue): RtValue {
        val gtxFields = gtxToArray(gtx)
        checkFieldCount(gtxFields.size)

        val attrs = type.attributesList
        val rtFields = gtxFields.mapIndexed { i, gtxField -> attrs[i].type.gtxToRt(ctx, gtxField, false) }.toMutableList()
        return RtRecordValue(type, rtFields)
    }

    private fun checkFieldCount(actualCount: Int) {
        val expectedCount = type.attributesList.size
        if (actualCount != expectedCount) {
            val typeName = type.name
            throw RtGtxValueError("record_size:$typeName:$expectedCount:$actualCount",
                    "Wrong GTX array size for record $typeName: $actualCount instead of $expectedCount")
        }
    }
}

class GtxRtConversion_Nullable(val type: RNullableType): GtxRtConversion() {
    override fun directHuman() = true
    override fun directCompact() = true

    override fun rtToGtx(rt: RtValue, human: Boolean): GTXValue {
        return if (rt == RtNullValue) {
            GTXNull
        } else {
            type.valueType.rtToGtx(rt, human)
        }
    }

    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean): RtValue {
        return if (gtx.isNull()) {
            RtNullValue
        } else {
            type.valueType.gtxToRt(ctx, gtx, human)
        }
    }
}

sealed class GtxRtConversion_Collection(val type: RCollectionType): GtxRtConversion() {
    final override fun directHuman() = true
    final override fun directCompact() = true

    final override fun rtToGtx(rt: RtValue, human: Boolean): GTXValue {
        val elementType = type.elementType
        return ArrayGTXValue(rt.asCollection().map { elementType.rtToGtx(it, human) }.toTypedArray())
    }
}

class GtxRtConversion_List(type: RListType): GtxRtConversion_Collection(type) {
    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean): RtValue {
        val elementType = type.elementType
        val lst = gtxToArray(gtx).map { elementType.gtxToRt(ctx, it, human) }.toMutableList()
        return RtListValue(type, lst)
    }
}

class GtxRtConversion_Set(type: RSetType): GtxRtConversion_Collection(type) {
    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean): RtValue {
        val elementType = type.elementType
        val set = mutableSetOf<RtValue>()

        for (gtxElem in gtxToArray(gtx)) {
            val rtElem = elementType.gtxToRt(ctx, gtxElem, human)
            if (!set.add(rtElem)) {
                throw RtGtxValueError("set_dup:$rtElem", "Duplicate set element: $rtElem")
            }
        }

        return RtSetValue(type, set)
    }
}

class GtxRtConversion_Map(val type: RMapType): GtxRtConversion() {
    override fun directHuman() = RTextType.isAssignableFrom(type.keyType)
    override fun directCompact() = RTextType.isAssignableFrom(type.keyType)

    override fun rtToGtx(rt: RtValue, human: Boolean): GTXValue {
        val valueType = type.valueType
        val m = rt.asMap()
        val m2 = m.mapKeys { (k, _) -> k.asString() }
                .mapValues { (_, v) -> valueType.rtToGtx(v, human) }
        return DictGTXValue(m2)
    }

    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean): RtValue {
        val valueType = type.valueType
        val map = gtxToMap(gtx)
                .mapKeys { (k, _) -> RtTextValue(k) as RtValue }
                .mapValues { (_, v) -> valueType.gtxToRt(ctx, v, human) }
                .toMutableMap()
        return RtMapValue(type, map)
    }
}

class GtxRtConversion_Tuple(val type: RTupleType): GtxRtConversion() {
    override fun directHuman() = type.fields.all { it.name != null } || !type.fields.any { it.name != null }
    override fun directCompact() = true

    override fun rtToGtx(rt: RtValue, human: Boolean): GTXValue {
        return if (human && type.fields.all { it.name != null }) rtToGtxHuman(rt) else rtToGtxCompact(rt)
    }

    private fun rtToGtxHuman(rt: RtValue): GTXValue {
        val rtFields = rt.asTuple()
        check(rtFields.size == type.fields.size)
        val gtx = rtFields.mapIndexed { i, rtField ->
            val field = type.fields[i]
            Pair(field.name!!, field.type.rtToGtx(rtField, true))
        }.toMap()
        return DictGTXValue(gtx)
    }

    private fun rtToGtxCompact(rt: RtValue): GTXValue {
        val rtFields = rt.asTuple()
        check(rtFields.size == type.fields.size)
        val gtxFields = rtFields.mapIndexed { i, rtField ->
            type.fields[i].type.rtToGtx(rtField, false)
        }.toTypedArray()
        return ArrayGTXValue(gtxFields)
    }

    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean): RtValue {
        return if (human && type.fields.all { it.name != null }) gtxToRtHuman(ctx, gtx) else gtxToRtCompact(ctx, gtx)
    }

    private fun gtxToRtHuman(ctx: GtxToRtContext, gtx: GTXValue): RtValue {
        val gtxFields = gtxToMap(gtx)
        checkFieldCount(gtxFields.size, "dictionary")

        val rtFields = type.fields.mapIndexed { i, field ->
            val key = field.name!!
            if (key !in gtxFields) {
                throw RtGtxValueError("tuple_nokey:$key", "Key missing in GTX dictionary: '$key'")
            }
            val gtxField = gtxFields.getValue(key)
            field.type.gtxToRt(ctx, gtxField, true)
        }

        return RtTupleValue(type, rtFields)
    }

    private fun gtxToRtCompact(ctx: GtxToRtContext, gtx: GTXValue): RtValue {
        val gtxFields = gtxToArray(gtx)
        checkFieldCount(gtxFields.size, "array")

        val rtFields = gtxFields.mapIndexed { i, gtxField ->
            type.fields[i].type.gtxToRt(ctx, gtxField, false)
        }.toList()

        return RtTupleValue(type, rtFields)
    }

    private fun checkFieldCount(actualCount: Int, structure: String) {
        val expectedCount = type.fields.size
        if (actualCount != expectedCount) {
            throw RtGtxValueError("tuple_count:$expectedCount:$actualCount",
                    "Wrong GTX $structure size: $actualCount instead of $expectedCount")
        }
    }
}

object GtxRtConversion_GtxValue: GtxRtConversion() {
    override fun directHuman() = true
    override fun directCompact() = true
    override fun rtToGtx(rt: RtValue, human: Boolean) = rt.asGtxValue()
    override fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean) = RtGtxValue(gtx)
}

private fun gtxToInteger(gtx: GTXValue): Long {
    try {
        return gtx.asInteger()
    } catch (e: UserMistake) {
        throw errGtxType("integer", gtx, e)
    }
}

private fun gtxToBoolean(gtx: GTXValue): Boolean {
    val i = try {
        gtx.asInteger()
    } catch (e: UserMistake) {
        throw errGtxType("boolean", gtx, e)
    }
    if (i == 0L) {
        return false
    } else if (i == 1L) {
        return true
    } else {
        throw errGtxType("boolean", gtx, "Type error: expected boolean (0, 1), was $i")
    }
}

private fun gtxToString(gtx: GTXValue): String {
    try {
        return gtx.asString()
    } catch (e: UserMistake) {
        throw errGtxType("string", gtx, e)
    }
}

private fun gtxToByteArray(gtx: GTXValue): ByteArray {
    val str = try {
        gtx.asString()
    } catch (e: UserMistake) {
        throw errGtxType("byte_array", gtx, e)
    }
    try {
        return str.hexStringToByteArray()
    } catch (e: IllegalArgumentException) {
        throw errGtxType("byte_array", gtx, "Type error: invalid byte array string")
    }
}

private fun gtxToJson(gtx: GTXValue): RtValue {
    val str = try {
        gtx.asString()
    } catch (e: UserMistake) {
        throw errGtxType("json", gtx, e)
    }
    try {
        return RtJsonValue.parse(str)
    } catch (e: IllegalArgumentException) {
        throw errGtxType("json", gtx, "Type error: invalid JSON string")
    }
}

private fun gtxToArray(gtx: GTXValue): Array<out GTXValue> {
    try {
        return gtx.asArray()
    } catch (e: UserMistake) {
        throw errGtxType("array", gtx, e)
    }
}

private fun gtxToMap(gtx: GTXValue): Map<String, GTXValue> {
    try {
        return gtx.asDict()
    } catch (e: UserMistake) {
        throw errGtxType("dict", gtx, e)
    }
}

private fun errGtxType(expected: String, actual: GTXValue, e: UserMistake): RtGtxValueError {
    return errGtxType(expected, actual, e.message ?: "")
}

private fun errGtxType(expected: String, actual: GTXValue, message: String): RtGtxValueError {
    return RtGtxValueError("type:$expected:${actual.type}", message)
}
