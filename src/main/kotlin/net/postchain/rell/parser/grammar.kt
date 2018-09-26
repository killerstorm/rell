package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.parser.Parser
import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.grammar.parser
import net.postchain.rell.hexStringToByteArray

object S_Grammar : Grammar<S_ModuleDefinition>() {
    private val ws by token("\\s+", ignore = true)
    private val LPAR by token("\\(")
    private val RPAR by token("\\)")
    private val LCURL by token("\\{")
    private val RCURL by token("\\}")
    private val AT by token("@")
    private val COLON by token(":")
    private val SEMI by token(";")
    private val COMMA by token(",")


    private val A_OPERATOR by token("[\\+\\-\\*/%]")
    private val C_OPERATOR by token("==|!=|[<>]=?")
    private val L_OPERATOR by token("or\\b|and\\b|not\\b")

    private val EQLS by token("=")

    val CREATE by token("create\\b")
    val UPDATE by token("update\\b")
    val DELETE by token("delete\\b")
    val FROM by token("from\\b")
    val SELECT by token("select\\b")
    val WHERE by token("where\\b")
    val CLASS by token("class\\b")
    val REL by token("rel\\b")
    val KEY by token("key\\b")
    val INDEX by token("index\\b")
    val OPER by token("operation\\b")
    val QUERY by token("query\\b")
    val VAL by token("val\\b")
    val RETURN by token("return\\b")
    private val NUMBER by token("\\d+")
    val HEXLIT by token("x\"[0123456789abcdefABCDEF]*\"")
    val STRINGLIT by token("\".*?\"")
    val IDT by token("\\w+")
    val id by (IDT) use { text }

    val relAutoAttribute by (id) map { S_Attribute(it, it) }
    val relNamedAttribute by (id * -COLON * id) map { (name, type) -> S_Attribute(name, type) }
    val relAttribute by (relNamedAttribute or relAutoAttribute)
    val relAttributes by separatedTerms(relAttribute, COMMA, false)

    val relKey by (-KEY * relAttributes) map { S_KeyClause(it) }
    val relIndex by (-INDEX * relAttributes) map { S_IndexClause(it) }

    val anyRelClause by (relKey or relIndex or (relAttribute map { S_AttributeClause(it) }))

    val relClauses by zeroOrMore(anyRelClause * -SEMI)

    val classDef by (-CLASS * id * -LCURL * relClauses * -RCURL) map {
        (identifier, clauses) ->
        val rel = relFromClauses(identifier, clauses)
        S_ClassDefinition(identifier, rel.attributes, rel.keys, rel.indices)
    }

    val relDef by (-REL * id * -LCURL * relClauses * -RCURL) map {
        (identifier, clauses) ->
        relFromClauses(identifier, clauses)
    }

    val varExpr by (id map { S_VarRef(it) })
    val integerLiteral = (NUMBER map { S_IntLiteral(it.text.toLong()) })
    val stringLiteral = (STRINGLIT map { S_StringLiteral(it.text.removeSurrounding("\"", "\"")) })
    val hexLiteral = (HEXLIT map {
        S_ByteALiteral(
                it.text.removeSurrounding("x\"", "\"").hexStringToByteArray())
    })
    val literalExpr = (stringLiteral or hexLiteral or integerLiteral)

    val operator = (A_OPERATOR or L_OPERATOR or C_OPERATOR) map { it.text }
    val binExpr : Parser<S_BinOp> = (-LPAR * parser(this::anyExpr) * operator * parser(this::anyExpr) * -RPAR) map {
        (lexpr, oper, rexpr) ->
        S_BinOp(oper, lexpr, rexpr)
    }

    val whereExpr_expl: Parser<S_BinOp> by (id * -EQLS * parser(this::anyExpr)) map {
        (key, value) ->
        S_BinOp("=", S_VarRef(key), value)
    }

    val whereExpr_C: Parser<S_BinOp> by (id * C_OPERATOR * parser(this::anyExpr)) map {
        (key, op, value) ->
        S_BinOp(op.text, S_VarRef(key), value)
    }

    val whereExpr_impl : Parser<S_BinOp> by (parser(this::anyExpr)) map { e ->
        S_BinOp("=", S_VarRef(inferName(e)), e)
    }

    val whereExpr = (whereExpr_expl or whereExpr_C or whereExpr_impl)

    val atExpr : Parser<S_AtExpr> by (id * -AT * -LCURL * separatedTerms(whereExpr, COMMA, false) * -RCURL) map {
        (relname, attrs) ->
        S_AtExpr(relname, attrs)
    }

    val funcallExpr : Parser<S_FunCallExpr> by (id * -LPAR * separatedTerms(parser(this::anyExpr), COMMA, true) * -RPAR) map {
        (fname, args) ->
        S_FunCallExpr(fname, args)
    }

    val anyExpr by (funcallExpr or binExpr or literalExpr or atExpr or varExpr)

    val valStatement by (-VAL * id * -EQLS * anyExpr * -SEMI) map { (varname, expr) -> S_BindStatement(varname, expr) }

