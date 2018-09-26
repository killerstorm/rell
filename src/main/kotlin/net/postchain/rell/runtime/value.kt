package net.postchain.rell.runtime

sealed class RtValue

class RtIntValue(val value: Long): RtValue() {
    override fun toString(): String = "int[$value]"
}

class RtTextValue(val value: String): RtValue() {
    override fun toString(): String = "text[$value]"
}

class RtEnv {
    private val values = mutableListOf<RtValue?>()

    fun set(offset: Int, value: RtValue) {
        while (values.size <= offset) {
            values.add(null)
        }
        values[offset] = value
    }

    fun get(offset: Int): RtValue {
        val value = values[offset]
        if (value == null) {
            throw RuntimeException("Value not set: $offset")
        } else {
            return value
        }
    }
}
