package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.parser.Parser
import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.lexer.Token
import com.github.h0tk3y.betterParse.lexer.TokenMatch
import net.postchain.rell.parser.S_Grammar.getValue
import net.postchain.rell.parser.S_Grammar.provideDelegate

object S_Grammar : Grammar<S_ModuleDefinition>() {
    private val LPAR by relltok("(")
    private val RPAR by relltok(")")
    private val LCURL by relltok("{")
    private val RCURL by relltok("}")
    private val LBRACK by relltok("[")
    private val RBRACK by relltok("]")
    private val AT by relltok("@")
    private val COLON by relltok(":")
    private val SEMI by relltok(";")
    private val COMMA by relltok(",")
    private val DOT by relltok(".")
    private val ELVIS by relltok("?:")
    private val SAFECALL by relltok("?.")
    private val NOTNULL by relltok("!!")
    private val QUESTION by relltok("?")

    private val EQ by relltok("==")
    private val NE by relltok("!=")
    private val LT by relltok("<")
    private val GT by relltok(">")
    private val LE by relltok("<=")
    private val GE by relltok(">=")
    private val EQ_REF by relltok("===")
    private val NE_REF by relltok("!==")

    private val PLUS by relltok("+")
    private val MINUS by relltok("-")
    private val MUL by relltok("*")
    private val DIV by relltok("/")
    private val MOD by relltok("%")

    private val AND by relltok("and")
    private val OR by relltok("or")
    private val NOT by relltok("not")

    private val ASSIGN by relltok("=")
    private val PLUS_ASSIGN by relltok("+=")
    private val MINUS_ASSIGN by relltok("-=")
    private val MUL_ASSIGN by relltok("*=")
    private val DIV_ASSIGN by relltok("/=")
    private val MOD_ASSIGN by relltok("%=")

    private val CREATE by relltok("create")
    private val UPDATE by relltok("update")
    private val DELETE by relltok("delete")
    private val CLASS by relltok("class")
    private val KEY by relltok("key")
    private val INDEX by relltok("index")
    private val OPERATION by relltok("operation")
    private val QUERY by relltok("query")
    private val RECORD by relltok("record")
    private val FUNCTION by relltok("function")
    private val VAL by relltok("val")
    private val VAR by relltok("var")
    private val RETURN by relltok("return")
    private val IF by relltok("if")
    private val ELSE by relltok("else")
    private val WHILE by relltok("while")
    private val FOR by relltok("for")
    private val BREAK by relltok("break")
    private val IN by relltok("in")
    private val MUTABLE by relltok("mutable")
    private val LIMIT by relltok("limit")
    private val SORT by relltok("sort")
    private val LIST by relltok("list")
    private val SET by relltok("set")
    private val MAP by relltok("map")

    private val FALSE by relltok("false")
    private val TRUE by relltok("true")
    private val NULL by relltok("null")

    private val NUMBER by relltok(RellTokenizer.INTEGER)
    private val HEXLIT by relltok(RellTokenizer.BYTEARRAY)
    private val STRINGLIT by relltok(RellTokenizer.STRING)
    private val IDT by relltok(RellTokenizer.IDENTIFIER)

    override val tokenizer: RellTokenizer by lazy { RellTokenizer(tokens) }

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

    private val relKeyClause by ( KEY * relFields * -SEMI ) map { (kw, attrs) -> S_KeyClause(S_Pos(kw), attrs) }
    private val relIndexClause by ( INDEX * relFields * -SEMI ) map { (kw, attrs) -> S_IndexClause(S_Pos(kw), attrs) }

    private val relAttributeClause by ( optional(MUTABLE) * relField * optional(-ASSIGN * _expression) * -SEMI ) map {
        ( mutable, field, expr ) ->
        S_AttributeClause(field, mutable != null, expr)
    }

    private val anyRelClause by ( relKeyClause or relIndexClause or relAttributeClause )

    private val relClauses by zeroOrMore(anyRelClause)

    private val classAnnotations by -LPAR * separatedTerms(id, COMMA, false) * -RPAR

    private val classDef by (-CLASS * id * optional(classAnnotations) * -LCURL * relClauses * -RCURL) map {
        (name, annotations, clauses) ->
        S_ClassDefinition(name, annotations ?: listOf(), clauses)
    }

