/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.module

import net.postchain.base.BlockchainRid
import net.postchain.core.UserMistake
import net.postchain.gtv.*
import net.postchain.gtv.merkle.proof.GtvMerkleProofTreeFactory
import net.postchain.gtv.merkle.proof.toGtvVirtual
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.utils.Bytes32
import net.postchain.rell.utils.Bytes33
import net.postchain.rell.utils.toImmList
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.HashSetValuedHashMap

val GTV_QUERY_PRETTY = true
val GTV_OPERATION_PRETTY = false

class GtvToRtContext(val pretty: Boolean) {
    private val objectIds: MultiValuedMap<R_Entity, Long> = HashSetValuedHashMap()

    fun trackObject(entity: R_Entity, rowid: Long) {
        objectIds.put(entity, rowid)
    }

    fun finish(exeCtx: Rt_ExecutionContext) {
        for (rEntities in objectIds.keySet()) {
            val rowids = objectIds.get(rEntities)
            checkRowids(exeCtx.sqlExec, exeCtx.appCtx.sqlCtx, rEntities, rowids)
        }
    }

    private fun checkRowids(sqlExec: SqlExecutor, sqlCtx: Rt_SqlContext, rEntity: R_Entity, rowids: Collection<Long>) {
        val existingIds = selectExistingIds(sqlExec, sqlCtx, rEntity, rowids)
        val missingIds = rowids.toSet() - existingIds
        if (!missingIds.isEmpty()) {
            val s = missingIds.toList().sorted()
            val name = rEntity.appLevelName
            throw errGtv("obj_missing:[$name]:${missingIds.joinToString(",")}", "Missing objects of entity '$name': $s")
        }
    }

    private fun selectExistingIds(sqlExec: SqlExecutor, sqlCtx: Rt_SqlContext, rEntity: R_Entity, rowids: Collection<Long>): Set<Long> {
        val buf = StringBuilder()
        buf.append("\"").append(rEntity.sqlMapping.rowidColumn()).append("\" IN (")
        rowids.joinTo(buf, ",")
        buf.append(")")
        val whereSql = buf.toString()

        val sql = rEntity.sqlMapping.selectExistingObjects(sqlCtx, whereSql)
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

object GtvRtConversion_Decimal: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvFactory.gtv(Rt_DecimalUtils.toString(rt.asDecimal()))

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        return if (gtv.type == GtvType.INTEGER) {
            val v = gtvToInteger(gtv)
            Rt_DecimalValue.of(v)
        } else {
            val s = gtvToString(gtv)
            Rt_DecimalValue.of(s)
        }
    }
}

object GtvRtConversion_ByteArray: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvByteArray(rt.asByteArray())
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = Rt_ByteArrayValue(gtvToByteArray(gtv))
}

object GtvRtConversion_Rowid: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvInteger(rt.asRowid())

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val v = gtvToInteger(gtv)
        if (v < 0) {
            throw errGtv("rowid:negative:$v", "Negative value of $R_RowidType type: $v")
        }
        return Rt_RowidValue(v)
    }
}

object GtvRtConversion_Json: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)
    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvString(rt.asJsonString())
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = gtvToJson(gtv)
}

class GtvRtConversion_Entity(val type: R_EntityType): GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(type.rEntity.flags.gtv, type.rEntity.flags.gtv)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean) = GtvInteger(rt.asObjectId())

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val rowid = gtvToInteger(gtv)
        ctx.trackObject(type.rEntity, rowid)
        return Rt_EntityValue(type, rowid)
    }
}

