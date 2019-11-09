package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.lexer.Token
import com.github.h0tk3y.betterParse.lexer.TokenMatch
import com.github.h0tk3y.betterParse.parser.ErrorResult
import com.github.h0tk3y.betterParse.parser.ParseResult
import com.github.h0tk3y.betterParse.parser.Parsed
import com.github.h0tk3y.betterParse.parser.Parser
import kotlin.reflect.KProperty

object S_Grammar : Grammar<S_RellFile>() {
    private val rellTokens = arrayListOf<RellToken>()

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
    private val DOUBLEQUESTION by relltok("??")
    private val ARROW by relltok("->")
    private val CARET by relltok("^")

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
    private val ENTITY by relltok("entity")
    private val CLASS by relltok("class")
    private val KEY by relltok("key")
    private val INDEX by relltok("index")
    private val OBJECT by relltok("object")
    private val OPERATION by relltok("operation")
    private val QUERY by relltok("query")
    private val STRUCT by relltok("struct")
    private val RECORD by relltok("record")
    private val ENUM by relltok("enum")
    private val FUNCTION by relltok("function")
    private val NAMESPACE by relltok("namespace")
    private val MODULE by relltok("module")
    private val IMPORT by relltok("import")
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
    private val VIRTUAL by relltok("virtual")

    private val FALSE by relltok("false")
    private val TRUE by relltok("true")
    private val NULL by relltok("null")

    private val NUMBER by relltok(RellTokenizer.INTEGER) // Must be exactly INT for Eclipse coloring, but then Xtext assumes it's a decimal Integer
    private val DECIMAL by relltok(RellTokenizer.DECIMAL)
    private val BYTES by relltok(RellTokenizer.BYTEARRAY)
    private val STRING by relltok(RellTokenizer.STRING) // Must be exactly STRING for Eclipse coloring
    private val ID by relltok(RellTokenizer.IDENTIFIER)

    override val tokenizer: RellTokenizer by lazy { RellTokenizer(rellTokens) }

    private val name by ( ID ) map { RellTokenizer.decodeName(it.pos, it.text) }

    private val fullName by separatedTerms(name, DOT, false)

    private val typeRef by parser(this::type)
    private val expressionRef by parser(this::expression)
    private val statementRef by parser(this::statement)

    private val nameType by fullName map { S_NameType(it) }

    private val tupleField by ( optional(name * -COLON) * typeRef ) map { (name, type) -> Pair(name, type) }
    private val tupleType by ( -LPAR * separatedTerms(tupleField, COMMA, false) * -RPAR ) map { S_TupleType(it) }

    private val listType by ( LIST * -LT * typeRef * -GT ) map { (kw, type) -> S_ListType(kw.pos, type) }
    private val setType by ( SET * -LT * typeRef * -GT ) map { (kw, type) -> S_SetType(kw.pos, type) }

    private val mapType by ( MAP * -LT * typeRef * -COMMA * typeRef * -GT ) map { (kw, key, value ) ->
        S_MapType(kw.pos, key, value)
    }

    private val virtualType by ( VIRTUAL * -LT * typeRef * -GT ) map { (kw, type) -> S_VirtualType(kw.pos, type) }

    private val baseType by (
            nameType
            or tupleType
            or listType
            or setType
            or mapType
            or virtualType
    )

    private val type: Parser<S_Type> by ( baseType * zeroOrMore(QUESTION) ) map { (base, nulls) ->
        var res = base
        for (n in nulls) res = S_NullableType(n.pos, res)
        res
    }

    private val annotationArgs by ( -LPAR * separatedTerms(parser(::literalExpr), COMMA, true) * -RPAR )

    private val annotation by ( -AT * name * optional(annotationArgs) ) map {
        (name, args) -> S_Annotation(name, args ?: listOf())
    }

    private val modifier: Parser<S_Modifier> by annotation

    private val nameTypeAttrHeader by name * -COLON * type map { (name, type) -> S_NameTypeAttrHeader(name, type) }

