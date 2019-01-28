package net.postchain.rell

import javax.xml.bind.DatatypeConverter

fun String.hexStringToByteArray() : ByteArray = DatatypeConverter.parseHexBinary(this)
fun ByteArray.toHex() : String = DatatypeConverter.printHexBinary(this).toLowerCase()

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
}
