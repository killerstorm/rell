package net.postchain.rell.model

class R_Key(val attribs: List<String>)

class R_Index(val attribs: List<String>)

class R_ClassBody(val keys: List<R_Key>, val indexes: List<R_Index>, val attributes: Map<String, R_Attrib>)

class R_Class(val name: String) {
    private lateinit var body: R_ClassBody
    val keys: List<R_Key> get() = body.keys
    val indexes: List<R_Index> get() = body.indexes
    val attributes: Map<String, R_Attrib> get() = body.attributes

    fun setBody(body: R_ClassBody) {
        this.body = body
    }
}