    private val anonAttrHeader by fullName * optional(QUESTION) map {
        (names, nullable) ->
        if (names.size == 1 && nullable == null) {
            S_NameAttrHeader(names[0])
        } else {
            val name = names.last()
            val type = S_NameType(names)
            val resultType = if (nullable == null) type else S_NullableType(nullable.pos, type)
            S_NameTypeAttrHeader(name, resultType)
        }
    }

    private val attrHeader by ( nameTypeAttrHeader or anonAttrHeader )

    private val relFields by separatedTerms(attrHeader, COMMA, false)

    private val relKeyClause by ( KEY * relFields * -SEMI ) map { (kw, attrs) -> S_KeyClause(kw.pos, attrs) }
    private val relIndexClause by ( INDEX * relFields * -SEMI ) map { (kw, attrs) -> S_IndexClause(kw.pos, attrs) }

    private val relAttributeClause by ( optional(MUTABLE) * attrHeader * optional(-ASSIGN * expressionRef) * -SEMI ) map {
        ( mutable, field, expr ) ->
        S_AttributeClause(field, mutable != null, expr)
    }

    private val anyRelClause by ( relKeyClause or relIndexClause or relAttributeClause )

    private val relClauses by zeroOrMore(anyRelClause)

    private val entityAnnotations by -LPAR * separatedTerms(name, COMMA, false) * -RPAR

    private val entityBodyFull by ( -LCURL * relClauses * -RCURL )
    private val entityBodyShort by ( SEMI ) map { _ -> null }
    private val entityBody by ( entityBodyFull or entityBodyShort )

    private val entityKeyword by (ENTITY map { null }) or (CLASS map { it.pos })

    private val entityDef by ( entityKeyword * name * optional(entityAnnotations) * optional(entityBody) ) map {
        (deprecatedKwPos, name, annotations2, clauses) ->
        annotatedDef { S_EntityDefinition(it, deprecatedKwPos, name, annotations2 ?: listOf(), clauses) }
    }

    private val objectDef by ( -OBJECT * name * -LCURL * zeroOrMore(anyRelClause) * -RCURL ) map {
        (name, clauses) -> annotatedDef { S_ObjectDefinition(it, name, clauses) }
    }

    private val structKeyword by (STRUCT map { null }) or (RECORD map { it.pos })

    private val structDef by ( structKeyword * name * -LCURL * zeroOrMore(relAttributeClause) * -RCURL ) map {
        (deprecatedKwPos, name, attrs) -> annotatedDef { S_StructDefinition(it, deprecatedKwPos, name, attrs) }
    }

    private val enumDef by ( -ENUM * name * -LCURL * separatedTerms(name, COMMA, true) * optional(COMMA) * -RCURL ) map {
        (name, values) -> annotatedDef { S_EnumDefinition(it, name, values) }
    }

    private val binaryOperator = (
            ( EQ mapNode { S_BinaryOp.EQ } )
            or ( NE mapNode { S_BinaryOp.NE } )
            or ( LE mapNode { S_BinaryOp.LE } )
            or ( GE mapNode { S_BinaryOp.GE } )
            or ( LT mapNode { S_BinaryOp.LT } )
            or ( GT mapNode { S_BinaryOp.GT } )
            or ( EQ_REF mapNode { S_BinaryOp.EQ_REF } )
            or ( NE_REF mapNode { S_BinaryOp.NE_REF } )

            or ( PLUS mapNode { S_BinaryOp.PLUS } )
            or ( MINUS mapNode { S_BinaryOp.MINUS } )
            or ( MUL mapNode { S_BinaryOp.MUL } )
            or ( DIV mapNode { S_BinaryOp.DIV } )
            or ( MOD mapNode { S_BinaryOp.MOD } )

            or ( AND mapNode { S_BinaryOp.AND } )
            or ( OR mapNode { S_BinaryOp.OR } )

            or ( IN mapNode { S_BinaryOp.IN } )
            or ( ELVIS mapNode { S_BinaryOp.ELVIS } )
    )

    private val incrementOperator = (
            ( PLUSPLUS mapNode { true }  )
            or ( MINUSMINUS mapNode { false }  )
    )

