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
    private val DOT by token("\\.")

    private val EQ by token("=")
    private val EQEQ by token("==")
    private val NE by token("!=")
    private val LT by token("<")
    private val GT by token(">")
    private val LE by token("<=")
    private val GE by token(">=")

    private val PLUS by token("\\+")
    private val MINUS by token("-")
    private val DIV by token("/")
    private val MOD by token("%")

    private val AND by token("and\\b")
    private val OR by token("or\\b")
    private val NOT by token("not\\b")

    private val CREATE by token("create\\b")
    private val UPDATE by token("update\\b")
    private val DELETE by token("delete\\b")
    private val FROM by token("from\\b")
    private val SELECT by token("select\\b")
    private val WHERE by token("where\\b")
    private val CLASS by token("class\\b")
    private val REL by token("rel\\b")
    private val KEY by token("key\\b")
    private val INDEX by token("index\\b")
    private val OPERATION by token("operation\\b")
    private val QUERY by token("query\\b")
    private val VAL by token("val\\b")
    private val RETURN by token("return\\b")
    private val NUMBER by token("\\d+")
     private val HEXLIT by token("x\"[0123456789abcdefABCDEF]*\"")
    private val STRINGLIT by token("\".*?\"")
    private val IDT by token("\\w+")
    private val id by (IDT) use { text }

    private val relAutoAttribute by (id) map { S_Attribute(it, it) }
    private val relNamedAttribute by (id * -COLON * id) map { (name, type) -> S_Attribute(name, type) }
    private val relAttribute by (relNamedAttribute or relAutoAttribute)
    private val relAttributes by separatedTerms(relAttribute, COMMA, false)

    private val relKey by (-KEY * relAttributes) map { S_KeyClause(it) }
    private val relIndex by (-INDEX * relAttributes) map { S_IndexClause(it) }

    private val anyRelClause by (relKey or relIndex or (relAttribute map { S_AttributeClause(it) }))

    private val relClauses by zeroOrMore(anyRelClause * -SEMI)

    private val classDef by (-CLASS * id * -LCURL * relClauses * -RCURL) map {
        (identifier, clauses) ->
        val rel = classFromClauses(identifier, clauses)
        S_ClassDefinition(identifier, rel.attributes, rel.keys, rel.indices)
    }

    private val binaryOperator = (
            EQ
            or EQEQ
            or NE
            or LT
            or GT
            or LE
            or GE

            or PLUS
            or MINUS
            or DIV
            or MOD

            or AND
            or OR
    ) map { it.text }

    private val unaryOperator = (
            PLUS
            or MINUS
            or NOT
    ) map { it.text }

    private val nameExpr by id map { S_NameExpr(it) }
    private val intExpr by NUMBER map { S_IntLiteral(it.text.toLong()) }
    private val stringExpr = STRINGLIT map { S_StringLiteral(it.text.removeSurrounding("\"", "\"")) }
    private val bytesExpr = HEXLIT map { S_ByteALiteral(it.text.removeSurrounding("x\"", "\"").hexStringToByteArray()) }

    private val parenthesesExpr by ( -LPAR * parser(this::expression) * -RPAR )

    private val baseExprHead by ( nameExpr or intExpr or stringExpr or bytesExpr or parenthesesExpr )

    private val baseExprTailAttribute by ( -DOT * id ) map { name -> BaseExprTailAttribute(name) }
    private val baseAttrTailCall by ( -LPAR * separatedTerms(parser(this::expression), COMMA, true)  * -RPAR ) map { args ->
        BaseExprTailCall(args)
    }

    private val baseExprTail by ( baseExprTailAttribute or baseAttrTailCall )

    private val baseExpr: Parser<S_Expression> by ( baseExprHead * zeroOrMore(baseExprTail) ) map { ( head, tails ) ->
        var expr = head
        for (tail in tails) {
            expr = tail.toExpr(expr)
        }
        expr
    }

    private val selectExpr by ( id * -AT * -LCURL * zeroOrMore(parser(this::expression)) * -RCURL ) map {
        ( className, exprs ) ->
        S_SelectExpr(className, exprs)
    }

    private val operandExpr: Parser<S_Expression> by ( selectExpr or baseExpr )

    private val unaryExpr by ( optional(unaryOperator) * operandExpr ) map { (op, expr) ->
        if (op == null) expr else S_UnaryExpr(op, expr)
    }

    private val binaryExprOperand by ( binaryOperator * unaryExpr ) map { ( op, expr ) -> BinaryExprTail(op, expr) }

    private val binaryExpr by ( unaryExpr * zeroOrMore(binaryExprOperand) ) map { ( left, tails ) ->
        var expr = left
        for (tail in tails) {
            expr = S_BinaryExpr(expr, tail.expr, tail.op)
        }
        expr
    }

    private val expression: Parser<S_Expression> by binaryExpr

    private val valStatement by (-VAL * id * -EQ * expression * -SEMI) map { (name, expr) -> S_ValStatement(name, expr) }

