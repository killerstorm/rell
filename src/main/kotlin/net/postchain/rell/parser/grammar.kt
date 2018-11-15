package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.parser.Parser
import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.lexer.TokenMatch
import net.postchain.rell.hexStringToByteArray
import java.lang.IllegalArgumentException
import java.util.regex.Pattern

object S_Grammar : Grammar<S_ModuleDefinition>() {
    private val ws by token("""\s+""", ignore = true)
    private val singleLineComment by token("""//.*""", ignore = true)

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
    private val ELVIS by token("\\?:")
    private val SAFECALL by token("\\?\\.")
    private val NOTNULL by token("!!")
    private val QUESTION by token("\\?")

    private val EQ by token("=(?!=)")
    private val EQEQ by token("==")
    private val NE by token("!=")
    private val LT by token("<(?!=)")
    private val GT by token(">(?!=)")
    private val LE by token("<=")
    private val GE by token(">=")

    private val PLUS by token("\\+(?!=)")
    private val MINUS by token("-(?!=)")
    private val MUL by token("\\*(?!=)")
    private val DIV by token("/(?!=)")
    private val MOD by token("%(?!=)")

    private val AND by token("and\\b")
    private val OR by token("or\\b")
    private val NOT by token("not\\b")

    private val PLUS_EQ by token("\\+=")
    private val MINUS_EQ by token("-=")
    private val MUL_EQ by token("\\*=")
    private val DIV_EQ by token("/=")
    private val MOD_EQ by token("%=")

    private val CREATE by token("create\\b")
    private val UPDATE by token("update\\b")
    private val DELETE by token("delete\\b")
    private val CLASS by token("class\\b")
    private val KEY by token("key\\b")
    private val INDEX by token("index\\b")
    private val OPERATION by token("operation\\b")
    private val QUERY by token("query\\b")
    private val FUNCTION by token("function\\b")
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
    private val LIMIT by token("limit\\b")
    private val SORT by token("sort\\b")
    private val LIST by token("list\\b")
    private val SET by token("set\\b")
    private val MAP by token("map\\b")

    private val FALSE by token("false\\b")
    private val TRUE by token("true\\b")
    private val NULL by token("null\\b")

    private val NUMBER by token("\\d+")
    private val HEXLIT_SINGLE by token("x'.*?'")
    private val HEXLIT_DOUBLE by token("x\".*?\"")
    private val STRINGLIT_SINGLE by token("'.*?'")
    private val STRINGLIT_DOUBLE by token("\".*?\"")
    private val IDT by token("[A-Za-z_][A-Za-z_0-9]*")
    private val id by ( IDT ) map { S_Name(S_Pos(it), it.text) }

    private val _type by parser(this::type)
    private val _expression by parser(this::expression)
    private val _statement by parser(this::statement)

    private val nameType by id map { S_NameType(it) }

    private val tupleField by ( optional(id * -COLON) * _type ) map { (name, type) -> Pair(name, type) }
    private val tupleType by ( -LPAR * separatedTerms(tupleField, COMMA, false) * -RPAR ) map { S_TupleType(it) }

    private val listType by ( LIST * -LT * _type * -GT ) map { (kw, type) -> S_ListType(S_Pos(kw), type) }
    private val setType by ( SET * -LT * _type * -GT ) map { (kw, type) -> S_SetType(S_Pos(kw), type) }

    private val mapType by ( MAP * -LT * _type * -COMMA * _type * -GT ) map { (kw, key, value ) ->
        S_MapType(S_Pos(kw), key, value)
    }

    private val baseType by (
            nameType
            or tupleType
            or listType
            or setType
            or mapType
    )

    private val type: Parser<S_Type> by ( baseType * zeroOrMore(QUESTION) ) map { (base, nulls) ->
        var res = base
        for (n in nulls) {
            res = S_NullableType(S_Pos(n), res)
        }
        res
    }

    private val relAutoField by ( id ) map { S_NameTypePair(it, null) }
    private val relNamedField by ( id * -COLON * type ) map { (name, type) -> S_NameTypePair(name, type) }
    private val relField by ( relNamedField or relAutoField )
    private val relFields by separatedTerms(relField, COMMA, false)

    private val relKeyClause by ( KEY * relFields ) map { (kw, attrs) -> S_KeyClause(S_Pos(kw), attrs) }
    private val relIndexClause by ( INDEX * relFields ) map { (kw, attrs) -> S_IndexClause(S_Pos(kw), attrs) }