    private val unaryPrefixOperator = (
            ( PLUS mapNode { S_UnaryOp_Plus } )
            or ( MINUS mapNode { S_UnaryOp_Minus }  )
            or ( NOT mapNode { S_UnaryOp_Not }  )
            or ( incrementOperator map { S_PosValue(it.pos, S_UnaryOp_IncDec(it.value, false)) } )
    )

    private val unaryPostfixOperator = (
            ( incrementOperator map { S_PosValue(it.pos, S_UnaryOp_IncDec(it.value, true)) } )
            or ( DOUBLEQUESTION mapNode { S_UnaryOp_IsNull } )
    )

    private val nameExpr by name map { S_NameExpr(it) }
    private val attrExpr by ( DOT * name ) map { (pos, name) -> S_AttrExpr(pos.pos, name) }

    private val intExpr by NUMBER map { S_IntegerLiteralExpr(it.pos, RellTokenizer.decodeInteger(it.pos, it.text)) }

    private val decimalExpr by DECIMAL map { S_DecimalLiteralExpr(it.pos, RellTokenizer.decodeDecimal(it.pos, it.text)) }

    private val stringExpr = STRING map { S_StringLiteralExpr(it.pos, RellTokenizer.decodeString(it.pos, it.text)) }

    private val bytesExpr by BYTES map { S_ByteArrayLiteralExpr(it.pos, RellTokenizer.decodeByteArray(it.pos, it.text)) }

    private val booleanLiteralExpr by
            ( FALSE map { S_BooleanLiteralExpr(it.pos, false) } ) or
            ( TRUE map { S_BooleanLiteralExpr(it.pos, true) } )

    private val nullLiteralExpr by NULL map { S_NullLiteralExpr(it.pos) }

    private val literalExpr by ( intExpr or decimalExpr or stringExpr or bytesExpr or booleanLiteralExpr or nullLiteralExpr )

    private val tupleExprField by ( optional(name * -ASSIGN) * expressionRef ) map { ( name, expr ) -> Pair(name, expr)  }

    private val tupleExprTail by ( -COMMA * separatedTerms(tupleExprField, COMMA, true) )

    private val parenthesesExpr by ( LPAR * tupleExprField * optional(tupleExprTail) * -RPAR ) map {
        (pos, field, tail) ->
        if (tail == null && field.first == null) {
            S_ParenthesesExpr(pos.pos, field.second)
        } else {
            val fields = listOf(field) + (tail ?: listOf())
            S_TupleExpr(pos.pos, fields)
        }
    }

    private val atExprFromSingle by fullName map { S_PosValue(it[0].pos, listOf(S_AtExprFrom(null, it))) }

    private val atExprFromItem by ( optional( name * -COLON ) * fullName ) map {
        ( alias, entityName ) -> S_AtExprFrom(alias, entityName)
    }

    private val atExprFromMulti by ( LPAR * separatedTerms( atExprFromItem, COMMA, false ) * -RPAR ) map {
        ( pos, items ) -> S_PosValue(pos, items)
    }

    private val atExprFrom by ( atExprFromSingle or atExprFromMulti )

    private val atExprAt by (
            ( AT * QUESTION map { S_AtCardinality.ZERO_ONE } )
            or ( AT * MUL map { S_AtCardinality.ZERO_MANY } )
            or ( AT * PLUS map { S_AtCardinality.ONE_MANY } )
            or ( AT map { S_AtCardinality.ONE } )
    )

    private val atExprWhatSimple by oneOrMore((-DOT * name)) map { path -> S_AtExprWhatSimple(path) }

    private val atExprWhatSort by ( optional(MINUS) * SORT ) map { (minus, _) -> minus == null }

    private val atExprWhatName by ( optional(name) * -ASSIGN ) map { name -> S_AtExprWhatAttr(name) }

    private val atExprWhatComplexItem by ( optional(atExprWhatSort) * optional(atExprWhatName) * expressionRef ) map {
        (sort, name, expr) -> S_AtExprWhatComplexField(name, expr, sort)
    }

    private val atExprWhatComplex by ( -LPAR * separatedTerms(atExprWhatComplexItem, COMMA, false) * -RPAR ) map {
        exprs -> S_AtExprWhatComplex(exprs)
    }

