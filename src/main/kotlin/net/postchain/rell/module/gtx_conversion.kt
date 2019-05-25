package net.postchain.rell.module

import net.postchain.core.UserMistake
import net.postchain.gtv.*
import net.postchain.gtv.merkle.proof.GtvMerkleProofTreeFactory
import net.postchain.gtv.merkle.proof.toGtvVirtual
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlExecutor
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.HashSetValuedHashMap

val GTV_QUERY_PRETTY = true
val GTV_OPERATION_PRETTY = false

class GtvToRtContext(val pretty: Boolean) {
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
    abstract fun directCompatibility(): R_GtvCompatibility
    abstract fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv
    abstract fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value
}

object GtvRtConversion_None: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(false, false)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = throw UnsupportedOperationException()
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = throw UnsupportedOperationException()
}

object GtvRtConversion_Null: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        check(rt == Rt_NullValue)
        return GtvNull
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        check(gtv.isNull())
        return Rt_NullValue
    }
}

object GtvRtConversion_Boolean: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvInteger(if (rt.asBoolean()) 1L else 0L)
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_BooleanValue(gtvToBoolean(gtv))
}

object GtvRtConversion_Text: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvString(rt.asString())
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_TextValue(gtvToString(gtv))
}

object GtvRtConversion_Integer: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvInteger(rt.asInteger())
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_IntValue(gtvToInteger(gtv))
}

object GtvRtConversion_ByteArray: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvByteArray(rt.asByteArray())
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_ByteArrayValue(gtvToByteArray(gtv))
}

object GtvRtConversion_Json: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvString(rt.asJsonString())
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = gtvToJson(gtv)
}

class GtvRtConversion_Class(val type: R_ClassType): GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(type.rClass.flags.gtv, type.rClass.flags.gtv)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvInteger(rt.asObjectId())

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val rowid = gtvToInteger(gtv)
        ctx.trackObject(type.rClass, rowid)
        return Rt_ClassValue(type, rowid)
    }
}

class GtvRtConversion_Record(val type: R_RecordType): GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        val attrs = type.attributesList
        if (pretty) {
            val record = rt.asRecord()
            val gtvFields = attrs.mapIndexed { i, attr -> Pair(attr.name, attr.type.rtToGtv(record.get(i), pretty)) }.toMap()
            return GtvFactory.gtv(gtvFields)
        } else {
            val record = rt.asRecord()
            val gtvFields = attrs.mapIndexed { i, attr -> attr.type.rtToGtv(record.get(i), pretty) }.toTypedArray()
            return GtvArray(gtvFields)
        }
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        return if (ctx.pretty && gtv.type == GtvType.DICT) gtvToRtDict(ctx, gtv) else gtvToRtArray(ctx, gtv)
    }

    private fun gtvToRtDict(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val gtvFields = gtvToMap(gtv)
        checkFieldCount(type, type, gtvFields.size)

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
        val gtvAttrValues = gtvToAttrValues(type, type, gtv)
        val attrs = type.attributesList
        val rtAttrValues = gtvAttrValues.mapIndexed { i, gtvField -> attrs[i].type.gtvToRt(ctx, gtvField) }.toMutableList()
        return Rt_RecordValue(type, rtAttrValues)
    }

    companion object {
        fun gtvToAttrValues(type: R_Type, recordType: R_RecordType, gtv: Gtv): List<Gtv> {
            val gtvFields = gtvToArray(gtv)
            checkFieldCount(type, recordType, gtvFields.size)
            return gtvFields.toList()
        }

        private fun checkFieldCount(type: R_Type, recordType: R_RecordType, actualCount: Int) {
            val expectedCount = recordType.attributesList.size
            if (actualCount != expectedCount) {
                throw errWrongSize(type, expectedCount, actualCount)
            }
        }

        fun errWrongSize(type: R_Type, expectedCount: Int, actualCount: Int): Rt_BaseError {
            val typeName = type.name
            return Rt_GtvError("record_size:$typeName:$expectedCount:$actualCount",
                    "Wrong Gtv array size for record $typeName: $actualCount instead of $expectedCount")
        }
    }
}

