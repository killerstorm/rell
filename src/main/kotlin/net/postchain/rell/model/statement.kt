package net.postchain.rell.model

sealed class RStatement

class RValStatement(val offset: Int, val attr: RAttrib, val expr: RExpr): RStatement()
class RCallStatement(val expr: RFunCallExpr): RStatement()
class RReturnStatement(val expr: RExpr): RStatement()

class RCreateStatement(val rclass: RClass, val attrs: Array<RAttrExpr>): RStatement()
class RUpdateStatement(val atExpr: RAtExpr, val setAttrs: Array<RAttrExpr>): RStatement()
class RDeleteStatement(val atExpr: RAtExpr): RStatement()
