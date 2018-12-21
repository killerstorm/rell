package net.postchain.rell.model

class RKey(val attribs: List<String>)

class RIndex(val attribs: List<String>)

class RClassBody(val keys: List<RKey>, val indexes: List<RIndex>, val attributes: Map<String, RAttrib>)

class RClass(val name: String) {
    private lateinit var body: RClassBody
    val keys: List<RKey> get() = body.keys
    val indexes: List<RIndex> get() = body.indexes
    val attributes: Map<String, RAttrib> get() = body.attributes

    fun setBody(body: RClassBody) {
        this.body = body
    }
}
