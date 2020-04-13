/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils

import com.google.gson.GsonBuilder
import net.postchain.base.BlockchainRid
import net.postchain.base.SECP256K1CryptoSystem
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.gtv.*
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import java.util.*
import java.util.function.Supplier

object PostchainUtils {
    val DATABASE_VERSION = 1

    val cryptoSystem = SECP256K1CryptoSystem()

    private val merkleCalculator = GtvMerkleHashCalculator(cryptoSystem)

    private val GSON = make_gtv_gson()

    private val PRETTY_GSON = GsonBuilder()
            .registerTypeAdapter(Gtv::class.java, GtvAdapter())
            .serializeNulls()
            .setPrettyPrinting()
            .create()!!

    fun gtvToBytes(v: Gtv): ByteArray = GtvEncoder.encodeGtv(v)
    fun bytesToGtv(v: ByteArray): Gtv = GtvFactory.decodeGtv(v)

    fun xmlToGtv(s: String): Gtv = GtvMLParser.parseGtvML(s)
    fun gtvToXml(v: Gtv): String = GtvMLEncoder.encodeXMLGtv(v)

    fun gtvToJson(v: Gtv): String = GSON.toJson(v, Gtv::class.java)
    fun jsonToGtv(s: String): Gtv = GSON.fromJson<Gtv>(s, Gtv::class.java) ?: GtvNull
    fun gtvToJsonPretty(v: Gtv): String = PRETTY_GSON.toJson(v, Gtv::class.java)

    fun merkleHash(v: Gtv): ByteArray = v.merkleHash(merkleCalculator)

    fun hexToRid(s: String): BlockchainRid = BlockchainRid(CommonUtils.hexToBytes(s))

    fun createDatabaseAccess(): SQLDatabaseAccess = PostgreSQLDatabaseAccess()

    fun calcBlockchainRid(config: Gtv): Bytes32 {
        val hash = merkleHash(config)
        return Bytes32(hash)
    }
}

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

    override fun equals(other: Any?) = other is Bytes && Arrays.equals(bytes, other.bytes)
    override fun hashCode() = Arrays.hashCode(bytes)
    override fun toString() = bytes.toHex()

    companion object {
        fun of(bytes: ByteArray) = Bytes(bytes.clone())
        fun of(text: String) = Bytes(text.toByteArray())
    }
}

abstract class FixLenBytes(bytes: ByteArray) {
    private val bytes: ByteArray

    init {
        val size = size()
        check(bytes.size == size) { "Wrong size: ${bytes.size} instead of $size" }
        this.bytes = bytes.clone()
    }

    abstract fun size(): Int

    fun toByteArray() = bytes.clone()
    fun toHex() = bytes.toHex()

    override fun equals(other: Any?) = other is FixLenBytes && javaClass == other.javaClass && Arrays.equals(bytes, other.bytes)
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
}

typealias Getter<T> = () -> T

data class Nullable<T>(val value: T?)
