package net.postchain.rell

import javax.xml.bind.DatatypeConverter

fun String.hexStringToByteArray() : ByteArray = DatatypeConverter.parseHexBinary(this)
fun ByteArray.toHex() : String = DatatypeConverter.printHexBinary(this).toLowerCase()
