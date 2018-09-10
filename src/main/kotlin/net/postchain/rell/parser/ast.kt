package net.postchain.rell.parser

class S_Attribute(val name: String, val type: String)
class S_Key(val attrNames: List<String>)
class S_Index(val attrNames: List<String>)
sealed class S_Expression
class S_VarRef(val varname: String): S_Expression()
class S_StringLiteral(val literal: String): S_Expression()
class S_ByteALiteral(val bytes: ByteArray): S_Expression()
class S_IntLiteral(val value: Long): S_Expression()
class S_BinOp(val op: String, val left: S_Expression, val right: S_Expression): S_Expression()
class S_AtExpr(val clasname: String, val where: List<S_BinOp>): S_Expression()
class S_FunCallExpr(val fname: String, val args: List<S_Expression>): S_Expression()
class S_AttrExpr(val name: String, val expr: S_Expression)
sealed class S_Statement
class S_BindStatement(val varname: String, val expr: S_Expression): S_Statement()
class S_CreateStatement(val classname: String, val attrs: List<S_AttrExpr>): S_Statement()
class S_CallStatement(val fname: String, val args: List<S_Expression>): S_Statement()
class S_FromStatement(val from: S_AtExpr, val attrs: List<String>): S_Statement()
class S_UpdateStatement(val what: S_AtExpr, val attrs: List<S_AttrExpr>): S_Statement()
class S_DeleteStatement(val what: S_AtExpr): S_Statement()
sealed class S_RelClause
class S_AttributeClause(val attr: S_Attribute): S_RelClause()
class S_KeyClause(val attrs: List<S_Attribute>): S_RelClause()
class S_IndexClause(val attrs: List<S_Attribute>): S_RelClause()
sealed class S_Definition(val identifier: String)
open class S_RelDefinition (identifier: String, val attributes: List<S_Attribute>,
                            val keys: List<S_Key>, val indices: List<S_Index>): S_Definition(identifier)

class S_ClassDefinition (identifier: String, attributes: List<S_Attribute>,
                         keys: List<S_Key>, indices: List<S_Index>)
    : S_RelDefinition(identifier, attributes, keys, indices)

class S_OpDefinition(identifier: String, var args: List<S_Attribute>, val statements: List<S_Statement>): S_Definition(identifier)
class S_QueryDefinition(identifier: String, var args: List<S_Attribute>, val statements: List<S_Statement>): S_Definition(identifier)
class S_ModuleDefinition(val definitions: List<S_Definition>)