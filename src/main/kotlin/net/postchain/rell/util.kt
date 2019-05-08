package net.postchain.rell

import net.postchain.base.SECP256K1CryptoSystem
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkleHash
import java.io.File
import javax.xml.bind.DatatypeConverter

fun String.hexStringToByteArray(): ByteArray = DatatypeConverter.parseHexBinary(this)
fun ByteArray.toHex(): String = DatatypeConverter.printHexBinary(this).toLowerCase()

object CommonUtils {
    fun <T> split(lst: MutableList<T>, partSize: Int): List<MutableList<T>> {
        val s = lst.size
        if (s <= partSize) {
            return listOf(lst)
        }

        val parts = (s + partSize - 1) / partSize
        val res = (0 until parts).map { lst.subList(it * partSize, Math.min((it + 1) * partSize, s)) }
        return res
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
