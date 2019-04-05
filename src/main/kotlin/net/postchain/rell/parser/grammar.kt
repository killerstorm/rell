package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.lexer.Token
import com.github.h0tk3y.betterParse.lexer.TokenMatch
import com.github.h0tk3y.betterParse.parser.Parser

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
    private val ARROW by relltok("->")

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
    private val PLUSPLUS by relltok("++")
    private val MINUSMINUS by relltok("--")

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
    private val OBJECT by relltok("object")
    private val OPERATION by relltok("operation")
    private val QUERY by relltok("query")
    private val RECORD by relltok("record")
    private val ENUM by relltok("enum")
    private val FUNCTION by relltok("function")
    private val NAMESPACE by relltok("namespace")
    private val EXTERNAL by relltok("external")
    private val INCLUDE by relltok("include")
    private val VAL by relltok("val")
    private val VAR by relltok("var")
    private val RETURN by relltok("return")
    private val IF by relltok("if")
    private val ELSE by relltok("else")
    private val WHEN by relltok("when")
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

    private val fullName by separatedTerms(id, DOT, false)

    private val _type by parser(this::type)
    private val _expression by parser(this::expression)
    private val _statement by parser(this::statement)

    private val nameType by fullName map { S_NameType(it) }

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

    private val classBodyFull by ( -LCURL * relClauses * -RCURL )
    private val classBodyShort by ( SEMI ) map { _ -> null }
    private val classBody by ( classBodyFull or classBodyShort )

    private val classDef by ( -CLASS * id * optional(classAnnotations) * optional(classBody) ) map {
        (name, annotations, clauses) ->
        S_ClassDefinition(name, annotations ?: listOf(), clauses)
    }

    private val objectDef by ( -OBJECT * id * -LCURL * zeroOrMore(anyRelClause) * -RCURL ) map { (name, clauses) ->
        S_ObjectDefinition(name, clauses)
    }

    private val recordDef by ( -RECORD * id * -LCURL * zeroOrMore(relAttributeClause) * -RCURL ) map { (name, attrs) ->
        S_RecordDefinition(name, attrs)
    }

    private val enumDef by ( -ENUM * id * -LCURL * separatedTerms(id, COMMA, true) * optional(COMMA) * -RCURL ) map {
        (name, values) ->
        S_EnumDefinition(name, values)
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

    private val incrementOperator = (
            ( PLUSPLUS mapNode { true }  )
            or ( MINUSMINUS mapNode { false }  )
    )

    private val unaryOperator = (
            ( PLUS mapNode { S_UnaryOp_Plus } )
            or ( MINUS mapNode { S_UnaryOp_Minus }  )
            or ( NOT mapNode { S_UnaryOp_Not }  )
            or ( incrementOperator map { S_Node(it.pos, S_UnaryOp_IncDec(it.value, false)) } )
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

    private val atExprFromSingle by fullName map { S_Node(it[0].pos, listOf(S_AtExprFrom(null, it))) }

    private val atExprFromItem by ( optional( id * -COLON ) * fullName ) map {
        ( alias, className ) -> S_AtExprFrom(alias, className)
    }

    private val atExprFromMulti by ( LPAR * separatedTerms( atExprFromItem, COMMA, false ) * -RPAR ) map {
        ( pos, items ) -> S_Node(pos, items)
    }

    private val atExprFrom by ( atExprFromSingle or atExprFromMulti )

    private val atExprAt by (
            ( AT * QUESTION map { S_AtCardinality.ZERO_ONE } )
            or ( AT * MUL map { S_AtCardinality.ZERO_MANY } )
            or ( AT * PLUS map { S_AtCardinality.ONE_MANY } )
            or ( AT map { S_AtCardinality.ONE } )
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
        ( from, cardinality, where, whatOpt, limit ) ->
        val what = if (whatOpt == null) S_AtExprWhatDefault() else whatOpt
        S_AtExpr(from.pos, cardinality, from.value, where, what, limit)
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

    private val createExpr by (CREATE * fullName * createExprArgs) map {
        (kw, className, exprs) ->
        S_CreateExpr(S_Pos(kw), className, exprs)
    }

    private val baseExprHeadNoAt by (
            nameExpr
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

    private val baseExprHead by ( atExpr or baseExprHeadNoAt )

    private val callArg by ( optional(id * -ASSIGN) * _expression ) map {
        (name, expr) -> S_NameExprPair(name, expr)
    }

    private val callArgs by ( -LPAR * separatedTerms(callArg, COMMA, true) * -RPAR )

    private val baseExprTailMember by ( -DOT * id ) map { name -> BaseExprTail_Member(name) }
    private val baseExprTailLookup by ( LBRACK * _expression * -RBRACK ) map { (pos, expr) -> BaseExprTail_Lookup(S_Pos(pos), expr) }
    private val baseExprTailNotNull by ( NOTNULL ) map { BaseExprTail_NotNull(S_Pos(it)) }
    private val baseExprTailSafeMember by ( -SAFECALL * id ) map { name -> BaseExprTail_SafeMember(name) }
    private val baseExprTailIncrement by incrementOperator map { BaseExprTail_IncDec(it.pos, it.value) }

    private val baseExprTailCall by callArgs map { args ->
        BaseExprTail_Call(args)
    }

    private val baseExprTailNoCall by (
            baseExprTailMember
            or baseExprTailLookup
            or baseExprTailNotNull
            or baseExprTailSafeMember
            or baseExprTailIncrement
    )

    private val baseExprTail by ( baseExprTailNoCall or baseExprTailCall )

    private val baseExpr: Parser<S_Expr> by ( baseExprHead * zeroOrMore(baseExprTail) ) map {
        ( head, tails ) -> tailsToExpr(head, tails)
    }

    private val baseExprNoCallNoAt by ( baseExprHeadNoAt * zeroOrMore(baseExprTailNoCall) ) map {
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

    private val ifExpr by ( IF * -LPAR * _expression * -RPAR * _expression * -ELSE * _expression ) map {
        ( pos, cond, trueExpr, falseExpr ) ->
        S_IfExpr(S_Pos(pos), cond, trueExpr, falseExpr)
    }

    private val whenConditionExpr by separatedTerms(_expression, COMMA, false) map { exprs -> S_WhenConditionExpr(exprs) }
    private val whenConditionElse by ELSE map { S_WhenCondtiionElse(S_Pos(it)) }
    private val whenCondition by whenConditionExpr or whenConditionElse

    private val whenExprCase by whenCondition * -ARROW * _expression map {
        (cond, expr) -> S_WhenExprCase(cond, expr)
    }

    private val whenExprCases by separatedTerms(whenExprCase, oneOrMore(SEMI), false) * zeroOrMore(SEMI) map {
        (cases, _) -> cases
    }

    private val whenExpr by WHEN * optional(-LPAR * _expression * -RPAR) * -LCURL * whenExprCases * -RCURL map {
        (pos, expr, cases) -> S_WhenExpr(S_Pos(pos), expr, cases)
    }

    private val operandExpr: Parser<S_Expr> by ( baseExpr or ifExpr or whenExpr )

    private val unaryExpr by ( zeroOrMore(unaryOperator) * operandExpr ) map { (ops, expr) ->
        var res = expr
        for (op in ops.reversed()) {
            res = S_UnaryExpr(op.pos, S_Node(op.pos, op.value), res)
        }
        res
    }

    private val binaryExprOperand by ( binaryOperator * unaryExpr ) map { ( op, expr ) -> S_BinaryExprTail(op, expr) }

    private val binaryExpr by ( unaryExpr * zeroOrMore(binaryExprOperand) ) map { ( head, tail ) ->
        if (tail.isEmpty()) head else S_BinaryExpr(head, tail)
    }

    private val expression: Parser<S_Expr> by binaryExpr

    private val emptyStmt by SEMI map { S_EmptyStatement(S_Pos(it)) }

    private val varVal by (
            ( VAL map { S_Node(it, false) } )
            or ( VAR map { S_Node(it, true) } )
    )

    private val simpleVarDeclarator by id * optional( -COLON * type ) map { (name, type) ->
        S_SimpleVarDeclarator(name, type)
    }

    private val tupleVarDeclarator by LPAR * separatedTerms(parser(this::varDeclarator), COMMA, false) * -RPAR map {
        (pos, decls) -> S_TupleVarDeclarator(S_Pos(pos), decls)
    }

    private val varDeclarator: Parser<S_VarDeclarator> by simpleVarDeclarator or tupleVarDeclarator

    private val varStmt by ( varVal * varDeclarator * optional(-ASSIGN * expression) * -SEMI) map {
        (mutable, declarator, expr) -> S_VarStatement(mutable.pos, declarator, expr, mutable.value)
    }

    private val returnStmt by ( RETURN * optional(expression) * -SEMI ) map { ( kw, expr ) ->
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

    private val assignStmt by ( baseExpr * assignOp * expression * -SEMI ) map {
        (expr1, op, expr2) -> S_AssignStatement(expr1, op, expr2)
    }

    private val incrementStmt by ( incrementOperator * baseExpr * -SEMI ) map {
        (op, expr) -> S_ExprStatement(S_UnaryExpr(op.pos, S_Node(op.pos, S_UnaryOp_IncDec(op.value, false)), expr))
    }

    private val blockStmt by ( LCURL * zeroOrMore(_statement) * -RCURL ) map {
        (pos, statements) -> S_BlockStatement(S_Pos(pos), statements)
    }

    private val ifStmt by ( IF * -LPAR * expression * -RPAR * _statement * optional(-ELSE * _statement) ) map {
        (pos, expr, trueStmt, falseStmt) -> S_IfStatement(S_Pos(pos), expr, trueStmt, falseStmt)
    }

    private val whenStmtCase by whenCondition * -ARROW * _statement * zeroOrMore(SEMI) map {
        (cond, stmt) -> S_WhenStatementCase(cond, stmt)
    }

    private val whenStmt by WHEN * optional(-LPAR * _expression * -RPAR) * -LCURL * zeroOrMore(whenStmtCase) * -RCURL map {
        (pos, expr, cases) -> S_WhenStatement(S_Pos(pos), expr, cases)
    }

    private val whileStmt by ( WHILE * -LPAR * expression * -RPAR * _statement ) map {
        (pos, expr, stmt) -> S_WhileStatement(S_Pos(pos), expr, stmt)
    }

    private val forStmt by ( FOR * -LPAR * varDeclarator * -IN * expression * -RPAR * _statement ) map {
        (pos, declarator, expr, stmt) -> S_ForStatement(S_Pos(pos), declarator, expr, stmt)
    }

    private val breakStmt by ( BREAK * -SEMI ) map { S_BreakStatement(S_Pos(it)) }

    private val callStmt by ( baseExpr * -SEMI ) map { expr -> S_ExprStatement(expr) }

    private val createStmt by ( createExpr * -SEMI ) map { expr -> S_ExprStatement(expr) }

    private val updateTargetAt by ( atExprFrom * atExprAt * atExprWhere ) map {
        (from, cardinality, where) -> S_UpdateTarget_Simple(cardinality, from.value, where)
    }

    private val updateTargetExpr by baseExprNoCallNoAt map { expr -> S_UpdateTarget_Expr(expr) }

    private val updateTarget by ( updateTargetAt or updateTargetExpr )

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

    private val updateStmt by (UPDATE * updateTarget * updateWhat * -SEMI) map {
        (kw, target, what) -> S_UpdateStatement(S_Pos(kw), target, what)
    }

    private val deleteStmt by (DELETE * updateTarget * -SEMI) map {
        (kw, target) -> S_DeleteStatement(S_Pos(kw), target)
    }

    private val statement: Parser<S_Statement> by (
            emptyStmt
            or varStmt
            or assignStmt
            or incrementStmt
            or returnStmt
            or blockStmt
            or ifStmt
            or whenStmt
            or whileStmt
            or forStmt
            or breakStmt
            or callStmt
            or createStmt
            or updateStmt
            or deleteStmt
    )

    private val formalParameters by ( -LPAR * separatedTerms(relField, COMMA, true) * -RPAR )

    private val opDef by (-OPERATION * id * formalParameters * blockStmt) map {
        (name, params, body) ->
        S_OpDefinition(name, params, body)
    }

    private val functionBodyShort by (-ASSIGN * expression * -SEMI) map { S_FunctionBodyShort(it) }
    private val functionBodyFull by blockStmt map { stmt -> S_FunctionBodyFull(stmt) }
    private val functionBody by ( functionBodyShort or functionBodyFull )

    private val queryDef by (-QUERY * id * formalParameters * optional(-COLON * type) * functionBody) map {
        (name, params, type, body) -> S_QueryDefinition(name, params, type, body)
    }

    private val functionDef by (-FUNCTION * id * formalParameters * optional(-COLON * type) * functionBody) map {
        (name, params, type, body) -> S_FunctionDefinition(name, params, type, body)
    }

    private val namespaceDef by ( -NAMESPACE * id * -LCURL * zeroOrMore(parser(this::anyDef)) * -RCURL ) map {
        (name, defs) -> S_NamespaceDefinition(name, defs)
    }

    private val externalDef by ( EXTERNAL * STRINGLIT * -LCURL * zeroOrMore(parser(this::anyDef)) * -RCURL ) map {
        (pos, name, defs) -> S_ExternalDefinition(S_Pos(pos), name.text, defs)
    }

    private val includeDef by ( INCLUDE * STRINGLIT * -SEMI ) map {
        (pos, path) -> S_IncludeDefinition(S_Pos(pos), S_Pos(path), path.text)
    }

    private val anyDef: Parser<S_Definition> by (
            classDef
            or objectDef
            or recordDef
            or enumDef
            or opDef
            or queryDef
            or functionDef
            or namespaceDef
            or externalDef
            or includeDef
    )

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

private class BaseExprTail_IncDec(val pos: S_Pos, val inc: Boolean): BaseExprTail() {
    override fun toExpr(base: S_Expr): S_Expr {
        val op = S_UnaryOp_IncDec(inc, true)
        return S_UnaryExpr(base.startPos, S_Node(pos, op), base)
    }
}

private infix fun <T> Parser<TokenMatch>.mapNode(transform: (TokenMatch) -> T): Parser<S_Node<T>> = MapCombinator(this) {
    S_Node(it, transform(it))
}