class GtvRtConversion_Struct(private val struct: R_Struct): GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        val attrs = struct.attributesList
        if (pretty) {
            val rtStruct = rt.asStruct()
            val gtvFields = attrs.mapIndexed { i, attr -> Pair(attr.name, attr.type.rtToGtv(rtStruct.get(i), pretty)) }.toMap()
            return GtvFactory.gtv(gtvFields)
        } else {
            val rtStruct = rt.asStruct()
            val gtvFields = attrs.mapIndexed { i, attr -> attr.type.rtToGtv(rtStruct.get(i), pretty) }.toTypedArray()
            return GtvArray(gtvFields)
        }
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        return if (ctx.pretty && gtv.type == GtvType.DICT) gtvToRtDict(ctx, gtv) else gtvToRtArray(ctx, gtv)
    }

    private fun gtvToRtDict(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val type = struct.type
        val gtvFields = gtvToMap(gtv)
        checkFieldCount(type, struct, gtvFields.size)

        val attrs = struct.attributesList
        val rtFields = attrs.map { attr ->
            val key = attr.name
            if (key !in gtvFields) {
                val typeName = struct.appLevelName
                throw errGtv("struct_nokey:$typeName:$key", "Key missing in Gtv dictionary: field '$typeName.$key'")
            }
            val gtvField = gtvFields.getValue(key)
            attr.type.gtvToRt(ctx, gtvField)
        }.toMutableList()

        return Rt_StructValue(type, rtFields)
    }

    private fun gtvToRtArray(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val type = struct.type
        val gtvAttrValues = gtvToAttrValues(type, struct, gtv)
        val attrs = struct.attributesList
        val rtAttrValues = gtvAttrValues.mapIndexed { i, gtvField -> attrs[i].type.gtvToRt(ctx, gtvField) }.toMutableList()
        return Rt_StructValue(type, rtAttrValues)
    }

    companion object {
        fun gtvToAttrValues(type: R_Type, struct: R_Struct, gtv: Gtv): List<Gtv> {
            val gtvFields = gtvToArray(gtv)
            checkFieldCount(type, struct, gtvFields.size)
            return gtvFields.toList()
        }

        private fun checkFieldCount(type: R_Type, struct: R_Struct, actualCount: Int) {
            val expectedCount = struct.attributesList.size
            if (actualCount != expectedCount) {
                throw errWrongSize(type, expectedCount, actualCount)
            }
        }

        fun errWrongSize(type: R_Type, expectedCount: Int, actualCount: Int): Rt_BaseError {
            val typeName = type.name
            return errGtv("struct_size:$typeName:$expectedCount:$actualCount",
                    "Wrong Gtv array size for struct '$typeName': $actualCount instead of $expectedCount")
        }
    }
}

class GtvRtConversion_Enum(private val enum: R_Enum): GtvRtConversion() {
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
        val enumName = enum.appLevelName
        val attr = if (ctx.pretty && gtv.type == GtvType.STRING) {
            val name = gtvToString(gtv)
            val attr = enum.attr(name)
            if (attr == null) {
                throw errGtvType("enum[$enumName]", gtv, "Invalid value for enum '$enumName': '$name'")
            }
            attr
        } else {
            val value = gtvToInteger(gtv)
            val attr = enum.attr(value)
            if (attr == null) {
                throw errGtvType("enum[$enumName]", gtv, "Invalid value for enum '$enumName': $value")
            }
            attr
        }
        return Rt_EnumValue(enum.type, attr)
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
                    throw errGtv("set_dup:$elem", "Duplicate set element: $elem")
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
                    throw errGtv("map_dup_key:$key", "Map duplicate key: $key")
                }
                tmp[key] = value
            }
            tmp
        }
        return Rt_MapValue(type, map)
    }

    private fun gtvToRtEntry(ctx: GtvToRtContext, gtv: Gtv): Pair<Rt_Value, Rt_Value> {
        val array = gtvToArray(gtv, 2, "map_entry_size")
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

        val rtFields = type.fields.mapIndexed { _, field ->
            val key = field.name!!
            if (key !in gtvFields) {
                throw errGtv("tuple_nokey:$key", "Key missing in Gtv dictionary: '$key'")
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
                throw errGtv("tuple_count:$expectedCount:$actualCount",
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

object GtvRtConversion_Operation: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        val op = rt.asOperation()
        val name = op.op.str().toGtv()
        val args = op.args.map { it.type().rtToGtv(it, pretty) }.toGtv()
        return GtvFactory.gtv(name, args)
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val array = gtvToArray(gtv, 2, "operation")

        val nameStr = gtvToString(array[0])
        val name = R_MountName.ofOpt(nameStr)
        if (name == null || name.isEmpty()) {
            throw errGtv("operation:bad_name:$nameStr", "Invalid operation name: '$nameStr'")
        }

        val args = gtvToArray(array[1]).map { Rt_GtvValue(it) }.toImmList()
        return Rt_OperationValue(name, args)
    }
}

object GtvRtConversion_GtxTx: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        val tx = rt.asGtxTx()
        val body = GtvFactory.gtv(
                tx.blockchainRid.toGtv(),
                tx.ops.map { it.type().rtToGtv(it, pretty) }.toGtv(),
                tx.signers.map { it.toGtv() }.toGtv()
        )
        val signatures = tx.signatures.map { it.toGtv() }.toGtv()
        return GtvFactory.gtv(body, signatures)
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val txArray = gtvToArray(gtv, 2, "gtx_tx")

        val bodyArray = gtvToArray(txArray[0], 3, "gtx_tx:body")
        val blockchainRid = BlockchainRid(gtvToByteArray(bodyArray[0]))
        val ops = gtvToArray(bodyArray[1]).map { GtvRtConversion_Operation.gtvToRt(ctx, it).asOperation() }
        val signers = gtvToArray(bodyArray[2]).map { Bytes33(gtvToByteArray(it)) }

        val signatures = gtvToArray(txArray[1]).map { Bytes32(gtvToByteArray(it)) }
        return Rt_GtxTxValue(blockchainRid, ops, signers, signatures)
    }
}

object GtvRtConversion_GtxBlock: GtvRtConversion() {
    override fun directCompatibility() = R_GtvCompatibility(true, true)

