package net.postchain.rell.model

class RKey(val attribs: List<String>)
class RIndex(val attribs: List<String>)
class RClass (val name: String, val keys: List<RKey>, val indexes: List<RIndex>, val attributes: List<RAttrib>)