    private val atExprWhat by ( atExprWhatSimple or atExprWhatComplex )

    private val atExprWhere by ( -LCURL * separatedTerms(expressionRef, COMMA, true) * -RCURL ) map {
        exprs -> S_AtExprWhere(exprs)
    }

    private val atExprLimit by ( -LIMIT * expressionRef )

    private val atExpr by ( atExprFrom * atExprAt * atExprWhere * optional(atExprWhat) * optional(atExprLimit) ) map {
        ( from, cardinality, where, whatOpt, limit ) ->
        val what = if (whatOpt == null) S_AtExprWhatDefault() else whatOpt
        S_AtExpr(from.pos, cardinality, from.value, where, what, limit)
    }

    private val listLiteralExpr by ( LBRACK * separatedTerms(expressionRef, COMMA, true) * -RBRACK ) map {
        ( pos, exprs ) -> S_ListLiteralExpr(pos.pos, exprs)
    }

    private val mapLiteralExprEntry by ( expressionRef * -COLON * expressionRef ) map { (key, value) -> Pair(key, value) }
    private val mapLiteralExpr by ( LBRACK * separatedTerms(mapLiteralExprEntry, COMMA, true) * -RBRACK ) map {
        ( pos, entries ) -> S_MapLiteralExpr(pos.pos, entries)
    }

    private val listExprType by -LT * type * -GT

    private val exprArgs by -LPAR * separatedTerms(expressionRef, COMMA, true) * -RPAR

    private val listExpr by ( LIST * optional(listExprType) * optional(exprArgs) ) map {
        (kw, type, args) -> S_ListExpr(kw.pos, type, args)
    }

    private val setExpr by ( SET * optional(listExprType) * optional(exprArgs) ) map {
        (kw, type, args) -> S_SetExpr(kw.pos, type, args)
    }

    private val mapExprType by ( -LT * type * -COMMA * type * -GT )

    private val mapExpr by ( MAP * optional(mapExprType) * optional(exprArgs) ) map {
        ( kw, types, args ) ->
        val keyValueTypes = if (types == null) null else Pair(types.t1, types.t2)
        S_MapExpr(kw.pos, keyValueTypes, args)
    }

    private val createExprArg by ( optional(-optional(DOT) * name * -ASSIGN) * expressionRef ) map {
        (name, expr) -> S_NameExprPair(name, expr)
    }

    private val createExprArgs by ( -LPAR * separatedTerms(createExprArg, COMMA, true) * -RPAR )

    private val createExpr by (CREATE * fullName * createExprArgs) map {
        (kw, entityName, exprs) ->
        S_CreateExpr(kw.pos, entityName, exprs)
    }