    private val relAttributeClause by ( optional(MUTABLE) * relField * optional(-EQ * _expression) ) map {
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
            ( EQ mapNode { S_BinaryOpCode.EQ } )
            or ( EQEQ mapNode { S_BinaryOpCode.EQ } )
            or ( NE mapNode { S_BinaryOpCode.NE } )
            or ( LE mapNode { S_BinaryOpCode.LE } )
            or ( GE mapNode { S_BinaryOpCode.GE } )
            or ( LT mapNode { S_BinaryOpCode.LT } )
            or ( GT mapNode { S_BinaryOpCode.GT } )

            or ( PLUS mapNode { S_BinaryOpCode.PLUS } )
            or ( MINUS mapNode { S_BinaryOpCode.MINUS } )
            or ( MUL mapNode { S_BinaryOpCode.MUL } )
            or ( DIV mapNode { S_BinaryOpCode.DIV } )
            or ( MOD mapNode { S_BinaryOpCode.MOD } )

            or ( AND mapNode { S_BinaryOpCode.AND } )
            or ( OR mapNode { S_BinaryOpCode.OR } )

            or ( IN mapNode { S_BinaryOpCode.IN } )
            or ( ELVIS mapNode { S_BinaryOpCode.ELVIS } )
    )

    private val unaryOperator = (
            ( PLUS mapNode { S_UnaryOp_Plus } )
            or ( MINUS mapNode { S_UnaryOp_Minus }  )
            or ( NOT mapNode { S_UnaryOp_Not }  )
    )

    private val nameExpr by id map { S_NameExpr(it) }

    private val intExpr by NUMBER map { S_IntLiteralExpr(S_Pos(it), it.text.toLong()) }

    private val stringExpr =
            ( STRINGLIT_SINGLE map { S_StringLiteralExpr(S_Pos(it), it.text.removeSurrounding("'", "'")) } ) or
            ( STRINGLIT_DOUBLE map { S_StringLiteralExpr(S_Pos(it), it.text.removeSurrounding("\"", "\"")) } )

    private val bytesExpr by
            ( HEXLIT_SINGLE map { S_ByteALiteralExpr(S_Pos(it), decodeByteArray(it, it.text.removeSurrounding("x'", "'"))) }) or
            ( HEXLIT_DOUBLE map { S_ByteALiteralExpr(S_Pos(it), decodeByteArray(it, it.text.removeSurrounding("x\"", "\""))) })

    private val booleanLiteralExpr by
            ( FALSE map { S_BooleanLiteralExpr(S_Pos(it), false) } ) or
            ( TRUE map { S_BooleanLiteralExpr(S_Pos(it), true) } )

    private val nullLiteralExpr by NULL map { S_NullLiteralExpr(S_Pos(it)) }

    private val literalExpr by ( intExpr or stringExpr or bytesExpr or booleanLiteralExpr or nullLiteralExpr )

    private val tupleExprField by ( optional(id * -COLON) * _expression ) map { ( name, expr ) -> Pair(name, expr)  }

    private val parenthesesExpr by ( LPAR * tupleExprField * optional(-COMMA * separatedTerms(tupleExprField, COMMA, true)) * -RPAR ) map {
        (pos, field, tail) ->
        if (tail == null && field.first == null) {
            field.second
        } else {
            val fields = listOf(field) + (tail ?: listOf())
            S_TupleExpression(S_Pos(pos), fields)
        }
    }

    private val listLiteralExpr by ( LBRACK * separatedTerms(_expression, COMMA, true) * -RBRACK ) map {
        ( pos, exprs ) -> S_ListLiteralExpression(S_Pos(pos), exprs)
    }

    private val mapLiteralExprEntry by ( _expression * -COLON * _expression ) map { (key, value) -> Pair(key, value) }
    private val mapLiteralExpr by ( LBRACK * separatedTerms(mapLiteralExprEntry, COMMA, true) * -RBRACK ) map {
        ( pos, entries ) -> S_MapLiteralExpression(S_Pos(pos), entries)
    }

    private val listExprType by ( -LT * type * -GT )

    private val listExpr by ( LIST * optional(listExprType) * -LPAR * separatedTerms(_expression, COMMA, true) * -RPAR  ) map {
        (kw, type, args) -> S_ListExpression(S_Pos(kw), type, args)
    }