class GtvRtConversion_Enum(val type: R_EnumType): GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        val e = rt.asEnum()
        if (pretty) {
            return GtvString(e.name)
        } else {
            return GtvInteger(e.value.toLong())
        }
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        if (ctx.pretty && gtv.type == GtvType.STRING) {
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
    override fun directCompatibility() = R_GtvCompatibility(true, true)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        return if (rt == Rt_NullValue) {
            GtvNull
        } else {
            type.valueType.rtToGtv(rt, pretty)
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
    final override fun directCompatibility() = R_GtvCompatibility(true, true)

    final override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        val elementType = type.elementType
        return GtvArray(rt.asCollection().map { elementType.rtToGtv(it, pretty) }.toTypedArray())
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
        val list = gtvToArray(gtv).map { type.elementType.gtvToRt(ctx, it) }
        val set = listToSet(list)
        return Rt_SetValue(type, set)
    }

    companion object {
        fun listToSet(elements: Iterable<Rt_Value>): MutableSet<Rt_Value> {
            val set = mutableSetOf<Rt_Value>()
            for (elem in elements) {
                if (!set.add(elem)) {
                    throw Rt_GtvError("set_dup:$elem", "Duplicate set element: $elem")
                }
            }
            return set
        }
    }
}

class GtvRtConversion_Map(val type: R_MapType): GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        val keyType = type.keyType
        val valueType = type.valueType
        val m = rt.asMap()
        if (keyType == R_TextType) {
            val m2 = m.mapKeys { (k, _) -> k.asString() }
                    .mapValues { (_, v) -> valueType.rtToGtv(v, pretty) }
            return GtvFactory.gtv(m2)
        } else {
            val entries = m.map { (k, v) -> GtvArray(arrayOf(keyType.rtToGtv(k, pretty), valueType.rtToGtv(v, pretty))) }
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
    override fun directCompatibility() = R_GtvCompatibility(true, true)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        return if (pretty && type.fields.all { it.name != null }) rtToGtvPretty(rt) else rtToGtvCompact(rt)
    }

    private fun rtToGtvPretty(rt: Rt_Value): Gtv {
        val rtFields = rt.asTuple()
        check(rtFields.size == type.fields.size)
        val gtv = rtFields.mapIndexed { i, rtField ->
            val field = type.fields[i]
            Pair(field.name!!, field.type.rtToGtv(rtField, true))
        }.toMap()
        return GtvFactory.gtv(gtv)
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
        return if (ctx.pretty && type.fields.all { it.name != null } && gtv.type == GtvType.DICT) {
            gtvToRtDict(ctx, gtv)
        } else {
            gtvToRtArray(ctx, gtv)
        }
    }

    private fun gtvToRtDict(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val gtvFields = gtvToMap(gtv)
        checkFieldCount(type, gtvFields.size, "dictionary")

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
        val gtvFields = gtvArrayToFields(type, gtv)
        val rtFields = gtvFields.mapIndexed { i, gtvField ->
            type.fields[i].type.gtvToRt(ctx, gtvField)
        }
        return Rt_TupleValue(type, rtFields)
    }

    companion object {
        fun gtvArrayToFields(type: R_TupleType, gtv: Gtv): List<Gtv> {
            val gtvFields = gtvToArray(gtv)
            checkFieldCount(type, gtvFields.size, "array")
            return gtvFields.toList()
        }

        private fun checkFieldCount(type: R_TupleType, actualCount: Int, structure: String) {
            val expectedCount = type.fields.size
            if (actualCount != expectedCount) {
                throw Rt_GtvError("tuple_count:$expectedCount:$actualCount",
                        "Wrong Gtv $structure size: $actualCount instead of $expectedCount")
            }
        }
    }
}

object GtvRtConversion_Gtv: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = rt.asGtv()
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_GtvValue(gtv)
}