    private val recordDef by (-RECORD * id * -LCURL * zeroOrMore(relAttributeClause) * -RCURL) map { (name, attrs) ->
        S_RecordDefinition(name, attrs)
    }

    private val binaryOperator = (
            ( EQ mapNode { S_BinaryOpCode.EQ } )
            or ( NE mapNode { S_BinaryOpCode.NE } )
            or ( LE mapNode { S_BinaryOpCode.LE } )
            or ( GE mapNode { S_BinaryOpCode.GE } )
            or ( LT mapNode { S_BinaryOpCode.LT } )
            or ( GT mapNode { S_BinaryOpCode.GT } )
            or ( EQ_REF mapNode { S_BinaryOpCode.EQ_REF } )
            or ( NE_REF mapNode { S_BinaryOpCode.NE_REF } )

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
    private val attrExpr by ( DOT * id ) map { (pos, name) -> S_AttrExpr(S_Pos(pos), name) }

    private val intExpr by NUMBER map { S_IntLiteralExpr(S_Pos(it), RellTokenizer.decodeInteger(it)) }

    private val stringExpr = STRINGLIT map { S_StringLiteralExpr(S_Pos(it), RellTokenizer.decodeString(it)) }

    private val bytesExpr by HEXLIT map { S_ByteArrayLiteralExpr(S_Pos(it), RellTokenizer.decodeByteArray(it)) }

    private val booleanLiteralExpr by
            ( FALSE map { S_BooleanLiteralExpr(S_Pos(it), false) } ) or
            ( TRUE map { S_BooleanLiteralExpr(S_Pos(it), true) } )

    private val nullLiteralExpr by NULL map { S_NullLiteralExpr(S_Pos(it)) }

    private val literalExpr by ( intExpr or stringExpr or bytesExpr or booleanLiteralExpr or nullLiteralExpr )

    private val tupleExprField by ( optional(id * -ASSIGN) * _expression ) map { ( name, expr ) -> Pair(name, expr)  }

    private val tupleExprTail by ( -COMMA * separatedTerms(tupleExprField, COMMA, true) )

    private val parenthesesExpr by ( LPAR * tupleExprField * optional(tupleExprTail) * -RPAR ) map {
        (pos, field, tail) ->
        if (tail == null && field.first == null) {
            S_ParenthesesExpr(S_Pos(pos), field.second)
        } else {
            val fields = listOf(field) + (tail ?: listOf())
            S_TupleExpr(S_Pos(pos), fields)
        }
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

    private val atExprWhatName by ( optional(id) * -ASSIGN ) map { name -> S_AtExprWhatAttr(name) }

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

    private val listLiteralExpr by ( LBRACK * separatedTerms(_expression, COMMA, true) * -RBRACK ) map {
        ( pos, exprs ) -> S_ListLiteralExpr(S_Pos(pos), exprs)
    }

    private val mapLiteralExprEntry by ( _expression * -COLON * _expression ) map { (key, value) -> Pair(key, value) }
    private val mapLiteralExpr by ( LBRACK * separatedTerms(mapLiteralExprEntry, COMMA, true) * -RBRACK ) map {
        ( pos, entries ) -> S_MapLiteralExpr(S_Pos(pos), entries)
    }

    private val listExprType by ( -LT * type * -GT )

    private val listExpr by ( LIST * optional(listExprType) * -LPAR * separatedTerms(_expression, COMMA, true) * -RPAR  ) map {
        (kw, type, args) -> S_ListExpr(S_Pos(kw), type, args)
    }

    private val setExpr by ( SET * optional(listExprType) * -LPAR * separatedTerms(_expression, COMMA, true) * -RPAR  ) map {
        (kw, type, args) -> S_SetExpr(S_Pos(kw), type, args)
    }

    private val mapExprType by ( -LT * type * -COMMA * type * -GT )

    private val mapExpr by ( MAP * optional(mapExprType) * -LPAR * separatedTerms(_expression, COMMA, true) * -RPAR ) map {
        ( kw, types, args ) ->
        val keyValueTypes = if (types == null) null else Pair(types.t1, types.t2)
        S_MapExpr(S_Pos(kw), keyValueTypes, args)
    }

    private val createExprArg by ( optional(-optional(DOT) * id * -ASSIGN) * _expression ) map {
        (name, expr) -> S_NameExprPair(name, expr)
    }

    private val createExprArgs by ( -LPAR * separatedTerms(createExprArg, COMMA, true) * -RPAR )

    private val createExpr by (CREATE * id * createExprArgs) map {
        (kw, className, exprs) ->
        S_CreateExpr(S_Pos(kw), className, exprs)
    }

    private val baseExprHead by (
            atExpr
            or nameExpr
            or attrExpr
            or literalExpr
            or parenthesesExpr
            or createExpr
            or listLiteralExpr
            or mapLiteralExpr
            or listExpr
            or setExpr
            or mapExpr
    )

    private val callArg by ( optional(id * -ASSIGN) * _expression ) map {
        (name, expr) -> S_NameExprPair(name, expr)
    }

    private val callArgs by ( -LPAR * separatedTerms(callArg, COMMA, true) * -RPAR )

    private val baseExprTailMember by ( -DOT * id ) map { name -> BaseExprTail_Member(name) }
    private val baseExprTailLookup by ( LBRACK * _expression * -RBRACK ) map { (pos, expr) -> BaseExprTail_Lookup(S_Pos(pos), expr) }
    private val baseExprTailNotNull by ( NOTNULL ) map { BaseExprTail_NotNull(S_Pos(it)) }
    private val baseExprTailSafeMember by ( -SAFECALL * id ) map { name -> BaseExprTail_SafeMember(name) }
    private val baseExprTailCall by callArgs map { args ->
        BaseExprTail_Call(args)
    }

    private val baseExprTailNoCall by ( baseExprTailMember or baseExprTailLookup or baseExprTailNotNull or baseExprTailSafeMember )
    private val baseExprTail by ( baseExprTailNoCall or baseExprTailCall )

    private val baseExpr: Parser<S_Expr> by ( baseExprHead * zeroOrMore(baseExprTail) ) map {
        ( head, tails ) -> tailsToExpr(head, tails)
    }

    private val baseExprNoCall by ( baseExprHead * zeroOrMore(baseExprTailNoCall) ) map {
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

    private val operandExpr: Parser<S_Expr> by baseExpr

    private val unaryExpr by ( optional(unaryOperator) * operandExpr ) map { (op, expr) ->
        if (op == null) expr else S_UnaryExpr(op.pos, S_Node(op.pos, op.value), expr)
    }

    private val binaryExprOperand by ( binaryOperator * unaryExpr ) map { ( op, expr ) -> S_BinaryExprTail(op, expr) }

    private val binaryExpr by ( unaryExpr * zeroOrMore(binaryExprOperand) ) map { ( head, tail ) ->
        if (tail.isEmpty()) head else S_BinaryExpr(head, tail)
    }

    private val expression: Parser<S_Expr> by binaryExpr

    private val emptyStatement by SEMI map { S_EmptyStatement() }

    private val valStatement by ( -VAL * id * optional(-COLON * type ) * -ASSIGN * expression * -SEMI) map {
        (name, type, expr) -> S_ValStatement(name, type, expr)
    }

    private val varStatement by ( -VAR * id * optional(-COLON * type ) * optional(-ASSIGN * expression) * -SEMI) map {
        (name, type, expr) -> S_VarStatement(name, type, expr)
    }

    private val returnStatement by ( RETURN * optional(expression) * -SEMI ) map { ( kw, expr ) ->
        S_ReturnStatement(S_Pos(kw), expr)
    }

    private val assignOp by (
            ( ASSIGN mapNode { S_AssignOpCode.EQ })
            or ( PLUS_ASSIGN mapNode { S_AssignOpCode.PLUS })
            or ( MINUS_ASSIGN mapNode { S_AssignOpCode.MINUS })
            or ( MUL_ASSIGN mapNode { S_AssignOpCode.MUL })
            or ( DIV_ASSIGN mapNode { S_AssignOpCode.DIV })
            or ( MOD_ASSIGN mapNode { S_AssignOpCode.MOD })
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

    private val createStatement by ( createExpr * -SEMI ) map { expr -> S_ExprStatement(expr) }

    private val updateFrom by ( atExprFromItem * optional(atExprFromMulti) ) map {
        ( targetClass, joinClasses ) ->
        listOf(targetClass) + (if (joinClasses == null) listOf() else joinClasses.value)
    }

    private val updateTargetSimple by ( updateFrom * -AT * atExprWhere ) map {
        (from, where) -> S_UpdateTarget_Simple(from, where)
    }

    private val updateTargetExpr by baseExprNoCall map { expr -> S_UpdateTarget_Expr(expr) }

    private val updateTarget by ( updateTargetSimple or updateTargetExpr )

    private val updateWhatNameOp by ( -optional(DOT) * id * assignOp ) map { (name, op) -> Pair(name, op) }

    private val updateWhatExpr by ( optional(updateWhatNameOp) * expression ) map {
        (nameOp, expr) ->
        if (nameOp == null) {
            S_UpdateWhat(expr.startPos, null, null, expr)
        } else {
            val (name, op) = nameOp
            S_UpdateWhat(name.pos, name, op.value, expr)
        }
    }

    private val updateWhat by ( -LPAR * separatedTerms(updateWhatExpr, COMMA, true) * -RPAR )

    private val updateStatement by (UPDATE * updateTarget * updateWhat * -SEMI) map {
        (kw, target, what) -> S_UpdateStatement(S_Pos(kw), target, what)
    }

    private val deleteStatement by (DELETE * updateTarget * -SEMI) map {
        (kw, target) -> S_DeleteStatement(S_Pos(kw), target)
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

    private val functionBodyShort by (-ASSIGN * expression * -SEMI) map { S_FunctionBodyShort(it) }
    private val functionBodyFull by blockStatement map { stmt -> S_FunctionBodyFull(stmt) }
    private val functionBody by ( functionBodyShort or functionBodyFull )

    private val queryDefinition by (-QUERY * id * formalParameters * optional(-COLON * type) * functionBody) map {
        (name, params, type, body) -> S_QueryDefinition(name, params, type, body)
    }

    private val functionDefinition by (-FUNCTION * id * formalParameters * optional(-COLON * type) * functionBody) map {
        (name, params, type, body) -> S_FunctionDefinition(name, params, type, body)
    }

    private val anyDef by ( classDef or recordDef or opDefinition or queryDefinition or functionDefinition )

    override val rootParser by zeroOrMore(anyDef) map { S_ModuleDefinition(it) }

    private fun relltok(s: String): Token = token(s)
}

private fun tailsToExpr(head: S_Expr, tails: List<BaseExprTail>): S_Expr {
    var expr = head
    for (tail in tails) {
        expr = tail.toExpr(expr)
    }
    return expr
}

private sealed class BaseExprTail {
    abstract fun toExpr(base: S_Expr): S_Expr
}

private class BaseExprTail_Member(val name: S_Name): BaseExprTail() {
    override fun toExpr(base: S_Expr): S_Expr = S_MemberExpr(base, name)
}

private class BaseExprTail_SafeMember(val name: S_Name): BaseExprTail() {
    override fun toExpr(base: S_Expr): S_Expr = S_SafeMemberExpr(base, name)
}

private class BaseExprTail_Lookup(val pos: S_Pos, val expr: S_Expr): BaseExprTail() {
    override fun toExpr(base: S_Expr): S_Expr = S_LookupExpr(pos, base, expr)
}

private class BaseExprTail_Call(val args: List<S_NameExprPair>): BaseExprTail() {
    override fun toExpr(base: S_Expr): S_Expr = S_RecordOrCallExpr(base, args)
}

private class BaseExprTail_NotNull(val pos: S_Pos): BaseExprTail() {
    override fun toExpr(base: S_Expr): S_Expr = S_UnaryExpr(base.startPos, S_Node(pos, S_UnaryOp_NotNull), base)
}

private infix fun <T> Parser<TokenMatch>.mapNode(transform: (TokenMatch) -> T): Parser<S_Node<T>> = MapCombinator(this) {
    S_Node(it, transform(it))
}