    private val setExpr by ( SET * optional(listExprType) * -LPAR * separatedTerms(_expression, COMMA, true) * -RPAR  ) map {
        (kw, type, args) -> S_SetExpression(S_Pos(kw), type, args)
    }

    private val mapExprType by ( -LT * type * -COMMA * type * -GT )

    private val mapExpr by ( MAP * optional(mapExprType) * -LPAR * separatedTerms(_expression, COMMA, true) * -RPAR ) map {
        ( kw, types, args ) ->
        val keyValueTypes = if (types == null) null else Pair(types.t1, types.t2)
        S_MapExpression(S_Pos(kw), keyValueTypes, args)
    }

    private val nameExprPair by ( optional(id * -EQ) * _expression ) map {
        (name, expr) -> S_NameExprPair(name, expr)
    }

    private val nameEqExprPairList by ( -LPAR * separatedTerms(nameExprPair, COMMA, true) * -RPAR )

    private val createExpr by (CREATE * id * nameEqExprPairList) map {
        (kw, className, exprs) ->
        S_CreateExpr(S_Pos(kw), className, exprs)
    }

    private val baseExprHead by (
            nameExpr
            or literalExpr
            or parenthesesExpr
            or listLiteralExpr
            or mapLiteralExpr
            or listExpr
            or setExpr
            or mapExpr
    )

    private val baseExprTailMember by ( -DOT * id ) map { name -> BaseExprTail_Member(name) }
    private val baseExprTailLookup by ( LBRACK * _expression * -RBRACK ) map { (pos, expr) -> BaseExprTail_Lookup(S_Pos(pos), expr) }
    private val baseExprTailNotNull by ( NOTNULL ) map { BaseExprTail_NotNull(S_Pos(it)) }
    private val baseExprTailSafeMember by ( -SAFECALL * id ) map { name -> BaseExprTail_SafeMember(name) }
    private val baseExprTailCall by ( -LPAR * separatedTerms(_expression, COMMA, true)  * -RPAR ) map { args ->
        BaseExprTail_Call(args)
    }

    private val baseExprTailNoCall by ( baseExprTailMember or baseExprTailLookup or baseExprTailNotNull or baseExprTailSafeMember )
    private val baseExprTail by ( baseExprTailNoCall or baseExprTailCall )

    private val baseExpr: Parser<S_Expression> by ( baseExprHead * zeroOrMore(baseExprTail) ) map {
        ( head, tails ) -> tailsToExpr(head, tails)
    }

    private val callExprTail by ( oneOrMore(zeroOrMore(baseExprTailNoCall) * baseExprTailCall )) map {
        tails ->
        val list = mutableListOf<BaseExprTail>()
        for (( nocalls, call ) in tails) {
            list.addAll(nocalls)
            list.add(call)
        }
        list
    }

    private val callExpr by ( baseExprHead * callExprTail ) map {
        (head, tails) -> tailsToExpr(head, tails)
    }

    private val atExprFromSingle by id map { S_Node(it.pos, listOf(S_AtExprFrom(null, it))) }

    private val atExprFromItem by ( optional( id * -COLON ) * id ) map {
        ( alias, className ) -> S_AtExprFrom(alias, className)
    }

    private val atExprFromMulti by ( LPAR * separatedTerms( atExprFromItem, COMMA, false ) * -RPAR ) map {
        ( pos, items ) -> S_Node(pos, items)
    }

    private val atExprFrom by ( atExprFromSingle or atExprFromMulti )

    private val atExprAt by (
            ( AT * QUESTION map { Pair(true, false) } )
            or ( AT * MUL map { Pair(true, true) } )
            or ( AT * PLUS map { Pair(false, true) } )
            or ( AT map { Pair(false, false) } )
    )

    private val atExprWhatSimple by oneOrMore((-DOT * id)) map { path -> S_AtExprWhatSimple(path) }

    private val atExprWhatSort by ( optional(MINUS) * SORT ) map { (minus, _) -> minus == null }

    private val atExprWhatName by ( optional(id) * -EQ ) map { name -> S_AtExprWhatAttr(name) }

    private val atExprWhatComplexItem by ( optional(atExprWhatSort) * optional(atExprWhatName) * _expression ) map {
        (sort, name, expr) -> S_AtExprWhatComplexField(name, expr, sort)
    }