    private val virtualExpr by virtualType map { type -> S_VirtualExpr(type) }

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
            or virtualExpr
    )

    private val baseExprHead by ( atExpr or baseExprHeadNoAt )

    private val callArg by ( optional(name * -ASSIGN) * expressionRef ) map {
        (name, expr) -> S_NameExprPair(name, expr)
    }

    private val callArgs by ( -LPAR * separatedTerms(callArg, COMMA, true) * -RPAR )

    private val baseExprTailMember by ( -DOT * name ) map { name -> BaseExprTail_Member(name) }
    private val baseExprTailLookup by ( LBRACK * expressionRef * -RBRACK ) map { (pos, expr) -> BaseExprTail_Lookup(pos.pos, expr) }
    private val baseExprTailNotNull by ( NOTNULL ) map { BaseExprTail_NotNull(it.pos) }
    private val baseExprTailSafeMember by ( -SAFECALL * name ) map { name -> BaseExprTail_SafeMember(name) }
    private val baseExprTailUnaryPostfixOp by unaryPostfixOperator map { BaseExprTail_UnaryPostfixOp(it.pos, it.value) }

    private val baseExprTailCall by callArgs map { args ->
        BaseExprTail_Call(args)
    }

    private val baseExprTailNoCall by (
            baseExprTailMember
            or baseExprTailLookup
            or baseExprTailNotNull
            or baseExprTailSafeMember
            or baseExprTailUnaryPostfixOp
    )

    private val baseExprTail by ( baseExprTailNoCall or baseExprTailCall )

    private val baseExpr: Parser<S_Expr> by ( baseExprHead * zeroOrMore(baseExprTail) ) map {
        ( head, tails ) -> tailsToExpr(head, tails)
    }

    private val baseExprNoCallNoAt by ( baseExprHeadNoAt * zeroOrMore(baseExprTailNoCall) ) map {
        ( head, tails ) -> tailsToExpr(head, tails)
    }

    private val ifExpr by ( IF * -LPAR * expressionRef * -RPAR * expressionRef * -ELSE * expressionRef ) map {
        ( pos, cond, trueExpr, falseExpr ) ->
        S_IfExpr(pos.pos, cond, trueExpr, falseExpr)
    }

    private val whenConditionExpr by separatedTerms(expressionRef, COMMA, false) map { exprs -> S_WhenConditionExpr(exprs) }
    private val whenConditionElse by ELSE map { S_WhenCondtiionElse(it.pos) }
    private val whenCondition by whenConditionExpr or whenConditionElse

    private val whenExprCase by whenCondition * -ARROW * expressionRef map {
        (cond, expr) -> S_WhenExprCase(cond, expr)
    }

    private val whenExprCases by separatedTerms(whenExprCase, oneOrMore(SEMI), false) * zeroOrMore(SEMI) map {
        (cases, _) -> cases
    }

    private val whenExpr by WHEN * optional(-LPAR * expressionRef * -RPAR) * -LCURL * whenExprCases * -RCURL map {
        (pos, expr, cases) -> S_WhenExpr(pos.pos, expr, cases)
    }

    private val operandExpr: Parser<S_Expr> by ( baseExpr or ifExpr or whenExpr )

    private val unaryExpr by ( zeroOrMore(unaryPrefixOperator) * operandExpr ) map { (ops, expr) ->
        var res = expr
        for (op in ops.reversed()) {
            res = S_UnaryExpr(op.pos, S_PosValue(op.pos, op.value), res)
        }
        res
    }

    private val binaryExprOperand by ( binaryOperator * unaryExpr ) map { ( op, expr ) -> S_BinaryExprTail(op, expr) }

    private val binaryExpr by ( unaryExpr * zeroOrMore(binaryExprOperand) ) map { ( head, tail ) ->
        if (tail.isEmpty()) head else S_BinaryExpr(head, tail)
    }

    private val expression: Parser<S_Expr> by binaryExpr

    private val emptyStmt by SEMI map { S_EmptyStatement(it.pos) }

    private val varVal by (
            ( VAL map { S_PosValue(it, false) } )
            or ( VAR map { S_PosValue(it, true) } )
    )

    private val simpleVarDeclarator by attrHeader map { S_SimpleVarDeclarator(it) }

    private val tupleVarDeclarator by LPAR * separatedTerms(parser(this::varDeclarator), COMMA, false) * -RPAR map {
        (pos, decls) -> S_TupleVarDeclarator(pos.pos, decls)
    }

    private val varDeclarator: Parser<S_VarDeclarator> by simpleVarDeclarator or tupleVarDeclarator

    private val varStmt by ( varVal * varDeclarator * optional(-ASSIGN * expression) * -SEMI) map {
        (mutable, declarator, expr) -> S_VarStatement(mutable.pos, declarator, expr, mutable.value)
    }

    private val returnStmt by ( RETURN * optional(expression) * -SEMI ) map { ( kw, expr ) ->
        S_ReturnStatement(kw.pos, expr)
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
        (op, expr) -> S_ExprStatement(S_UnaryExpr(op.pos, S_PosValue(op.pos, S_UnaryOp_IncDec(op.value, false)), expr))
    }

    private val blockStmt by ( LCURL * zeroOrMore(statementRef) * -RCURL ) map {
        (pos, statements) -> S_BlockStatement(pos.pos, statements)
    }

    private val ifStmt by ( IF * -LPAR * expression * -RPAR * statementRef * optional(-ELSE * statementRef) ) map {
        (pos, expr, trueStmt, falseStmt) -> S_IfStatement(pos.pos, expr, trueStmt, falseStmt)
    }

    private val whenStmtCase by whenCondition * -ARROW * statementRef * zeroOrMore(SEMI) map {
        (cond, stmt) -> S_WhenStatementCase(cond, stmt)
    }

    private val whenStmt by WHEN * optional(-LPAR * expressionRef * -RPAR) * -LCURL * zeroOrMore(whenStmtCase) * -RCURL map {
        (pos, expr, cases) -> S_WhenStatement(pos.pos, expr, cases)
    }

    private val whileStmt by ( WHILE * -LPAR * expression * -RPAR * statementRef ) map {
        (pos, expr, stmt) -> S_WhileStatement(pos.pos, expr, stmt)
    }

    private val forStmt by ( FOR * -LPAR * varDeclarator * -IN * expression * -RPAR * statementRef ) map {
        (pos, declarator, expr, stmt) -> S_ForStatement(pos.pos, declarator, expr, stmt)
    }

    private val breakStmt by ( BREAK * -SEMI ) map { S_BreakStatement(it.pos) }

    private val callStmt by ( baseExpr * -SEMI ) map { expr -> S_ExprStatement(expr) }

    private val createStmt by ( createExpr * -SEMI ) map { expr -> S_ExprStatement(expr) }

    private val updateTargetAt by ( atExprFrom * atExprAt * atExprWhere ) map {
        (from, cardinality, where) -> S_UpdateTarget_Simple(cardinality, from.value, where)
    }

    private val updateTargetExpr by baseExprNoCallNoAt map { expr -> S_UpdateTarget_Expr(expr) }

    private val updateTarget by ( updateTargetAt or updateTargetExpr )

    private val updateWhatNameOp by ( -optional(DOT) * name * assignOp ) map { (name, op) -> Pair(name, op) }

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
        (kw, target, what) -> S_UpdateStatement(kw.pos, target, what)
    }

    private val deleteStmt by (DELETE * updateTarget * -SEMI) map {
        (kw, target) -> S_DeleteStatement(kw.pos, target)
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

    private val formalParameters by ( -LPAR * separatedTerms(attrHeader, COMMA, true) * -RPAR )

    private val opDef by ( -OPERATION * name * formalParameters * blockStmt ) map {
        (name, params, body) -> annotatedDef { S_OperationDefinition(it, name, params, body) }
    }

    private val functionBodyShort by (-ASSIGN * expression * -SEMI) map { S_FunctionBodyShort(it) }
    private val functionBodyFull by blockStmt map { stmt -> S_FunctionBodyFull(stmt) }
    private val functionBody by ( functionBodyShort or functionBodyFull )

    private val queryDef by ( -QUERY * name * formalParameters * optional(-COLON * type) * functionBody ) map {
        (name, params, type, body) -> annotatedDef { S_QueryDefinition(it, name, params, type, body) }
    }

    private val functionDef by ( -FUNCTION * name * formalParameters * optional(-COLON * type) * functionBody ) map {
        (name, params, type, body) -> annotatedDef { S_FunctionDefinition(it, name, params, type, body) }
    }

    private val namespaceDef by ( -NAMESPACE * optional(name) * -LCURL * zeroOrMore(parser(this::annotatedDef)) * -RCURL ) map {
        (name, defs) -> annotatedDef { S_NamespaceDefinition(it, name, defs) }
    }

    private val absoluteImportPath by separatedTerms(name, DOT, false) map { S_ImportPath(null, it) }

    private val relativeImportPath by DOT * separatedTerms(name, DOT, true) map {
        (pos, path) -> S_ImportPath(S_RelativeImportPath(pos.pos, 0), path)
    }

    private val upImportPath by oneOrMore(CARET) * optional(-DOT * separatedTerms(name, DOT, false)) map {
        (carets, path) -> S_ImportPath(S_RelativeImportPath(carets[0].pos, carets.size), path ?: listOf())
    }

    private val importPath by absoluteImportPath or relativeImportPath or upImportPath

    private val importDef by ( IMPORT * optional( name * -COLON ) * importPath * -SEMI ) map {
        (pos, alias, path) -> annotatedDef { S_ImportDefinition(it, pos.pos, alias, path) }
    }

    private val includeDef by ( INCLUDE * STRING * -SEMI ) map {
        (pos, _) -> annotatedDef { S_IncludeDefinition(pos.pos) }
    }

    private val anyDef: Parser<AnnotatedDef> by (
            entityDef
            or objectDef
            or structDef
            or enumDef
            or opDef
            or queryDef
            or functionDef
            or namespaceDef
            or importDef
            or includeDef
    )

    private val annotatedDef by zeroOrMore(modifier) * anyDef map {
        (modifiers, def) -> def.createDef(S_Modifiers(modifiers))
    }

    private val moduleHeader by zeroOrMore(modifier) * MODULE * -SEMI map {
        (modifiers, kw) -> S_ModuleHeader(S_Modifiers(modifiers), kw.pos)
    }

    override val rootParser by optional(moduleHeader) * zeroOrMore(annotatedDef) map {
        (header, defs) -> S_RellFile(header, defs)
    }

    private fun relltok(s: String): RellToken {
        val t = token(s)
        return RellToken(t.name ?: "", t)
    }

    private operator fun RellToken.provideDelegate(thisRef: Grammar<*>, property: KProperty<*>): RellToken {
        val ex = if (token.name != null) this else RellToken(property.name, token)
        rellTokens.add(ex)
        return ex
    }
}

