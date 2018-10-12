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

    private val EQ by token("=(?!=)")
    private val EQEQ by token("==")
    private val NE by token("!=")
    private val LT by token("<(?!=)")
    private val GT by token(">(?!=)")
    private val LE by token("<=")
    private val GE by token(">=")

    private val PLUS by token("\\+")
    private val MINUS by token("-")
    private val MUL by token("\\*")
    private val DIV by token("/")
    private val MOD by token("%")

    private val AND by token("and\\b")
    private val OR by token("or\\b")
    private val NOT by token("not\\b")

    private val CREATE by token("create\\b")
    private val UPDATE by token("update\\b")
    private val DELETE by token("delete\\b")
    private val CLASS by token("class\\b")
    private val KEY by token("key\\b")
    private val INDEX by token("index\\b")
    private val OPERATION by token("operation\\b")
    private val QUERY by token("query\\b")
    private val VAL by token("val\\b")
    private val RETURN by token("return\\b")
    private val ALL by token("all\\b")

    private val FALSE by token("false\\b")
    private val TRUE by token("true\\b")

    private val NUMBER by token("\\d+")
    private val HEXLIT by token("x\"[0-9A-Fa-f]*\"")
    private val STRINGLIT_SINGLE by token("'.*?'")
    private val STRINGLIT_DOUBLE by token("\".*?\"")
    private val IDT by token("[A-Za-z_][A-Za-z_0-9]*")
    private val id by (IDT) use { text }

    private val relAutoAttribute by (id) map { S_NameTypePair(it, it) }
    private val relNamedAttribute by (id * -COLON * id) map { (name, type) -> S_NameTypePair(name, type) }
    private val relAttribute by (relNamedAttribute or relAutoAttribute)
    private val relAttributes by separatedTerms(relAttribute, COMMA, false)

    private val relKey by (-KEY * relAttributes) map { S_KeyClause(it) }
    private val relIndex by (-INDEX * relAttributes) map { S_IndexClause(it) }

    private val anyRelClause by (relKey or relIndex or (relAttribute map { S_AttributeClause(it) }))

    private val relClauses by zeroOrMore(anyRelClause * -SEMI)

    private val classDef by (-CLASS * id * -LCURL * relClauses * -RCURL) map {
        (name, clauses) ->
        S_ClassDefinition(name, clauses)
    }

    private val binaryOperator = (
            ( EQ map { S_BinaryOp_Eq } )
            or ( EQEQ map { S_BinaryOp_Eq } )
            or ( NE map { S_BinaryOp_Ne } )
            or ( LE map { S_BinaryOp_Le } )
            or ( GE map { S_BinaryOp_Ge } )
            or ( LT map { S_BinaryOp_Lt } )
            or ( GT map { S_BinaryOp_Gt } )

            or ( PLUS map { S_BinaryOp_Plus } )
            or ( MINUS map { S_BinaryOp_Minus } )
            or ( MUL map { S_BinaryOp_Mul } )
            or ( DIV map { S_BinaryOp_Div } )
            or ( MOD map { S_BinaryOp_Mod } )

            or ( AND map { S_BinaryOp_And } )
            or ( OR map { S_BinaryOp_Or } )
    )

    private val unaryOperator = (
            ( PLUS map { S_UnaryOp_Plus } )
            or ( MINUS map { S_UnaryOp_Minus }  )
            or ( NOT map { S_UnaryOp_Not }  )
    )

    private val nameExpr by id map { S_NameExpr(it) }

    private val intExpr by NUMBER map { S_IntLiteralExpr(it.text.toLong()) }

    private val stringExpr = ( STRINGLIT_SINGLE map { S_StringLiteralExpr(it.text.removeSurrounding("'", "'")) } ) or
            ( STRINGLIT_DOUBLE map { S_StringLiteralExpr(it.text.removeSurrounding("\"", "\"")) } )

    private val bytesExpr = HEXLIT map { S_ByteALiteralExpr(it.text.removeSurrounding("x\"", "\"").hexStringToByteArray()) }

    private val booleanLiteralExpr by
            ( FALSE map { S_BooleanLiteralExpr(false) } ) or
            ( TRUE map { S_BooleanLiteralExpr(true) } )

    private val literalExpr by ( intExpr or stringExpr or bytesExpr or booleanLiteralExpr )

    private val parenthesesExpr by ( -LPAR * parser(this::expression) * -RPAR )

    private val baseExprHead by ( nameExpr or literalExpr or parenthesesExpr )

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

    private val atExprFromSingle by id map { listOf(S_AtExprFrom(null, it)) }

    private val atExprFromItem by ( optional( id * -COLON ) * id ) map {
        ( alias, className ) -> S_AtExprFrom(alias, className)
    }

    private val atExprFromMulti by ( -LPAR * separatedTerms( atExprFromItem, COMMA, false ) * -RPAR )

    private val atExprFrom by ( atExprFromSingle or atExprFromMulti )

    private val atExprWhatSimple by oneOrMore((-DOT * id)) map { path -> S_AtExprWhatSimple(path) }

    private val atExprWhatComplexItem by ( optional(id * -EQ) * parser(this::expression) ) map {
        (name, expr) -> S_AtExprWhatComplexField(name, expr)
    }

    private val atExprWhatComplex by ( -LPAR * separatedTerms(atExprWhatComplexItem, COMMA, false) * -RPAR ) map {
        exprs -> S_AtExprWhatComplex(exprs)
    }

    private val atExprWhat by ( atExprWhatSimple or atExprWhatComplex )

    private val atExprWhere by ( -LCURL * separatedTerms(parser(this::expression), COMMA, true) * -RCURL ) map {
        exprs -> S_AtExprWhere(exprs)
    }

    private val atExpr by ( optional(ALL) * atExprFrom * -AT * atExprWhere * optional(atExprWhat) ) map {
        ( all, from, where, whatOpt ) ->
        val what = if (whatOpt == null) S_AtExprWhatDefault() else whatOpt
        S_AtExpr(from, what, where, all != null)
    }

    private val operandExpr: Parser<S_Expression> by ( atExpr or baseExpr )

    private val unaryExpr by ( optional(unaryOperator) * operandExpr ) map { (op, expr) ->
        if (op == null) expr else S_UnaryExpr(op, expr)
    }

    private val binaryExprOperand by ( binaryOperator * unaryExpr ) map { ( op, expr ) -> BinaryExprTail(op, expr) }

    private val binaryExpr by ( unaryExpr * zeroOrMore(binaryExprOperand) ) map { ( left, tails ) ->
        var expr = left
        for (tail in tails) {
            expr = S_BinaryExpr(tail.op, expr, tail.expr)
        }
        expr
    }

    private val expression: Parser<S_Expression> by binaryExpr

    private val valStatement by (-VAL * id * -EQ * expression * -SEMI) map { (name, expr) -> S_ValStatement(name, expr) }

    private val returnStatement by (-RETURN * expression * -SEMI) map {
        expr ->
        S_ReturnStatement(expr)
    }

    private val blockStatement by (-LCURL * oneOrMore(parser(this::statement)) * -RCURL) map {
        statements ->
        if (statements.size == 1) statements[0] else S_BlockStatement(statements)
    }

    private val nameExprPair by ( optional(id * -EQ) * parser(this::expression) ) map {
        (name, expr) -> S_NameExprPair(name, expr)
    }

    private val nameEqExprPairList by ( -LPAR * separatedTerms(nameExprPair, COMMA, true) * -RPAR )

    val createStatement by (-CREATE * id * nameEqExprPairList * -SEMI) map {
        (className, exprs) ->
        S_CreateStatement(className, exprs)
    }

    val updateFrom by ( atExprFromItem * optional(atExprFromMulti) ) map {
        ( targetClass, joinClasses ) ->
        listOf(targetClass) + (if (joinClasses == null) listOf() else joinClasses)
    }

    val updateStatement by (-UPDATE * updateFrom * -AT * atExprWhere * nameEqExprPairList * -SEMI) map {
        (from, where, what) -> S_UpdateStatement(from, where, what)
    }

    val deleteStatement by (-DELETE * updateFrom * -AT * atExprWhere * -SEMI) map {
        (from, where) -> S_DeleteStatement(from, where)
    }

    private val statement: Parser<S_Statement> by (
            valStatement
            or returnStatement
            or blockStatement
            or createStatement
            or updateStatement
            or deleteStatement
    )

    private val opDefinition by (-OPERATION * id * -LPAR * separatedTerms(relAttribute, COMMA, true) * -RPAR
            * blockStatement) map
    {
        (name, args, body) ->
        S_OpDefinition(name, args, body)
    }

    private val queryBodyShort by (-EQ * expression * -SEMI) map {
        expr ->
        S_QueryBodyShort(expr)
    }

    private val queryBodyFull by blockStatement map { stmt -> S_QueryBodyFull(stmt) }

    private val queryDefinition by (-QUERY * id * -LPAR * separatedTerms(relAttribute, COMMA, true) * -RPAR
            * (queryBodyShort or queryBodyFull)) map {
        (identifier, args, body) ->
        S_QueryDefinition(identifier, args, body)
    }

    private val anyDef by ( classDef or opDefinition or queryDefinition )

    override val rootParser by oneOrMore(anyDef) map { S_ModuleDefinition(it) }
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

private class BinaryExprTail(val op: S_BinaryOp, val expr: S_Expression)

fun parseRellCode(s: String): S_ModuleDefinition {
    return S_Grammar.parseToEnd(s)
}
