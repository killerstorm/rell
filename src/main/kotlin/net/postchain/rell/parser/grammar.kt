package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.parser.Parser
import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.grammar.parser
import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.parser.S_Grammar.getValue
import net.postchain.rell.parser.S_Grammar.provideDelegate
import java.lang.IllegalArgumentException

object S_Grammar : Grammar<S_ModuleDefinition>() {
    private val ws by token("\\s+", ignore = true)
    private val LPAR by token("\\(")
    private val RPAR by token("\\)")
    private val LCURL by token("\\{")
    private val RCURL by token("\\}")
    private val LBRACK by token("\\[")
    private val RBRACK by token("\\]")
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
    private val VAR by token("var\\b")
    private val RETURN by token("return\\b")
    private val IF by token("if\\b")
    private val ELSE by token("else\\b")
    private val WHILE by token("while\\b")
    private val FOR by token("for\\b")
    private val BREAK by token("break\\b")
    private val IN by token("in\\b")
    private val MUTABLE by token("mutable\\b")
    private val ALL by token("all\\b")
    private val REQUIRE by token("require\\b")

    private val FALSE by token("false\\b")
    private val TRUE by token("true\\b")

    private val NUMBER by token("\\d+")
    private val HEXLIT_SINGLE by token("x'.*?'")
    private val HEXLIT_DOUBLE by token("x\".*?\"")
    private val STRINGLIT_SINGLE by token("'.*?'")
    private val STRINGLIT_DOUBLE by token("\".*?\"")
    private val IDT by token("[A-Za-z_][A-Za-z_0-9]*")
    private val id by (IDT) use { text }

    private val relAutoField by (id) map { S_NameTypePair(it, it) }
    private val relNamedField by (id * -COLON * id) map { (name, type) -> S_NameTypePair(name, type) }
    private val relField by (relNamedField or relAutoField)
    private val relFields by separatedTerms(relField, COMMA, false)

    private val relKeyClause by (-KEY * relFields) map { S_KeyClause(it) }
    private val relIndexClause by (-INDEX * relFields) map { S_IndexClause(it) }

    private val relAttributeClause by (optional(MUTABLE) * relField * optional(-EQ * parser(this::expression))) map {
        ( mutable, field, expr ) ->
        S_AttributeClause(field, mutable != null, expr)
    }

    private val anyRelClause by ( relKeyClause or relIndexClause or relAttributeClause )

    private val relClauses by zeroOrMore(anyRelClause * -SEMI)

    private val classDef by (-CLASS * id * -LCURL * relClauses * -RCURL) map {
        (name, clauses) ->
        S_ClassDefinition(name, clauses)
    }

    private val binaryOperator = (
            ( EQ map { S_BinaryOpCode.EQ } )
            or ( EQEQ map { S_BinaryOpCode.EQ } )
            or ( NE map { S_BinaryOpCode.NE } )
            or ( LE map { S_BinaryOpCode.LE } )
            or ( GE map { S_BinaryOpCode.GE } )
            or ( LT map { S_BinaryOpCode.LT } )
            or ( GT map { S_BinaryOpCode.GT } )

            or ( PLUS map { S_BinaryOpCode.PLUS } )
            or ( MINUS map { S_BinaryOpCode.MINUS } )
            or ( MUL map { S_BinaryOpCode.MUL } )
            or ( DIV map { S_BinaryOpCode.DIV } )
            or ( MOD map { S_BinaryOpCode.MOD } )

            or ( AND map { S_BinaryOpCode.AND } )
            or ( OR map { S_BinaryOpCode.OR } )
    )

    private val unaryOperator = (
            ( PLUS map { S_UnaryOp_Plus } )
            or ( MINUS map { S_UnaryOp_Minus }  )
            or ( NOT map { S_UnaryOp_Not }  )
    )

    private val nameExpr by id map { S_NameExpr(it) }

    private val intExpr by NUMBER map { S_IntLiteralExpr(it.text.toLong()) }

    private val stringExpr =
            ( STRINGLIT_SINGLE map { S_StringLiteralExpr(it.text.removeSurrounding("'", "'")) } ) or
            ( STRINGLIT_DOUBLE map { S_StringLiteralExpr(it.text.removeSurrounding("\"", "\"")) } )

    private val bytesExpr by
            ( HEXLIT_SINGLE map { S_ByteALiteralExpr(decodeByteArray(it.text.removeSurrounding("x'", "'"))) }) or
            ( HEXLIT_DOUBLE map { S_ByteALiteralExpr(decodeByteArray(it.text.removeSurrounding("x\"", "\""))) })

    private val booleanLiteralExpr by
            ( FALSE map { S_BooleanLiteralExpr(false) } ) or
            ( TRUE map { S_BooleanLiteralExpr(true) } )

    private val literalExpr by ( intExpr or stringExpr or bytesExpr or booleanLiteralExpr )

    private val parenthesesExpr by ( -LPAR * parser(this::expression) * -RPAR )

    private val nameExprPair by ( optional(id * -EQ) * parser(this::expression) ) map {
        (name, expr) -> S_NameExprPair(name, expr)
    }

    private val nameEqExprPairList by ( -LPAR * separatedTerms(nameExprPair, COMMA, true) * -RPAR )

    private val createExpr by (-CREATE * id * nameEqExprPairList) map {
        (className, exprs) ->
        S_CreateExpr(className, exprs)
    }

    private val baseExprHead by ( nameExpr or literalExpr or parenthesesExpr )

