package net.postchain.rell.model

sealed class RExpr(val type: RType)
class RVarRef(type: RType, val _var: RAttrib): RExpr(type)
class RAtExpr(type: RType, val rel: RRel, val attrConditions: List<Pair<RAttrib, RExpr>>): RExpr(type)
class RBinOpExpr(type: RType, val op: String, val left: RExpr, val right: RExpr): RExpr(type)
class RStringLiteral(type: RType, val literal: String): RExpr(type)
class RByteALiteral(type: RType, val literal: ByteArray): RExpr(type)
class RIntegerLiteral(type: RType, val literal: Long): RExpr(type)
class RFunCallExpr(type: RType, val fname: String, val args: List<RExpr>): RExpr(type)
class RAttrExpr(val attr: RAttrib, val expr: RExpr)