sealed class GtvRtConversion_Virtual: GtvRtConversion() {
    final override fun directCompatibility() = R_GtvCompatibility(true, false)
    final override fun rtToGtv(rt: Rt_Value, pretty: Boolean) =
            throw Rt_GtvError("virtual:to_gtv", "Cannot convert virtual to Gtv")

    companion object {
        fun deserialize(gtv: Gtv): Gtv {
            if (gtv !is GtvArray) {
                val cls = gtv.javaClass.simpleName
                throw Rt_GtvError("virtual:type:$cls", "Wrong Gtv type: $cls")
            }

            val proof = try {
                GtvMerkleProofTreeFactory().deserialize(gtv)
            } catch (e: Exception) {
                throw Rt_GtvError("virtual:deserialize:${e.javaClass.canonicalName}",
                        "Virtual proof deserialization failed: ${e.message}")
            }

            val virtual = proof.toGtvVirtual()
            return virtual
        }

        fun decodeVirtualElement(ctx: GtvToRtContext, type: R_Type, gtv: Gtv): Rt_Value {
            return when (type) {
                is R_RecordType -> GtvRtConversion_VirtualRecord.decodeVirtualRecord(ctx, type.virtualType, gtv)
                is R_ListType -> GtvRtConversion_VirtualList.decodeVirtualList(ctx, type.virtualType, gtv)
                is R_SetType -> GtvRtConversion_VirtualSet.decodeVirtualSet(ctx, type.virtualType, gtv)
                is R_MapType -> GtvRtConversion_VirtualMap.decodeVirtualMap(ctx, type.virtualType, gtv)
                is R_TupleType -> GtvRtConversion_VirtualTuple.decodeVirtualTuple(ctx, type.virtualType, gtv)
                is R_NullableType -> if (gtv.isNull()) Rt_NullValue else decodeVirtualElement(ctx, type.valueType, gtv)
                else -> type.gtvToRt(ctx, gtv)
            }
        }
    }
}

class GtvRtConversion_VirtualRecord(private val type: R_VirtualRecordType): GtvRtConversion_Virtual() {
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val virtual = deserialize(gtv)
        return decodeVirtualRecord(ctx, type, virtual)
    }

    companion object {
        fun decodeVirtualRecord(ctx: GtvToRtContext, type: R_VirtualRecordType, v: Gtv): Rt_Value {
            val attrValues = decodeAttrs(type, v)
            val rtAttrValues = type.innerType.attributesList.mapIndexed { i, attr ->
                val gtvAttr = if (i < attrValues.size) attrValues[i] else null
                if (gtvAttr == null) null else decodeVirtualElement(ctx, attr.type, gtvAttr)
            }
            return Rt_VirtualRecordValue(v, type, rtAttrValues)
        }

        private fun decodeAttrs(type: R_VirtualRecordType, v: Gtv): List<Gtv?> {
            return if (v !is GtvVirtual) {
                GtvRtConversion_Record.gtvToAttrValues(type, type.innerType, v)
            } else {
                decodeVirtualArray(type, v, type.innerType.attributes.size)
            }
        }

        fun decodeVirtualArray(type: R_Type, v: Gtv, maxSize: Int): List<Gtv?> {
            if (v !is GtvVirtualArray) {
                val cls = v.javaClass.simpleName
                throw Rt_GtvError("virtual:deserialized_type:$cls", "Wrong deserialized Gtv type: $cls")
            }

            val actualCount = v.array.size
            if (actualCount > maxSize) {
                throw GtvRtConversion_Record.errWrongSize(type, maxSize, actualCount)
            }

            return v.array.toList()
        }
    }
}

