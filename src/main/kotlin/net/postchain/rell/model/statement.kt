package net.postchain.rell.model

sealed class RStatement
class RCreateStatement(val rclass: RClass, val attrs: Array<RAttrExpr>): RStatement()
class RUpdateStatement(val atExpr: RAtExpr, val setAttrs: Array<RAttrExpr>): RStatement()
class RDeleteStatement(val atExpr: RAtExpr): RStatement()
class RCallStatement(val expr: RFunCallExpr): RStatement()
class RFromStatement(val atExpr: RAttrExpr, val attrs: Array<RAttrib>): RStatement()