    private val atExprWhatComplex by ( -LPAR * separatedTerms(atExprWhatComplexItem, COMMA, false) * -RPAR ) map {
        exprs -> S_AtExprWhatComplex(exprs)
    }

    private val atExprWhat by ( atExprWhatSimple or atExprWhatComplex )

    private val atExprWhere by ( -LCURL * separatedTerms(_expression, COMMA, true) * -RCURL ) map {
        exprs -> S_AtExprWhere(exprs)
    }

    private val atExprLimit by ( -LIMIT * _expression )

    private val atExpr by ( atExprFrom * atExprAt * atExprWhere * optional(atExprWhat) * optional(atExprLimit) ) map {
        ( from, zeroMany, where, whatOpt, limit ) ->
        val (zero, many) = zeroMany
        val what = if (whatOpt == null) S_AtExprWhatDefault() else whatOpt
        S_AtExpr(from.pos, from.value, what, where, limit, zero, many)
    }

    private val operandExpr: Parser<S_Expression> by ( atExpr or baseExpr or createExpr )

    private val unaryExpr by ( optional(unaryOperator) * operandExpr ) map { (op, expr) ->
        if (op == null) expr else S_UnaryExpr(op.pos, S_Node(op.pos, op.value), expr)
    }

    private val binaryExprOperand by ( binaryOperator * unaryExpr ) map { ( op, expr ) -> S_BinaryExprTail(op, expr) }

    private val binaryExpr by ( unaryExpr * zeroOrMore(binaryExprOperand) ) map { ( head, tail ) ->
        if (tail.isEmpty()) head else S_BinaryExpr(head, tail)
    }

    private val expression: Parser<S_Expression> by binaryExpr

    private val emptyStatement by SEMI map { S_EmptyStatement() }

    private val valStatement by ( -VAL * id * optional(-COLON * type ) * -EQ * expression * -SEMI) map {
        (name, type, expr) -> S_ValStatement(name, type, expr)
    }

    private val varStatement by ( -VAR * id * optional(-COLON * type ) * optional(-EQ * expression) * -SEMI) map {
        (name, type, expr) -> S_VarStatement(name, type, expr)
    }

    private val returnStatement by ( RETURN * optional(expression) * -SEMI ) map { ( kw, expr ) ->
        S_ReturnStatement(S_Pos(kw), expr)
    }

    private val assignOp by (
            ( EQ mapNode { S_AssignOpCode.EQ })
            or ( PLUS_EQ mapNode { S_AssignOpCode.PLUS })
            or ( MINUS_EQ mapNode { S_AssignOpCode.MINUS })
            or ( MUL_EQ mapNode { S_AssignOpCode.MUL })
            or ( DIV_EQ mapNode { S_AssignOpCode.DIV })
            or ( MOD_EQ mapNode { S_AssignOpCode.MOD })
    )

    private val assignStatement by ( baseExpr * assignOp * expression * -SEMI ) map {
        (expr1, op, expr2) -> S_AssignStatement(expr1, op, expr2)
    }

    private val blockStatement by ( -LCURL * zeroOrMore(_statement) * -RCURL ) map {
        statements -> S_BlockStatement(statements)
    }

    private val ifStatement by ( -IF * -LPAR * expression * -RPAR * _statement * optional(-ELSE * _statement) ) map {
        (expr, trueStmt, falseStmt) -> S_IfStatement(expr, trueStmt, falseStmt)
    }

    private val whileStatement by ( -WHILE * -LPAR * expression * -RPAR * _statement ) map {
        (expr, stmt) -> S_WhileStatement(expr, stmt)
    }

    private val forStatement by ( -FOR * -LPAR * id * -IN * expression * -RPAR * _statement ) map {
        (name, expr, stmt) -> S_ForStatement(name, expr, stmt)
    }

    private val breakStatement by ( BREAK * -SEMI ) map { S_BreakStatement(S_Pos(it)) }

    private val callStatement by ( callExpr * -SEMI ) map { expr -> S_ExprStatement(expr) }

    private val updateFrom by ( atExprFromItem * optional(atExprFromMulti) ) map {
        ( targetClass, joinClasses ) ->
        listOf(targetClass) + (if (joinClasses == null) listOf() else joinClasses.value)
    }

    private val createStatement by ( createExpr * -SEMI ) map { expr -> S_ExprStatement(expr) }

