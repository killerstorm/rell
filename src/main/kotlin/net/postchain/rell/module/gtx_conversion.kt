package net.postchain.rell.module

import net.postchain.core.UserMistake
import net.postchain.gtv.*
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlExecutor
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.HashSetValuedHashMap

val GTV_QUERY_HUMAN = true
val GTV_OPERATION_HUMAN = false

class GtvToRtContext(val human: Boolean) {
    private val objectIds: MultiValuedMap<R_Class, Long> = HashSetValuedHashMap()

    fun trackObject(cls: R_Class, rowid: Long) {
        objectIds.put(cls, rowid)
    }

    fun finish(modCtx: Rt_ModuleContext) {
        for (rClass in objectIds.keySet()) {
            val rowids = objectIds.get(rClass)
            checkRowids(modCtx.globalCtx.sqlExec, modCtx.sqlCtx, rClass, rowids)
        }
    }

    private fun checkRowids(sqlExec: SqlExecutor, sqlCtx: Rt_SqlContext, rClass: R_Class, rowids: Collection<Long>) {
        val existingIds = selectExistingIds(sqlExec, sqlCtx, rClass, rowids)
        val missingIds = rowids.toSet() - existingIds
        if (!missingIds.isEmpty()) {
            val s = missingIds.toList().sorted()
            throw Rt_GtvError("obj_missing:${rClass.name}:${missingIds.joinToString(",")}",
                    "Missing objects of class '${rClass.name}': $s")
        }
    }

    private fun selectExistingIds(sqlExec: SqlExecutor, sqlCtx: Rt_SqlContext, rClass: R_Class, rowids: Collection<Long>): Set<Long> {
        val buf = StringBuilder()
        buf.append("\"").append(rClass.sqlMapping.rowidColumn()).append("\" IN (")
        rowids.joinTo(buf, ",")
        buf.append(")")
        val whereSql = buf.toString()

        val sql = rClass.sqlMapping.selectExistingObjects(sqlCtx, whereSql)
        val existingIds = mutableSetOf<Long>()
        sqlExec.executeQuery(sql, {}) { existingIds.add(it.getLong(1)) }
        return existingIds
    }
}

sealed class GtvRtConversion {
    abstract fun directHuman(): R_GtvCompatibility
    abstract fun directCompact(): R_GtvCompatibility
    abstract fun rtToGtv(rt: Rt_Value, human: Boolean): Gtv
    abstract fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value
}

object GtvRtConversion_None: GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(false)
    override fun directCompact() = R_GtvCompatibility(false)
    override fun rtToGtv(rt: Rt_Value, human: Boolean) = throw UnsupportedOperationException()
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = throw UnsupportedOperationException()
}

object GtvRtConversion_Null: GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)

    override fun rtToGtv(rt: Rt_Value, human: Boolean): Gtv {
        check(rt == Rt_NullValue)
        return GtvNull
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        check(gtv.isNull())
        return Rt_NullValue
    }
}

object GtvRtConversion_Boolean: GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)
    override fun rtToGtv(rt: Rt_Value, human: Boolean) = GtvInteger(if (rt.asBoolean()) 1L else 0L)
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_BooleanValue(gtvToBoolean(gtv))
}

object GtvRtConversion_Text: GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)
    override fun rtToGtv(rt: Rt_Value, human: Boolean) = GtvString(rt.asString())
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_TextValue(gtvToString(gtv))
}

object GtvRtConversion_Integer: GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)
    override fun rtToGtv(rt: Rt_Value, human: Boolean) = GtvInteger(rt.asInteger())
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_IntValue(gtvToInteger(gtv))
}

object GtvRtConversion_ByteArray: GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)
    override fun rtToGtv(rt: Rt_Value, human: Boolean) = GtvByteArray(rt.asByteArray())
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_ByteArrayValue(gtvToByteArray(gtv))
}

object GtvRtConversion_Json: GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)
    override fun rtToGtv(rt: Rt_Value, human: Boolean) = GtvString(rt.asJsonString())
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = gtvToJson(gtv)
}

