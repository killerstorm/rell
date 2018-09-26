package net.postchain.rell.model

class RKey(val attribs: Array<String>)
class RIndex(val attribs: Array<String>)
open class RRel (val name: String, val keys: Array<RKey>, val indexes: Array<RIndex>, val attributes: Array<RAttrib>)
class RClass (name: String, keys: Array<RKey>, indexes: Array<RIndex>, attributes: Array<RAttrib>)
    : RRel(name, keys, indexes, attributes)