    private val updateWhatNameOp by ( id * assignOp ) map { (name, op) -> Pair(name, op) }

    private val updateWhatExpr by ( optional(updateWhatNameOp) * expression ) map {
        (nameOp, expr) ->
        if (nameOp == null) S_UpdateWhatAnon(expr) else S_UpdateWhatNamed(nameOp.first, nameOp.second, expr)
    }

    private val updateWhat by ( -LPAR * separatedTerms(updateWhatExpr, COMMA, true) * -RPAR )

    private val updateStatement by (UPDATE * updateFrom * -AT * atExprWhere * updateWhat * -SEMI) map {
        (kw, from, where, what) -> S_UpdateStatement(S_Pos(kw), from, where, what)
    }

    private val deleteStatement by (DELETE * updateFrom * -AT * atExprWhere * -SEMI) map {
        (kw, from, where) -> S_DeleteStatement(S_Pos(kw), from, where)
    }

    private val statement: Parser<S_Statement> by (
            emptyStatement
            or valStatement
            or varStatement
            or assignStatement
            or returnStatement
            or blockStatement
            or ifStatement
            or whileStatement
            or forStatement
            or breakStatement
            or callStatement
            or createStatement
            or updateStatement
            or deleteStatement
    )

    private val formalParameters by ( -LPAR * separatedTerms(relField, COMMA, true) * -RPAR )

    private val opDefinition by (-OPERATION * id * formalParameters * blockStatement) map {
        (name, params, body) ->
        S_OpDefinition(name, params, body)
    }

    private val functionBodyShort by (-EQ * expression * -SEMI) map { S_FunctionBodyShort(it) }
    private val functionBodyFull by blockStatement map { stmt -> S_FunctionBodyFull(stmt) }
    private val functionBody by ( functionBodyShort or functionBodyFull )

    private val queryDefinition by (-QUERY * id * formalParameters * optional(-COLON * type) * functionBody) map {
        (name, params, type, body) -> S_QueryDefinition(name, params, type, body)
    }

    private val functionDefinition by (-FUNCTION * id * formalParameters * optional(-COLON * type) * functionBody) map {
        (name, params, type, body) -> S_FunctionDefinition(name, params, type, body)
    }

    private val anyDef by ( classDef or opDefinition or queryDefinition or functionDefinition )

    override val rootParser by zeroOrMore(anyDef) map { S_ModuleDefinition(it) }
}

private fun decodeByteArray(t: TokenMatch, s: String): ByteArray {
    try {
        return s.hexStringToByteArray()
    } catch (e: IllegalArgumentException) {
        throw CtError(S_Pos(t), "parser_bad_hex:$s", "Invalid byte array literal: '$s'")
    }
}

private fun tailsToExpr(head: S_Expression, tails: List<BaseExprTail>): S_Expression {
    var expr = head
    for (tail in tails) {
        expr = tail.toExpr(expr)
    }
    return expr
}

private sealed class BaseExprTail {
    abstract fun toExpr(base: S_Expression): S_Expression
}

private class BaseExprTail_Member(val name: S_Name): BaseExprTail() {
    override fun toExpr(base: S_Expression): S_Expression = S_MemberExpr(base, name)
}

private class BaseExprTail_SafeMember(val name: S_Name): BaseExprTail() {
    override fun toExpr(base: S_Expression): S_Expression = S_SafeMemberExpr(base, name)
}

private class BaseExprTail_Lookup(val pos: S_Pos, val expr: S_Expression): BaseExprTail() {
    override fun toExpr(base: S_Expression): S_Expression = S_LookupExpr(pos, base, expr)
}

private class BaseExprTail_Call(val args: List<S_Expression>): BaseExprTail() {
    override fun toExpr(base: S_Expression): S_Expression = S_CallExpr(base, args)
}

private class BaseExprTail_NotNull(val pos: S_Pos): BaseExprTail() {
    override fun toExpr(base: S_Expression): S_Expression = S_UnaryExpr(base.startPos, S_Node(pos, S_UnaryOp_NotNull), base)
}

private infix fun <A: TokenMatch, T> Parser<A>.mapNode(transform: (A) -> T): Parser<S_Node<T>> = MapCombinator(this) {
    S_Node(it, transform(it))
}

fun parseRellCode(s: String): S_ModuleDefinition {
    return S_Grammar.parseToEnd(s)
}