class GtvRtConversion_Class(val type: R_ClassType): GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(type.rClass.flags.gtv)
    override fun directCompact() = R_GtvCompatibility(type.rClass.flags.gtv)

    override fun rtToGtv(rt: Rt_Value, human: Boolean) = GtvInteger(rt.asObjectId())

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val rowid = gtvToInteger(gtv)
        ctx.trackObject(type.rClass, rowid)
        return Rt_ClassValue(type, rowid)
    }
}

class GtvRtConversion_Record(val type: R_RecordType): GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)

    override fun rtToGtv(rt: Rt_Value, human: Boolean): Gtv {
        val attrs = type.attributesList
        if (human) {
            val record = rt.asRecord()
            val gtvFields = attrs.mapIndexed { i, attr -> Pair(attr.name, attr.type.rtToGtv(record.get(i), human)) }.toMap()
            return GtvDictionary(gtvFields)
        } else {
            val record = rt.asRecord()
            val gtvFields = attrs.mapIndexed { i, attr -> attr.type.rtToGtv(record.get(i), human) }.toTypedArray()
            return GtvArray(gtvFields)
        }
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        return if (ctx.human && gtv.type == GtvType.DICT) gtvToRtDict(ctx, gtv) else gtvToRtArray(ctx, gtv)
    }

    private fun gtvToRtDict(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val gtvFields = gtvToMap(gtv)
        checkFieldCount(gtvFields.size)

        val attrs = type.attributesList
        val rtFields = attrs.map { attr ->
            val key = attr.name
            if (key !in gtvFields) {
                val typeName = type.name
                throw Rt_GtvError("record_nokey:$typeName:$key", "Key missing in Gtv dictionary: field $typeName.$key")
            }
            val gtvField = gtvFields.getValue(key)
            attr.type.gtvToRt(ctx, gtvField)
        }.toMutableList()

        return Rt_RecordValue(type, rtFields)
    }

    private fun gtvToRtArray(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val gtvFields = gtvToArray(gtv)
        checkFieldCount(gtvFields.size)

        val attrs = type.attributesList
        val rtFields = gtvFields.mapIndexed { i, gtvField -> attrs[i].type.gtvToRt(ctx, gtvField) }.toMutableList()
        return Rt_RecordValue(type, rtFields)
    }

    private fun checkFieldCount(actualCount: Int) {
        val expectedCount = type.attributesList.size
        if (actualCount != expectedCount) {
            val typeName = type.name
            throw Rt_GtvError("record_size:$typeName:$expectedCount:$actualCount",
                    "Wrong Gtv array size for record $typeName: $actualCount instead of $expectedCount")
        }
    }
}

class GtvRtConversion_Enum(val type: R_EnumType): GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)

    override fun rtToGtv(rt: Rt_Value, human: Boolean): Gtv {
        val e = rt.asEnum()
        if (human) {
            return GtvString(e.name)
        } else {
            return GtvInteger(e.value.toLong())
        }
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        if (ctx.human && gtv.type == GtvType.STRING) {
            val name = gtvToString(gtv)
            val attr = type.attr(name)
            if (attr == null) {
                throw errGtvType("enum[${type.name}]", gtv, "Invalid enum value: '$name'")
            }
            return Rt_EnumValue(type, attr)
        } else {
            val value = gtvToInteger(gtv)
            val attr = type.attr(value)
            if (attr == null) {
                throw errGtvType("enum[${type.name}]", gtv, "Invalid enum value: $value")
            }
            return Rt_EnumValue(type, attr)
        }
    }
}

class GtvRtConversion_Nullable(val type: R_NullableType): GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)

    override fun rtToGtv(rt: Rt_Value, human: Boolean): Gtv {
        return if (rt == Rt_NullValue) {
            GtvNull
        } else {
            type.valueType.rtToGtv(rt, human)
        }
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        return if (gtv.isNull()) {
            Rt_NullValue
        } else {
            type.valueType.gtvToRt(ctx, gtv)
        }
    }
}

sealed class GtvRtConversion_Collection(val type: R_CollectionType): GtvRtConversion() {
    final override fun directHuman() = R_GtvCompatibility(true)
    final override fun directCompact() = R_GtvCompatibility(true)

