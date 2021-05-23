/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils

import com.google.gson.GsonBuilder
import net.postchain.base.BlockchainRid
import net.postchain.base.CryptoSystem
import net.postchain.base.SECP256K1CryptoSystem
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.base.merkle.MerkleHashCalculator
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.core.UserMistake
import net.postchain.gtv.*
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtx.StandardOpsGTXModule
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_MountName
import net.postchain.rell.module.GtvToRtContext
import net.postchain.rell.runtime.Rt_ChainContext
import net.postchain.rell.runtime.Rt_Value
import org.apache.commons.lang3.StringUtils
import java.util.*
import java.util.function.Supplier

fun <T> checkEquals(actual: T, expected: T) {
    check(expected == actual) { "expected <$expected> actual <$actual>" }
}

object PostchainUtils {
    val DATABASE_VERSION = 1

    val cryptoSystem: CryptoSystem = SECP256K1CryptoSystem()

    private val merkleCalculator: MerkleHashCalculator<Gtv> = GtvMerkleHashCalculator(cryptoSystem)

    private val GSON = make_gtv_gson()

    private val PRETTY_GSON = GsonBuilder()
            .registerTypeAdapter(Gtv::class.java, GtvAdapter())
            .serializeNulls()
            .setPrettyPrinting()
            .create()!!

    val STD_OPS: Set<R_MountName>
    val STD_QUERIES: Set<R_MountName>

    init {
        val m = StandardOpsGTXModule()
        STD_OPS = m.getOperations().map { R_MountName.of(it) }.toImmSet()
        STD_QUERIES = m.getQueries().map { R_MountName.of(it) }.toImmSet()
    }

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

    fun createChainContext(rawConfig: Gtv, rApp: R_App, blockchainRid: BlockchainRid): Rt_ChainContext {
        val gtxNode = rawConfig.asDict().getValue("gtx")
        val rellNode = gtxNode.asDict().getValue("rell")
        val gtvArgsDict = rellNode.asDict()["moduleArgs"]?.asDict() ?: mapOf()

        val moduleArgs = mutableMapOf<R_ModuleName, Rt_Value>()

        for (rModule in rApp.modules) {
            val argsStruct = rModule.moduleArgs

            if (argsStruct != null) {
                val gtvArgs = gtvArgsDict[rModule.name.str()]
                if (gtvArgs == null) {
                    throw UserMistake("No moduleArgs in blockchain configuration for module '${rModule.name}', " +
                            "but type ${argsStruct.moduleLevelName} defined in the code")
                }

                val convCtx = GtvToRtContext(true)
                val rtArgs = argsStruct.type.gtvToRt(convCtx, gtvArgs)
                moduleArgs[rModule.name] = rtArgs
            }
        }

        return Rt_ChainContext(rawConfig, moduleArgs, blockchainRid)
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
    fun toGtv(): Gtv = GtvFactory.gtv(bytes.clone())

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
}

class VersionNumber(items: List<Int>): Comparable<VersionNumber> {
    val items = items.toImmList()

    init {
        for (v in this.items) require(v >= 0) { "wrong version: ${this.items}" }
    }

    fun str(): String = items.joinToString(".")

    override fun compareTo(other: VersionNumber) = CommonUtils.compareLists(items, other.items)
    override fun equals(other: Any?) = other is VersionNumber && items == other.items
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

typealias Getter<T> = () -> T

data class Nullable<T>(val value: T? = null)

fun <T> Nullable<T>?.orElse(other: T?): T? = if (this != null) this.value else other

class MsgString(s: String) {
    val normal = s.toLowerCase()
    val upper = s.toUpperCase()
    val capital = StringUtils.capitalize(s)

    override fun equals(other: Any?) = other is MsgString && normal == other.normal
    override fun hashCode() = normal.hashCode()
    override fun toString() = normal
}
