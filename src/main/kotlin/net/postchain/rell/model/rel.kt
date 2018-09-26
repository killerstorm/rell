package net.postchain.rell.model

class RKey(val attribs: Array<String>)
class RIndex(val attribs: Array<String>)
class RClass (val name: String, val keys: Array<RKey>, val indexes: Array<RIndex>, val attributes: Array<RAttrib>)
