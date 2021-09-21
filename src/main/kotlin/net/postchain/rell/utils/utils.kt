/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils

import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import org.apache.commons.lang3.StringUtils
import java.util.*
import java.util.function.Supplier

typealias Getter<T> = () -> T

data class Nullable<T>(val value: T? = null)
fun <T> Nullable<T>?.orElse(other: T?): T? = if (this != null) this.value else other

data class One<T>(val value: T)

class MutableTypedKeyMap {
    private val map = mutableMapOf<TypedKey<Any>, Any>()

    fun <V> put(key: TypedKey<V>, value: V) {
        key as TypedKey<Any>
        check(key !in map)
        map[key] = value as Any
    }

    fun immutableCopy(): TypedKeyMap {
        return TypedKeyMap(map.toMap())
    }
}

class TypedKeyMap(private val map: Map<TypedKey<Any>, Any> = mapOf()) {
    fun <V> get(key: TypedKey<V>): V {
        key as TypedKey<Any>
        val value = map.getValue(key)
        return value as V
    }
}

class TypedKey<V>

class Bytes {
    private val bytes: ByteArray

    private constructor(bytes: ByteArray) {
        this.bytes = bytes
    }

    fun size() = bytes.size

    fun toByteArray() = bytes.clone()
    fun toHex() = bytes.toHex()

    override fun equals(other: Any?) = other === this || (other is Bytes && Arrays.equals(bytes, other.bytes))
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = bytes.toHex()

    companion object {
        fun of(bytes: ByteArray) = Bytes(bytes.clone())
        fun of(text: String) = Bytes(text.toByteArray())
    }
}

abstract class FixLenBytes(bytes: ByteArray) {
    private val bytes: ByteArray = let {
        val size = size()
        check(bytes.size == size) { "Wrong size: ${bytes.size} instead of $size" }
        bytes.clone()
    }

    abstract fun size(): Int

    fun toByteArray() = bytes.clone()
    fun toHex() = bytes.toHex()
    fun toGtv(): Gtv = GtvFactory.gtv(bytes.clone())

    override fun equals(other: Any?) = other === this
            || (other is FixLenBytes && javaClass == other.javaClass && bytes.contentEquals(other.bytes))
    override fun hashCode() = Arrays.hashCode(bytes)
    override fun toString() = bytes.toHex()
}

class Bytes32(bytes: ByteArray): FixLenBytes(bytes) {
    override fun size() = 32

    companion object {
        fun parse(s: String): Bytes32 {
            val bytes = s.hexStringToByteArray()
            return Bytes32(bytes)
        }
    }
}

class Bytes33(bytes: ByteArray): FixLenBytes(bytes) {
    override fun size() = 33

    companion object {
        fun parse(s: String): Bytes33 {
            val bytes = s.hexStringToByteArray()
            return Bytes33(bytes)
        }
    }
}

class BytesKeyPair(val priv: Bytes32, val pub: Bytes33) {
    constructor(priv: ByteArray, pub: ByteArray): this(Bytes32(priv), Bytes33(pub))
}

class LateInit<T> {
    val getter = LateGetter(this)
    val setter = LateSetter(this)

    private var t: T? = null

    fun isSet(): Boolean = t != null

    fun get(): T = t!!

    fun set(v: T) {
        check(t == null) { "value already initialized with: <$t>" }
        t = v
    }
}

class LateGetter<T>(private val init: LateInit<T>): Supplier<T> {
    override fun get(): T = init.get()

    companion object {
        fun <T> of(value: T): LateGetter<T> {
            val init = LateInit<T>()
            init.set(value)
            return init.getter
        }
    }
}

class LateSetter<T>(private val init: LateInit<T>) {
    fun set(value: T) = init.set(value)
}

class ThreadLocalContext<T>(private val defaultValue: T? = null) {
    private val local = ThreadLocal.withInitial<T> { defaultValue }

    fun <R> set(value: T, code: () -> R): R {
        val old = local.get()
        local.set(value)
        try {
            val res = code()
            return res
        } finally {
            local.set(old)
        }
    }

    fun get(): T {
        val res = local.get()
        check(res != null)
        return res
    }

    fun getOpt(): T? = local.get()
}

class VersionNumber(items: List<Int>): Comparable<VersionNumber> {
    val items = items.toImmList()

    init {
        for (v in this.items) require(v >= 0) { "wrong version: ${this.items}" }
    }

    fun str(): String = items.joinToString(".")

    override fun compareTo(other: VersionNumber) = CommonUtils.compareLists(items, other.items)
    override fun equals(other: Any?) = other === this || (other is VersionNumber && items == other.items)
    override fun hashCode() = items.hashCode()
    override fun toString() = str()

    companion object {
        fun of(s: String): VersionNumber {
            require(s.matches(Regex("(0|[1-9][0-9]*)([.](0|[1-9][0-9]*))*")))
            val parts = StringUtils.splitPreserveAllTokens(s, ".")
            val items = parts.map { it.toInt() }
            return VersionNumber(items)
        }
    }
}

class MsgString(s: String) {
    val normal = s.toLowerCase()
    val upper = s.toUpperCase()
    val capital = s.capitalize()

    override fun equals(other: Any?) = other is MsgString && normal == other.normal
    override fun hashCode() = normal.hashCode()
    override fun toString() = normal
}

fun <T> checkEquals(actual: T, expected: T) {
    check(expected == actual) { "expected <$expected> actual <$actual>" }
}