private fun tailsToExpr(head: S_Expr, tails: List<BaseExprTail>): S_Expr {
    var expr = head
    for (tail in tails) {
        expr = tail.toExpr(expr)
    }
    return expr
}

private class AnnotatedDef(private val creator: (S_Modifiers) -> S_Definition) {
    fun createDef(modifiers: S_Modifiers) = creator(modifiers)
}

private fun annotatedDef(creator: (S_Modifiers) -> S_Definition): AnnotatedDef = AnnotatedDef(creator)

private sealed class BaseExprTail {
    abstract fun toExpr(base: S_Expr): S_Expr
}

private class BaseExprTail_Member(val name: S_Name): BaseExprTail() {
    override fun toExpr(base: S_Expr) = S_MemberExpr(base, name)
}

private class BaseExprTail_SafeMember(val name: S_Name): BaseExprTail() {
    override fun toExpr(base: S_Expr) = S_SafeMemberExpr(base, name)
}

private class BaseExprTail_Lookup(val pos: S_Pos, val expr: S_Expr): BaseExprTail() {
    override fun toExpr(base: S_Expr) = S_LookupExpr(pos, base, expr)
}

private class BaseExprTail_Call(val args: List<S_NameExprPair>): BaseExprTail() {
    override fun toExpr(base: S_Expr) = S_StructOrCallExpr(base, args)
}

