package net.postchain.rell

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import net.postchain.base.SECP256K1CryptoSystem
import net.postchain.base.data.PostgreSQLCommands
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.gtv.*
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import java.io.File
import java.util.*
import javax.xml.bind.DatatypeConverter

object CommonUtils {
    fun bytesToHex(bytes: ByteArray): String = DatatypeConverter.printHexBinary(bytes).toLowerCase()
    fun hexToBytes(hex: String): ByteArray = DatatypeConverter.parseHexBinary(hex)

    fun <T> split(lst: MutableList<T>, partSize: Int): List<MutableList<T>> {
        val s = lst.size
        if (s <= partSize) {
            return listOf(lst)
        }

        val parts = (s + partSize - 1) / partSize
        val res = (0 until parts).map { lst.subList(it * partSize, Math.min((it + 1) * partSize, s)) }
        return res
    }

    fun <T: Comparable<T>> compareLists(l1: List<T>, l2: List<T>): Int {
        val n1 = l1.size
        val n2 = l2.size
        for (i in 0 until Math.min(n1, n2)) {
            val c = l1[i].compareTo(l2[i])
            if (c != 0) {
                return c
            }
        }
        return n1.compareTo(n2)
    }

    /** Invokes the key getter strictly one time for each item (builds list of pairs (item, key), then sorts). */
    fun <T, K: Comparable<K>> sortedByCopy(data: Collection<T>, keyGetter: (T) -> K): List<T> {
        val pairs = data.map { Pair(it, keyGetter(it)) }.sortedBy { it.second }
        return pairs.map { it.first }
    }

    fun readFileContent(filename: String): String {
        /*
        * FYI: We use Spring convention here when files under resources are labeled with prefix 'classpath:'.
        * */
        val resourcePrefix = "classpath:"
        return if (filename.startsWith(resourcePrefix)) {
            javaClass.getResource(filename.substringAfter(resourcePrefix))
                    .readText()
        } else {
            File(filename).readText()
        }
    }

    fun <T> calcOpt(f: () -> T): T? {
        try {
            return f()
        } catch (e: Throwable) {
            return null
        }
    }

    fun <T> foldSimple(items: Iterable<T>, op: (T, T) -> T): T {
        val iter = items.iterator()
        check(iter.hasNext())

        var res = iter.next()
        while (iter.hasNext()) {
            var item = iter.next()
            res = op(res, item)
        }

        return res
    }
}

object PostchainUtils {
    val cryptoSystem = SECP256K1CryptoSystem()

    private val merkleCalculator = GtvMerkleHashCalculator(cryptoSystem)

    private val GSON = make_gtv_gson()

    fun gtvToBytes(v: Gtv): ByteArray = GtvEncoder.encodeGtv(v)
    fun bytesToGtv(v: ByteArray): Gtv = GtvFactory.decodeGtv(v)

    fun xmlToGtv(s: String): Gtv = GtvMLParser.parseGtvML(s)
    fun gtvToXml(v: Gtv): String = GtvMLEncoder.encodeXMLGtv(v)

    fun gtvToJson(v: Gtv): String = GSON.toJson(v, Gtv::class.java)
    fun jsonToGtv(s: String): Gtv = GSON.fromJson<Gtv>(s, Gtv::class.java) ?: GtvNull

    fun merkleHash(v: Gtv): ByteArray = v.merkleHash(merkleCalculator)

    fun createDatabaseAccess(): SQLDatabaseAccess = PostgreSQLDatabaseAccess(PostgreSQLCommands)
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

abstract class GeneralDir {
    abstract fun absolutePath(path: String): String
    abstract fun parentPath(path: String): String
    abstract fun subPath(path1: String, path2: String): String

    abstract fun readTextOpt(path: String): String?

    fun readText(path: String): String {
        val res = readTextOpt(path)
        if (res == null) {
            val fullPath = absolutePath(path)
            throw IllegalArgumentException("File not found: $fullPath")
        }
        return res
    }
}

class DiskGeneralDir(private val dir: File): GeneralDir() {
    override fun absolutePath(path: String) = pathToFile(path).absolutePath
    override fun parentPath(path: String) = pathToFile(path).parent
    override fun subPath(path1: String, path2: String) = File(path1, path2).path

    override fun readTextOpt(path: String): String? {
        val file = pathToFile(path)
        if (!file.exists()) return null
        val text = file.readText()
        return text
    }

    private fun pathToFile(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(dir, path)
    }
}

class MapGeneralDir(private val files: Map<String, String>): GeneralDir() {
    override fun absolutePath(path: String) = normalPath(path)

    override fun parentPath(path: String): String {
        val parts = splitPath(path)
        check(!parts.isEmpty())
        val res = joinPath(parts.subList(0, parts.size - 1))
        return res
    }

    override fun subPath(path1: String, path2: String): String {
        val p1 = splitPath(path1)
        val p2 = splitPath(path2)
        val res = joinPath(p1 + p2)
        return res
    }

    override fun readTextOpt(path: String): String? {
        val normPath = normalPath(path)
        val res = files[normPath]
        return res
    }

    private fun normalPath(path: String) = joinPath(splitPath(path))
    private fun splitPath(path: String) = if (path == "") listOf() else path.split("/+").toList()
    private fun joinPath(path: List<String>) = path.joinToString("/")
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

class DirBuilder {
    private val files = mutableMapOf<String, String>()

    fun put(path: String, text: String) {
        check(path.isNotBlank())
        check(path !in files) { "Duplicate file: $path" }
        files[path] = text
    }

    fun put(map: Map<String, String>) {
        for ((path, text) in map) {
            put(path, text)
        }
    }

    fun toFileMap() = files.toMap()
}

class LateInit<T> {
    private var t: T? = null

    fun isSet(): Boolean = t != null

    fun get(): T = t!!

    fun set(v: T) {
        check(t == null) { "value already initialized with: <$t>" }

        t = v
    }
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
typealias Setter<T> = (T) -> Unit

fun <T> Iterable<T>.toImmList(): List<T> = ImmutableList.copyOf(this)
fun <T> Iterable<T>.toImmSet(): Set<T> = ImmutableSet.copyOf(this)
fun <K, V> Map<K, V>.toImmMap(): Map<K, V> = ImmutableMap.copyOf(this)