    override fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv {
        val block = rt.asGtxBlock()
        val txs = block.txs.map { it.type().rtToGtv(it, pretty) }.toGtv()
        return txs
    }

    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val array = gtvToArray(gtv)
        val txs = array.map { GtvRtConversion_GtxTx.gtvToRt(ctx, it).asGtxTx() }
        return Rt_GtxBlockValue(txs)
    }
}

sealed class GtvRtConversion_Virtual: GtvRtConversion() {
    final override fun directCompatibility() = R_GtvCompatibility(true, false)
    final override fun rtToGtv(rt: Rt_Value, pretty: Boolean) =
            throw errGtv("virtual:to_gtv", "Cannot convert virtual to Gtv")

    companion object {
        fun deserialize(gtv: Gtv): Gtv {
            if (gtv !is GtvArray) {
                val cls = gtv.javaClass.simpleName
                throw errGtv("virtual:type:$cls", "Wrong Gtv type: $cls")
            }

            val proof = try {
                GtvMerkleProofTreeFactory().deserialize(gtv)
            } catch (e: Exception) {
                throw errGtv("virtual:deserialize:${e.javaClass.canonicalName}",
                        "Virtual proof deserialization failed: ${e.message}")
            }

            val virtual = proof.toGtvVirtual()
            return virtual
        }

        fun decodeVirtualElement(ctx: GtvToRtContext, type: R_Type, gtv: Gtv): Rt_Value {
            return when (type) {
                is R_StructType -> GtvRtConversion_VirtualStruct.decodeVirtualStruct(ctx, type.struct.virtualType, gtv)
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

class GtvRtConversion_VirtualStruct(private val type: R_VirtualStructType): GtvRtConversion_Virtual() {
    override fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv): Rt_Value {
        val virtual = deserialize(gtv)
        return decodeVirtualStruct(ctx, type, virtual)
    }

    companion object {
        fun decodeVirtualStruct(ctx: GtvToRtContext, type: R_VirtualStructType, v: Gtv): Rt_Value {
            val attrValues = decodeAttrs(type, v)
            val rtAttrValues = type.innerType.struct.attributesList.mapIndexed { i, attr ->
                val gtvAttr = if (i < attrValues.size) attrValues[i] else null
                if (gtvAttr == null) null else decodeVirtualElement(ctx, attr.type, gtvAttr)
            }
            return Rt_VirtualStructValue(v, type, rtAttrValues)
        }

        private fun decodeAttrs(type: R_VirtualStructType, v: Gtv): List<Gtv?> {
            return if (v !is GtvVirtual) {
                GtvRtConversion_Struct.gtvToAttrValues(type, type.innerType.struct, v)
            } else {
                decodeVirtualArray(type, v, type.innerType.struct.attributes.size)
            }
        }

        fun decodeVirtualArray(type: R_Type, v: Gtv, maxSize: Int): List<Gtv?> {
            if (v !is GtvVirtualArray) {
                val cls = v.javaClass.simpleName
                throw errGtv("virtual:deserialized_type:$cls", "Wrong deserialized Gtv type: $cls")
            }

            val actualCount = v.array.size
            if (actualCount > maxSize) {
                throw GtvRtConversion_Struct.errWrongSize(type, maxSize, actualCount)
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
                throw errGtv("virtual:deserialized_type:$cls", "Wrong deserialized Gtv type: $cls")
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
                    .mapValues { (_, v) -> decodeVirtualElement(ctx, type.innerType.valueType, v) }
                    .mapKeys { (k, _) -> Rt_TextValue(k) as Rt_Value }
            return Rt_VirtualMapValue(v, type, rtMap)
        }

        private fun decodeMap(v: Gtv): Map<String, Gtv> {
            if (v !is GtvVirtual) {
                return gtvToMap(v)
            }
            if (v !is GtvVirtualDictionary) {
                val cls = v.javaClass.simpleName
                throw errGtv("virtual:deserialized_type:$cls", "Wrong deserialized Gtv type: $cls")
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
                GtvRtConversion_VirtualStruct.decodeVirtualArray(type, v, type.innerType.fields.size)
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

private fun gtvToArray(gtv: Gtv, size: Int, errCode: String): Array<out Gtv> {
    val array = gtvToArray(gtv)
    val actSize = array.size
    if (actSize != size) {
        throw errGtv("$errCode:$size:$actSize", "Wrong gtv array size: $actSize instead of $size")
    }
    return array
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

private fun errGtvType(expected: String, actual: Gtv, e: UserMistake): Rt_BaseError {
    return errGtvType(expected, actual, e.message ?: "")
}

private fun errGtvType(expected: String, actual: Gtv, message: String): Rt_BaseError {
    return errGtv("type:$expected:${actual.type}", message)
}

private fun errGtv(code: String, msg: String) = Rt_GtvError(code, msg)