    val callStatement by ( id * -LPAR * separatedTerms(anyExpr, COMMA, false) * -RPAR * -SEMI) map {
        (fname, args) ->
        S_CallStatement(fname, args)
    }

    val attrExpr_expl = (id * -EQLS * anyExpr) map { (i, e) -> S_AttrExpr(i, e) }
    val attrExpr_impl = (anyExpr) map { e ->
        S_AttrExpr(inferName(e), e)
    }
    val anyAttrExpr by (attrExpr_expl or attrExpr_impl)

    val createStatement by (-CREATE * id * -LPAR * separatedTerms(anyAttrExpr, COMMA, true) * -RPAR * -SEMI) map {
        (classname, values) ->
        S_CreateStatement(classname, values)
    }

    val updateStatement by (-UPDATE * atExpr * -LPAR * separatedTerms(anyAttrExpr, COMMA, false) * -RPAR * -SEMI) map {
        (atExpr, values) ->
        S_UpdateStatement(atExpr, values)
    }

    val deleteStatement by (-DELETE * atExpr * -SEMI) map {
        S_DeleteStatement(it)
    }

    val fromStatement by (-FROM * atExpr * -SELECT * separatedTerms(id, COMMA, false)) map {
        (_atExpr, identifers) ->
        S_FromStatement(_atExpr, identifers)
    }

    val returnStatement by (-RETURN * anyExpr * -SEMI) map {
        expr ->
        S_ReturnStatement(expr)
    }

    val statement by (valStatement or createStatement or callStatement or updateStatement or deleteStatement or returnStatement)

    val opDefinition by (-OPER * id * -LPAR * separatedTerms(relAttribute, COMMA, true) * -RPAR
            * -LCURL * oneOrMore(statement) * -RCURL) map {
        (identifier, args, statements) ->
        S_OpDefinition(identifier, args, statements)
    }

    val queryBodyShort by (-EQLS * anyExpr * -SEMI) map {
        expr ->
        S_QueryBodyShort(expr)
    }

    val queryBodyFull by (-LCURL * oneOrMore(statement) * -RCURL) map {
        statements ->
        S_QueryBodyFull(statements)
    }

    val queryDefinition by (-QUERY * id * -LPAR * separatedTerms(relAttribute, COMMA, true) * -RPAR
            * (queryBodyShort or queryBodyFull)) map {
        (identifier, args, body) ->
        S_QueryDefinition(identifier, args, body)
    }

    val anyDef by (classDef or relDef or opDefinition or queryDefinition)

    override val rootParser by oneOrMore(anyDef) map { S_ModuleDefinition(it) }

    /*val term by
    (id use { Variable(text) }) or
            (-not * parser(this::term) map { (Not(it) }) or
            (-lpar * parser(this::rootParser) * -rpar)

    val andChain by leftAssociative(term, and) { l, _, r -> And(l, r) }
    override val rootParser by leftAssociative(andChain, or) { l, _, r -> Or(l, r) }*/
}

private fun relFromClauses(identifier: String, clauses: List<S_RelClause>): S_RelDefinition {
    val attributes = mutableMapOf<String, String>()
    val keys = mutableListOf<S_Key>()
    val indices = mutableListOf<S_Index>()

    fun addAttr(a: S_Attribute) {
        if (a.name in attributes) {
            if (a.type != attributes[a.name])
                throw Exception("Conflicting attribute type")
        } else
            attributes[a.name] = a.type
    }

    for (clause in clauses) {
        when (clause) {
            is S_AttributeClause -> addAttr(clause.attr)
            is S_KeyClause -> {
                keys.add(S_Key(clause.attrs.map { it.name }))
                clause.attrs.forEach { addAttr(it) }
            }
            is S_IndexClause -> {
                indices.add(S_Index(clause.attrs.map { it.name }))
                clause.attrs.forEach { addAttr(it) }
            }
        }.let {} // ensure exhaustiveness
    }
    return S_RelDefinition(identifier,
            attributes.entries.map { S_Attribute(it.key, it.value) },
            keys, indices
    )
}


private fun inferName(e: S_Expression): String {
    return when (e) {
        is S_AtExpr -> e.clasname
        is S_VarRef -> e.varname
        else -> throw Exception("Cannot automatically infer name of expression")
    }
}


fun parseRellCode(s: String): S_ModuleDefinition {
    return S_Grammar.parseToEnd(s)
}


/*

    company JOIN user ON user.company_id = company.id


    company JOIN user
        WHERE user.company_id = company.id


 issuer@{name='Bob'}


from {company, user, city }@{ user.company_id = company_id, city.id =user.city_id}
from (company, user, city)@{ user.company_id = company_id, city.id =user.city_id}
from(company, user, city)@{ user.company_id = company_id, city.id =user.city_id}
from user select name;
from (user, fdldskfjsd, as)@{name='Bob'} select name


from (user as u, company as c)@{
    u.company_id = c.id
} select (foo, bar);


  (a + (b + c))

  a + b + c


SELECT FROM
        */