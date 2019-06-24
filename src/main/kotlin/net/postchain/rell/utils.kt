package net.postchain.rell

import net.postchain.base.SECP256K1CryptoSystem
import net.postchain.base.data.PostgreSQLCommands
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkleHash
import java.io.File
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
}

object PostchainUtils {
    val cryptoSystem = SECP256K1CryptoSystem()

    private val merkleCalculator = GtvMerkleHashCalculator(cryptoSystem)

    fun gtvToBytes(v: Gtv): ByteArray = GtvEncoder.encodeGtv(v)
    fun bytesToGtv(v: ByteArray): Gtv = GtvFactory.decodeGtv(v)
    fun xmlToGtv(s: String): Gtv = GtvMLParser.parseGtvML(s)
    fun gtvToXml(v: Gtv): String = GtvMLEncoder.encodeXMLGtv(v)

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