//    val attrExpr_expl = (id * -EQ * anyExpr) map { (i, e) -> S_AttrExpr(i, e) }
//    val attrExpr_impl = (anyExpr) map { e ->
//        S_AttrExpr(inferName(e), e)
//    }
//    val anyAttrExpr by (attrExpr_expl or attrExpr_impl)
//
//    val createStatement by (-CREATE * id * -LPAR * separatedTerms(anyAttrExpr, COMMA, true) * -RPAR * -SEMI) map {
//        (classname, values) ->
//        S_CreateStatement(classname, values)
//    }
//
//    val updateStatement by (-UPDATE * atExpr * -LPAR * separatedTerms(anyAttrExpr, COMMA, false) * -RPAR * -SEMI) map {
//        (atExpr, values) ->
//        S_UpdateStatement(atExpr, values)
//    }
//
//    val deleteStatement by (-DELETE * atExpr * -SEMI) map {
//        S_DeleteStatement(it)
//    }

    private val returnStatement by (-RETURN * expression * -SEMI) map {
        expr ->
        S_ReturnStatement(expr)
    }

    private val statement by (valStatement or returnStatement)

    private val opDefinition by (-OPERATION * id * -LPAR * separatedTerms(relAttribute, COMMA, true) * -RPAR
            * -LCURL * oneOrMore(statement) * -RCURL) map {
        (identifier, args, statements) ->
        S_OpDefinition(identifier, args, statements)
    }

    private val queryBodyShort by (-EQ * expression * -SEMI) map {
        expr ->
        S_QueryBodyShort(expr)
    }

    private val queryBodyFull by (-LCURL * oneOrMore(statement) * -RCURL) map {
        statements ->
        S_QueryBodyFull(statements)
    }

    private val queryDefinition by (-QUERY * id * -LPAR * separatedTerms(relAttribute, COMMA, true) * -RPAR
            * (queryBodyShort or queryBodyFull)) map {
        (identifier, args, body) ->
        S_QueryDefinition(identifier, args, body)
    }

    private val anyDef by (classDef or opDefinition or queryDefinition)

    override val rootParser by oneOrMore(anyDef) map { S_ModuleDefinition(it) }

    /*val term by
    (id use { Variable(text) }) or
            (-not * parser(this::term) map { (Not(it) }) or
            (-lpar * parser(this::rootParser) * -rpar)

    val andChain by leftAssociative(term, and) { l, _, r -> And(l, r) }
    override val rootParser by leftAssociative(andChain, or) { l, _, r -> Or(l, r) }*/
}

private fun classFromClauses(identifier: String, clauses: List<S_RelClause>): S_ClassDefinition {
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

    return S_ClassDefinition(
            identifier,
            attributes.entries.map { S_Attribute(it.key, it.value) },
            keys,
            indices
    )
}

private sealed class BaseExprTail {
    abstract fun toExpr(base: S_Expression): S_Expression
}

private class BaseExprTailAttribute(val name: String): BaseExprTail() {
    override fun toExpr(base: S_Expression): S_Expression = S_AttributeExpr(base, name)
}

private class BaseExprTailCall(val args: List<S_Expression>): BaseExprTail() {
    override fun toExpr(base: S_Expression): S_Expression = S_CallExpr(base, args)
}

private class BinaryExprTail(val op: String, val expr: S_Expression)

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