private class BaseExprTail_NotNull(val pos: S_Pos): BaseExprTail() {
    override fun toExpr(base: S_Expr) = S_UnaryExpr(base.startPos, S_PosValue(pos, S_UnaryOp_NotNull), base)
}

private class BaseExprTail_UnaryPostfixOp(val pos: S_Pos, val op: S_UnaryOp): BaseExprTail() {
    override fun toExpr(base: S_Expr) = S_UnaryExpr(base.startPos, S_PosValue(pos, op), base)
}

private infix fun <T> Parser<RellTokenMatch>.mapNode(transform: (RellTokenMatch) -> T): Parser<S_PosValue<T>> = MapCombinator(this) {
    S_PosValue(it, transform(it))
}

class RellToken(val name: String, val token: Token): Parser<RellTokenMatch> {
    override fun tryParse(tokens: Sequence<TokenMatch>): ParseResult<RellTokenMatch> {
        val r = token.tryParse(tokens)
        if (r is ErrorResult) {
            return r
        }

        r as Parsed<TokenMatch>

        val t = r.value
        val file = C_Parser.currentFile()
        val pos = S_BasicPos(file, t.row, t.column)

        return Parsed(RellTokenMatch(pos, t.text), r.remainder)
    }
}

class RellTokenMatch(val pos: S_Pos, val text: String)
