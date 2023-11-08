/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.mtype

import net.postchain.rell.base.utils.Nullable
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.toImmList

object M_TupleTypeUtils {
    private val NULL_NAMES = (0 .. 50)
        .map { n ->
            (0 until n).map { Nullable.of<String>(null) }.toImmList()
        }
        .toImmList()

    private fun getNullNames(n: Int): List<Nullable<String>> {
        check(n > 0) { n }
        return if (n < NULL_NAMES.size) NULL_NAMES[n] else (0 until n).map { Nullable.of(null) }
    }

    fun <T> makeNames(fields: List<T>, nameGetter: (T) -> String?): List<Nullable<String>> {
        return if (fields.all { nameGetter(it) == null }) {
            getNullNames(fields.size)
        } else {
            fields.map { Nullable.of(nameGetter(it)) }.toImmList()
        }
    }

    fun makeType(types: List<M_Type>): M_Type {
        val names = getNullNames(types.size)
        return makeType(types, names)
    }

    fun makeType(types: List<M_Type>, names: List<Nullable<String>>): M_Type {
        return M_Type_Tuple_Internal(types, names)
    }
}

sealed class M_Type_Tuple(
    fieldTypes: List<M_Type>,
    fieldNames: List<Nullable<String>>,
): M_Type_Composite(fieldTypes.size) {
    val fieldTypes = fieldTypes.toImmList()
    val fieldNames = fieldNames.toImmList()

    init {
        check(this.fieldTypes.isNotEmpty())
        checkEquals(this.fieldNames.size, this.fieldTypes.size)
    }
}

private class M_Type_Tuple_Internal(
    fieldTypes: List<M_Type>,
    fieldNames: List<Nullable<String>>,
): M_Type_Tuple(fieldTypes, fieldNames) {
    override val canonicalArgs: List<M_TypeSet> = fieldTypes.map { M_TypeSets.one(it) }.toImmList()

    override fun strCode(): String {
        return fieldNames.indices.joinToString(",", "(", ")") { i ->
            val name = fieldNames[i].value
            val typeStr = fieldTypes[i].strCode()
            if (name == null) typeStr else "$name:$typeStr"
        }
    }

    override fun equalsComposite0(other: M_Type_Composite): Boolean =
        other is M_Type_Tuple_Internal && fieldNames == other.fieldNames

    override fun hashCodeComposite0() = fieldNames.hashCode()

    override fun getTypeArgVariance(index: Int) = M_TypeVariance.OUT

    override fun captureWildcards(): M_Type = this

    override fun newInstance(newArgs: List<M_TypeSet>): M_Type_Composite {
        checkEquals(newArgs.size, fieldNames.size)
        val newFieldTypes = newArgs.map { it.canonicalOutType() }
        return M_Type_Tuple_Internal(newFieldTypes, fieldNames)
    }

    override fun getCorrespondingSuperType(otherType: M_Type_Composite): M_Type_Composite? {
        return if (otherType is M_Type_Tuple_Internal && otherType.fieldNames == fieldNames) this else null
    }

    override fun validate() {
        for (fieldType in fieldTypes) {
            fieldType.validate()
        }
    }
}