    private val baseExprTailAttribute by ( -DOT * id ) map { name -> BaseExprTailAttribute(name) }
    private val baseAttrTailLookup by ( -LBRACK * parser(this::expression) * -RBRACK ) map { expr -> BaseExprTailLookup(expr) }
    private val baseAttrTailCall by ( -LPAR * separatedTerms(parser(this::expression), COMMA, true)  * -RPAR ) map { args ->
        BaseExprTailCall(args)
    }

    private val baseExprTail by ( baseExprTailAttribute or baseAttrTailLookup or baseAttrTailCall )

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

    private val operandExpr: Parser<S_Expression> by ( atExpr or baseExpr or createExpr )

    private val unaryExpr by ( optional(unaryOperator) * operandExpr ) map { (op, expr) ->
        if (op == null) expr else S_UnaryExpr(op, expr)
    }

    private val binaryExprOperand by ( binaryOperator * unaryExpr ) map { ( op, expr ) -> S_BinaryExprTail(op, expr) }

    private val binaryExpr by ( unaryExpr * zeroOrMore(binaryExprOperand) ) map { ( head, tail ) ->
        if (tail.isEmpty()) head else S_BinaryExpr(head, tail)
    }

    private val expression: Parser<S_Expression> by binaryExpr

    private val type by id

    private val valStatement by (-VAL * id * optional(-COLON * type) * -EQ * expression * -SEMI) map {
        (name, type, expr) -> S_ValStatement(name, type, expr)
    }

    private val varStatement by (-VAR * id * optional(-COLON * type) * optional(-EQ * expression) * -SEMI) map {
        (name, type, expr) -> S_VarStatement(name, type, expr)
    }

    private val returnStatement by (-RETURN * expression * -SEMI) map {
        expr ->
        S_ReturnStatement(expr)
    }

    private val assignStatement by (id * -EQ * expression * -SEMI) map {
        (name, expr) -> S_AssignStatement(name, expr)
    }

    private val blockStatement by (-LCURL * oneOrMore(parser(this::statement)) * -RCURL) map {
        statements ->
        if (statements.size == 1) statements[0] else S_BlockStatement(statements)
    }

    private val ifStatement by (-IF * -LPAR * expression * -RPAR * parser(this::statement) * optional(-ELSE * parser(this::statement))) map {
        (expr, trueStmt, falseStmt) -> S_IfStatement(expr, trueStmt, falseStmt)
    }

    private val whileStatement by (-WHILE * -LPAR * expression * -RPAR * parser(this::statement)) map {
        (expr, stmt) -> S_WhileStatement(expr, stmt)
    }

    private val forStatement by (-FOR * -LPAR * id * -IN * expression * -RPAR * parser(this::statement)) map {
        (name, expr, stmt) -> S_ForStatement(name, expr, stmt)
    }

    private val breakStatement by (BREAK * -SEMI) map { S_BreakStatement() }

    private val requireStatement by -REQUIRE * -LPAR * expression * optional(-COMMA * expression) * -RPAR * -SEMI map {
        (expr, msgExpr) -> S_RequireStatement(expr, msgExpr)
    }

    private val updateFrom by ( atExprFromItem * optional(atExprFromMulti) ) map {
        ( targetClass, joinClasses ) ->
        listOf(targetClass) + (if (joinClasses == null) listOf() else joinClasses)
    }

    private val createStatement by (createExpr * -SEMI) map { expr -> S_ExprStatement(expr) }

    private val updateStatement by (-UPDATE * updateFrom * -AT * atExprWhere * nameEqExprPairList * -SEMI) map {
        (from, where, what) -> S_UpdateStatement(from, where, what)
    }

    private val deleteStatement by (-DELETE * updateFrom * -AT * atExprWhere * -SEMI) map {
        (from, where) -> S_DeleteStatement(from, where)
    }

    private val statement: Parser<S_Statement> by (
            valStatement
            or varStatement
            or assignStatement
            or returnStatement
            or blockStatement
            or ifStatement
            or whileStatement
            or forStatement
            or breakStatement
            or requireStatement
            or createStatement
            or updateStatement
            or deleteStatement
    )

    private val opDefinition by (-OPERATION * id * -LPAR * separatedTerms(relField, COMMA, true) * -RPAR
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

    private val queryDefinition by (-QUERY * id * -LPAR * separatedTerms(relField, COMMA, true) * -RPAR
            * (queryBodyShort or queryBodyFull)) map {
        (identifier, args, body) ->
        S_QueryDefinition(identifier, args, body)
    }

    private val anyDef by ( classDef or opDefinition or queryDefinition )

    override val rootParser by zeroOrMore(anyDef) map { S_ModuleDefinition(it) }
}

private fun decodeByteArray(s: String): ByteArray {
    try {
        return s.hexStringToByteArray()
    } catch (e: IllegalArgumentException) {
        throw CtError("parser_bad_hex:$s", "Invalid byte array literal: '$s'")
    }
}

private sealed class BaseExprTail {
    abstract fun toExpr(base: S_Expression): S_Expression
}

private class BaseExprTailAttribute(val name: String): BaseExprTail() {
    override fun toExpr(base: S_Expression): S_Expression = S_AttributeExpr(base, name)
}

private class BaseExprTailLookup(val expr: S_Expression): BaseExprTail() {
    override fun toExpr(base: S_Expression): S_Expression = S_LookupExpr(base, expr)
}

private class BaseExprTailCall(val args: List<S_Expression>): BaseExprTail() {
    override fun toExpr(base: S_Expression): S_Expression = S_CallExpr(base, args)
}

fun parseRellCode(s: String): S_ModuleDefinition {
    return S_Grammar.parseToEnd(s)
}