class GtvRtConversion_VirtualList(private val type: R_VirtualListType): GtvRtConversion_Virtual() {
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val virtual = deserialize(gtv)
        return decodeVirtualList(ctx, type, virtual)
    }

    companion object {
        fun decodeVirtualList(ctx: GtvToRtContext, type: R_VirtualListType, v: Gtv): Rt_Value {
            val rtElements = decodeVirtualElements(ctx, type.innerType, v)
            return Rt_VirtualListValue(v, type, rtElements)
        }

        fun decodeVirtualElements(ctx: GtvToRtContext, innerType: R_CollectionType, v: Gtv): List<Rt_Value?> {
            val gtvElements = decodeElements(v)
            val rtElements = gtvElements.map {
                if (it == null) null else decodeVirtualElement(ctx, innerType.elementType, it)
            }
            return rtElements
        }

        private fun decodeElements(v: Gtv): List<Gtv?> {
            if (v !is GtvVirtual) {
                return gtvToArray(v).toList()
            }
            if (v !is GtvVirtualArray) {
                val cls = v.javaClass.simpleName
                throw Rt_GtvError("virtual:deserialized_type:$cls", "Wrong deserialized Gtv type: $cls")
            }
            return v.array.toList()
        }
    }
}

class GtvRtConversion_VirtualSet(private val type: R_VirtualSetType): GtvRtConversion_Virtual() {
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val virtual = deserialize(gtv)
        return decodeVirtualSet(ctx, type, virtual)
    }

    companion object {
        fun decodeVirtualSet(ctx: GtvToRtContext, type: R_VirtualSetType, v: Gtv): Rt_Value {
            val rtList = GtvRtConversion_VirtualList.decodeVirtualElements(ctx, type.innerType, v)
            val rtSet = GtvRtConversion_Set.listToSet(rtList.filterNotNull())
            return Rt_VirtualSetValue(v, type, rtSet)
        }
    }
}

class GtvRtConversion_VirtualMap(private val type: R_VirtualMapType): GtvRtConversion_Virtual() {
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val virtual = deserialize(gtv)
        return decodeVirtualMap(ctx, type, virtual)
    }

    companion object {
        fun decodeVirtualMap(ctx: GtvToRtContext, type: R_VirtualMapType, v: Gtv): Rt_Value {
            val gtvMap = decodeMap(v)
            val rtMap = gtvMap
                    .mapValues { (k, v) -> decodeVirtualElement(ctx, type.innerType.valueType, v) }
                    .mapKeys { (k, v) -> Rt_TextValue(k) as Rt_Value }
            return Rt_VirtualMapValue(v, type, rtMap)
        }

        private fun decodeMap(v: Gtv): Map<String, Gtv> {
            if (v !is GtvVirtual) {
                return gtvToMap(v)
            }
            if (v !is GtvVirtualDictionary) {
                val cls = v.javaClass.simpleName
                throw Rt_GtvError("virtual:deserialized_type:$cls", "Wrong deserialized Gtv type: $cls")
            }
            return v.dict
        }
    }
}

class GtvRtConversion_VirtualTuple(val type: R_VirtualTupleType): GtvRtConversion_Virtual() {
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val virtual = deserialize(gtv)
        return decodeVirtualTuple(ctx, type, virtual)
    }

    companion object {
        fun decodeVirtualTuple(ctx: GtvToRtContext, type: R_VirtualTupleType, v: Gtv): Rt_Value {
            val fieldValues = decodeFields(type, v)
            val rtFieldValues = type.innerType.fields.mapIndexed { i, attr ->
                val gtvAttr = if (i < fieldValues.size) fieldValues[i] else null
                if (gtvAttr == null) null else decodeVirtualElement(ctx, attr.type, gtvAttr)
            }
            return Rt_VirtualTupleValue(v, type, rtFieldValues)
        }

        private fun decodeFields(type: R_VirtualTupleType, v: Gtv): List<Gtv?> {
            return if (v !is GtvVirtual) {
                GtvRtConversion_Tuple.gtvArrayToFields(type.innerType, v)
            } else {
                GtvRtConversion_VirtualRecord.decodeVirtualArray(type, v, type.innerType.fields.size)
            }
        }
    }
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