    final override fun rtToGtv(rt: Rt_Value, human: Boolean): Gtv {
        val elementType = type.elementType
        return GtvArray(rt.asCollection().map { elementType.rtToGtv(it, human) }.toTypedArray())
    }
}

class GtvRtConversion_List(type: R_ListType): GtvRtConversion_Collection(type) {
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val elementType = type.elementType
        val lst = gtvToArray(gtv).map { elementType.gtvToRt(ctx, it) }.toMutableList()
        return Rt_ListValue(type, lst)
    }
}

class GtvRtConversion_Set(type: R_SetType): GtvRtConversion_Collection(type) {
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val elementType = type.elementType
        val set = mutableSetOf<Rt_Value>()

        for (gtvElem in gtvToArray(gtv)) {
            val rtElem = elementType.gtvToRt(ctx, gtvElem)
            if (!set.add(rtElem)) {
                throw Rt_GtvError("set_dup:$rtElem", "Duplicate set element: $rtElem")
            }
        }

        return Rt_SetValue(type, set)
    }
}

class GtvRtConversion_Map(val type: R_MapType): GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)

    override fun rtToGtv(rt: Rt_Value, human: Boolean): Gtv {
        val keyType = type.keyType
        val valueType = type.valueType
        val m = rt.asMap()
        if (keyType == R_TextType) {
            val m2 = m.mapKeys { (k, _) -> k.asString() }
                    .mapValues { (_, v) -> valueType.rtToGtv(v, human) }
            return GtvDictionary(m2)
        } else {
            val entries = m.map { (k, v) -> GtvArray(arrayOf(keyType.rtToGtv(k, human), valueType.rtToGtv(v, human))) }
            return GtvArray(entries.toTypedArray())
        }
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val map = if (type.keyType == R_TextType && gtv.type == GtvType.DICT) {
            gtvToMap(gtv)
                    .mapKeys { (k, _) -> Rt_TextValue(k) as Rt_Value }
                    .mapValues { (_, v) -> type.valueType.gtvToRt(ctx, v) }
                    .toMutableMap()
        } else {
            val tmp = mutableMapOf<Rt_Value, Rt_Value>()
            for (gtvEntry in gtvToArray(gtv)) {
                val (key, value) = gtvToRtEntry(ctx, gtvEntry)
                if (key in tmp) {
                    throw Rt_GtvError("map_dup_key:$key", "Map duplicate key: $key")
                }
                tmp[key] = value
            }
            tmp
        }
        return Rt_MapValue(type, map)
    }

    private fun gtvToRtEntry(ctx: GtvToRtContext, gtv: Gtv): Pair<Rt_Value, Rt_Value> {
        val array = gtvToArray(gtv)
        if (array.size != 2) {
            throw Rt_GtvError("map_entry_size:${array.size}", "Map entry size is ${array.size}")
        }
        val key = type.keyType.gtvToRt(ctx, array[0])
        val value = type.valueType.gtvToRt(ctx, array[1])
        return Pair(key, value)
    }
}

class GtvRtConversion_Tuple(val type: R_TupleType): GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)

    override fun rtToGtv(rt: Rt_Value, human: Boolean): Gtv {
        return if (human && type.fields.all { it.name != null }) rtToGtvHuman(rt) else rtToGtvCompact(rt)
    }

    private fun rtToGtvHuman(rt: Rt_Value): Gtv {
        val rtFields = rt.asTuple()
        check(rtFields.size == type.fields.size)
        val gtv = rtFields.mapIndexed { i, rtField ->
            val field = type.fields[i]
            Pair(field.name!!, field.type.rtToGtv(rtField, true))
        }.toMap()
        return GtvDictionary(gtv)
    }

    private fun rtToGtvCompact(rt: Rt_Value): Gtv {
        val rtFields = rt.asTuple()
        check(rtFields.size == type.fields.size)
        val gtvFields = rtFields.mapIndexed { i, rtField ->
            type.fields[i].type.rtToGtv(rtField, false)
        }.toTypedArray()
        return GtvArray(gtvFields)
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        return if (ctx.human && type.fields.all { it.name != null } && gtv.type == GtvType.DICT) {
            gtvToRtDict(ctx, gtv)
        } else {
            gtvToRtArray(ctx, gtv)
        }
    }

    private fun gtvToRtDict(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val gtvFields = gtvToMap(gtv)
        checkFieldCount(gtvFields.size, "dictionary")

        val rtFields = type.fields.mapIndexed { i, field ->
            val key = field.name!!
            if (key !in gtvFields) {
                throw Rt_GtvError("tuple_nokey:$key", "Key missing in Gtv dictionary: '$key'")
            }
            val gtvField = gtvFields.getValue(key)
            field.type.gtvToRt(ctx, gtvField)
        }

        return Rt_TupleValue(type, rtFields)
    }

    private fun gtvToRtArray(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val gtvFields = gtvToArray(gtv)
        checkFieldCount(gtvFields.size, "array")

        val rtFields = gtvFields.mapIndexed { i, gtvField ->
            type.fields[i].type.gtvToRt(ctx, gtvField)
        }.toList()

        return Rt_TupleValue(type, rtFields)
    }

    private fun checkFieldCount(actualCount: Int, structure: String) {
        val expectedCount = type.fields.size
        if (actualCount != expectedCount) {
            throw Rt_GtvError("tuple_count:$expectedCount:$actualCount",
                    "Wrong Gtv $structure size: $actualCount instead of $expectedCount")
        }
    }
}

object GtvRtConversion_Gtv: GtvRtConversion() {
    override fun directHuman() = R_GtvCompatibility(true)
    override fun directCompact() = R_GtvCompatibility(true)
    override fun rtToGtv(rt: Rt_Value, human: Boolean) = rt.asGtv()
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_GtvValue(gtv)
}

private fun gtvToInteger(gtv: Gtv): Long {
    try {
        return gtv.asInteger()
    } catch (e: UserMistake) {
        throw errGtvType("integer", gtv, e)
    }
}

private fun gtvToBoolean(gtv: Gtv): Boolean {
    val i = try {
        gtv.asInteger()
    } catch (e: UserMistake) {
        throw errGtvType("boolean", gtv, e)
    }
    if (i == 0L) {
        return false
    } else if (i == 1L) {
        return true
    } else {
        throw errGtvType("boolean", gtv, "Type error: expected boolean (0, 1), was $i")
    }
}

private fun gtvToString(gtv: Gtv): String {
    try {
        return gtv.asString()
    } catch (e: UserMistake) {
        throw errGtvType("string", gtv, e)
    }
}

private fun gtvToByteArray(gtv: Gtv): ByteArray {
    try {
        // TODO: This allows interpreting string as byte array.
        // This is a temporary measure needed because of deficiency of the query API.
        // Auto-conversion should be removed later.
        return gtv.asByteArray(true)
    } catch (e: UserMistake) {
        throw errGtvType("byte_array", gtv, e)
    }
}

private fun gtvToJson(gtv: Gtv): Rt_Value {
    val str = try {
        gtv.asString()
    } catch (e: UserMistake) {
        throw errGtvType("json", gtv, e)
    }
    try {
        return Rt_JsonValue.parse(str)
    } catch (e: IllegalArgumentException) {
        throw errGtvType("json", gtv, "Type error: invalid JSON string")
    }
}

private fun gtvToArray(gtv: Gtv): Array<out Gtv> {
    try {
        return gtv.asArray()
    } catch (e: UserMistake) {
        throw errGtvType("array", gtv, e)
    }
}

private fun gtvToMap(gtv: Gtv): Map<String, Gtv> {
    try {
        return gtv.asDict()
    } catch (e: UserMistake) {
        throw errGtvType("dict", gtv, e)
    }
}

private fun errGtvType(expected: String, actual: Gtv, e: UserMistake): Rt_GtvError {
    return errGtvType(expected, actual, e.message ?: "")
}

private fun errGtvType(expected: String, actual: Gtv, message: String): Rt_GtvError {
    return Rt_GtvError("type:$expected:${actual.type}", message)
}
