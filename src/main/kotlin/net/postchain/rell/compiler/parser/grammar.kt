/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.parser

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.parser.Parser
import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.model.R_KeyIndexKind
import net.postchain.rell.model.expr.R_AtCardinality
import net.postchain.rell.utils.immListOf
import kotlin.reflect.KProperty

object S_Keywords {
    const val ABSTRACT = "abstract"
    const val OVERRIDE = "override"
}

object S_Grammar : Grammar<S_RellFile>() {
    private val rellTokens = arrayListOf<RellToken>()

    private val LPAR by relltok("(")
    private val RPAR by relltok(")")
    private val LCURL by relltok("{")
    private val RCURL by relltok("}")
    private val LBRACK by relltok("[")
    private val RBRACK by relltok("]")
    private val AT by relltok("@")
    private val DOLLAR by relltok("$")
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

    private val ABSTRACT by relltok(S_Keywords.ABSTRACT)
    private val BREAK by relltok("break")
    private val CLASS by relltok("class")
    private val CONTINUE by relltok("continue")
    private val CREATE by relltok("create")
    private val DELETE by relltok("delete")
    private val ELSE by relltok("else")
    private val ENTITY by relltok("entity")
    private val ENUM by relltok("enum")
    private val FALSE by relltok("false")
    private val FOR by relltok("for")
    private val FUNCTION by relltok("function")
    private val GUARD by relltok("guard")
    private val IF by relltok("if")
    private val IMPORT by relltok("import")
    private val IN by relltok("in")
    private val INCLUDE by relltok("include")
    private val INDEX by relltok("index")
    private val KEY by relltok("key")
    private val LIMIT by relltok("limit")
    private val MODULE by relltok("module")
    private val MUTABLE by relltok("mutable")
    private val NAMESPACE by relltok("namespace")
    private val NULL by relltok("null")
    private val OBJECT by relltok("object")
    private val OFFSET by relltok("offset")
    private val OPERATION by relltok("operation")
    private val OVERRIDE by relltok(S_Keywords.OVERRIDE)
    private val QUERY by relltok("query")
    private val RECORD by relltok("record")
    private val RETURN by relltok("return")
    private val STRUCT by relltok("struct")
    private val TRUE by relltok("true")
    private val UPDATE by relltok("update")
    private val VAL by relltok("val")
    private val VAR by relltok("var")
    private val VIRTUAL by relltok("virtual")
    private val WHEN by relltok("when")
    private val WHILE by relltok("while")

    private val NUMBER by relltok(RellTokenizer.INTEGER) // Must be exactly INT for Eclipse coloring, but then Xtext assumes it's a decimal Integer
    private val BIG_INTEGER by relltok(RellTokenizer.BIG_INTEGER)
    private val DECIMAL by relltok(RellTokenizer.DECIMAL)
    private val BYTES by relltok(RellTokenizer.BYTEARRAY)
    private val STRING by relltok(RellTokenizer.STRING) // Must be exactly STRING for Eclipse coloring
    private val ID by relltok(RellTokenizer.IDENTIFIER)

    override val tokenizer: RellTokenizer by lazy { RellTokenizer(rellTokens) }

    private val name by (ID) map { S_Name(it.pos, RellTokenizer.decodeName(it.pos, it.text)) }

    private val qualifiedName by separatedTerms(name, DOT, false) map { S_QualifiedName(it) }

    private val typeRef by parser(this::type)
    private val expressionRef by parser(this::expression)
    private val statementRef by parser(this::statement)

    private val nameType by qualifiedName map { S_NameType(it) }

    private val tupleTypeField by ( optional(name * -COLON) * typeRef ) map { (name, type) -> S_NameOptValue(name, type) }

    private val tupleTypeTail by -COMMA * separatedTerms(tupleTypeField, COMMA, true)

    private val tupleType by ( LPAR * tupleTypeField * optional(tupleTypeTail) * -RPAR) map {
        (pos, head, tail) ->
        if (tail == null && head.name == null) {
            head.value
        } else {
            val fields = listOf(head) + (tail ?: listOf())
            S_TupleType(pos.pos, fields)
        }
    }

    private val genericType by ( qualifiedName * -LT * separatedTerms(typeRef, COMMA, false) * -GT ) map {
        (name, args) ->
        S_GenericType(name, args)
    }

    private val virtualType by ( VIRTUAL * -LT * typeRef * -GT ) map { (kw, type) -> S_VirtualType(kw.pos, type) }

    private val mirrorStructType0 by STRUCT * -LT * optional(MUTABLE) * typeRef * -GT

    private val mirrorStructType by mirrorStructType0 map {
        (kw, mutable, paramType) ->
        S_MirrorStructType(kw.pos, mutable != null, paramType)
    }

    private val primaryType by (
            genericType
            or nameType
            or tupleType
            or virtualType
            or mirrorStructType
    )

    private val basicType by primaryType * zeroOrMore(QUESTION) map { (base, nulls) ->
        var res = base
        for (n in nulls) res = S_NullableType(n.pos, res)
        res
    }

    private val complexNullableType by LPAR * typeRef * -RPAR * -QUESTION map {
        (pos, type) ->
        S_NullableType(pos.pos, type)
    }

    private val functionType by LPAR * separatedTerms(typeRef, COMMA, true) * -RPAR * -ARROW * typeRef map {
        (pos, params, res) ->
        S_FunctionType(pos.pos, params, res)
    }

    private val type: Parser<S_Type> by (
            complexNullableType
            or functionType
            or basicType
    )

    private val annotationArgValue by parser(S_Grammar::literalExpr) map {
        S_AnnotationArg_Value(it)
    }

    private val annotationArgName by qualifiedName map {
        S_AnnotationArg_Name(it)
    }

    private val annotationArg by annotationArgValue or annotationArgName
    private val annotationArgs by ( -LPAR * separatedTerms(annotationArg, COMMA, true ) * -RPAR)

    private val annotation by ( -AT * name * optional(annotationArgs) ) map {
        (name, args) ->
        S_Annotation(name, args ?: immListOf())
    }

    private val keywordModifier by (
            ( ABSTRACT map { S_KeywordModifier(C_Name.make(it.pos, it.text), S_KeywordModifierKind.ABSTRACT) } )
            or ( OVERRIDE map { S_KeywordModifier(C_Name.make(it.pos, it.text), S_KeywordModifierKind.OVERRIDE) } )
    )

    private val modifier: Parser<S_Modifier> by keywordModifier or annotation

    private val nameTypeAttrHeader by name * -COLON * type map { (name, type) -> S_NamedAttrHeader(name, type) }

    private val anonAttrHeader by qualifiedName * optional(QUESTION) map {
        (name, nullable) ->
        S_AnonAttrHeader(name, nullable != null)
    }

    private val attrHeader by ( nameTypeAttrHeader or anonAttrHeader )

    private val baseAttributeDefinition by optional(MUTABLE) * attrHeader * optional(-ASSIGN * expressionRef) map {
        (mutable, header, expr) ->
        S_AttributeDefinition(mutable?.pos, header, expr)
    }

    private val attributeDefinition by baseAttributeDefinition * -SEMI

    private val relAttributeClause by attributeDefinition map {
        S_AttributeClause(it)
    }

    private val keyIndexKind by (
            ( KEY mapNode { R_KeyIndexKind.KEY } )
            or ( INDEX mapNode { R_KeyIndexKind.INDEX } )
    )

    private val relKeyIndexClause by ( keyIndexKind * separatedTerms(baseAttributeDefinition, COMMA, false) * -SEMI ) map {
        (kind, attrs) ->
        S_KeyIndexClause(kind.pos, kind.value, attrs)
    }

    private val relAnyClause by relAttributeClause or relKeyIndexClause

    private val entityAnnotations by -LPAR * separatedTerms(name, COMMA, false) * -RPAR

    private val entityBodyFull by ( -LCURL * zeroOrMore(relAnyClause) * -RCURL )
    private val entityBodyShort by (SEMI) map { null }
    private val entityBody by ( entityBodyFull or entityBodyShort )

    private val entityKeyword by (ENTITY map { it.pos to false }) or (CLASS map { it.pos to true })

    private val entityDef by ( entityKeyword * name * optional(entityAnnotations) * optional(entityBody) ) map {
        (posDeprecated, name, annotations2, clauses) ->
        val (pos, deprecated) = posDeprecated
        val deprecatedKwPos = if (deprecated) pos else null
        AnnotatedDef {
            S_EntityDefinition(pos, it, deprecatedKwPos, name, annotations2 ?: listOf(), clauses)
        }
    }

    private val objectDef by ( OBJECT * name * -LCURL * zeroOrMore(attributeDefinition) * -RCURL ) map {
        (kw, name, attrs) ->
        AnnotatedDef { S_ObjectDefinition(kw.pos, it, name, attrs) }
    }

    private val structKeyword by (STRUCT map { it.pos to false }) or (RECORD map { it.pos to true })

    private val structDef by ( structKeyword * name * -LCURL * zeroOrMore(attributeDefinition) * -RCURL ) map {
        (posDeprecated, name, attrs) ->
        val (pos, deprecated) = posDeprecated
        val deprecatedKwPos = if (deprecated) pos else null
        AnnotatedDef { S_StructDefinition(pos, it, deprecatedKwPos, name, attrs) }
    }

    private val enumDef by ( ENUM * name * -LCURL * separatedTerms(name, COMMA, true) * optional(COMMA) * -RCURL ) map {
        (kw, name, values) ->
        AnnotatedDef { S_EnumDefinition(kw.pos, it, name, values) }
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
            or ( -NOT * IN mapNode { S_BinaryOp.NOT_IN } )
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

    private val nameExpr by name map { S_NameExpr(S_QualifiedName(it)) }
    private val dollarExpr by DOLLAR map { S_DollarExpr(it.pos) }
    private val attrExpr by ( DOT * name) map { (pos, name) -> S_AttrExpr(pos.pos, name) }

    private val intExpr by NUMBER map { S_IntegerLiteralExpr(it.pos, RellTokenizer.decodeInteger(it.pos, it.text)) }

    private val bigIntExpr by BIG_INTEGER map { S_CommonLiteralExpr(it.pos, RellTokenizer.decodeBigInteger(it.pos, it.text)) }

    private val decimalExpr by DECIMAL map { S_CommonLiteralExpr(it.pos, RellTokenizer.decodeDecimal(it.pos, it.text)) }

    private val stringExpr = STRING map { S_StringLiteralExpr(it.pos, RellTokenizer.decodeString(it.pos, it.text)) }

    private val bytesExpr by BYTES map { S_ByteArrayLiteralExpr(it.pos, RellTokenizer.decodeByteArray(it.pos, it.text)) }

    private val booleanLiteralExpr by
            ( FALSE map { S_BooleanLiteralExpr(it.pos, false) } ) or
            ( TRUE map { S_BooleanLiteralExpr(it.pos, true) } )

    private val nullLiteralExpr by NULL map { S_NullLiteralExpr(it.pos) }

    private val literalExpr by intExpr or bigIntExpr or decimalExpr or stringExpr or bytesExpr or booleanLiteralExpr or nullLiteralExpr

    private val tupleExprFieldNameEqExpr by ( name * ASSIGN * expressionRef ) map {
        ( name, pos, expr ) -> S_TupleExprField_NameEqExpr(name, pos.pos, expr)
    }

    private val tupleExprFieldNameColonExpr by ( name * COLON * expressionRef ) map {
        ( name, pos, expr ) -> S_TupleExprField_NameColonExpr(name, pos.pos, expr)
    }

    private val tupleExprFieldExpr by ( expressionRef ) map { expr -> S_TupleExprField_Expr(expr) }

    private val tupleExprField by ( tupleExprFieldNameEqExpr or tupleExprFieldNameColonExpr or tupleExprFieldExpr )

    private val tupleExprTail by ( -COMMA * separatedTerms(tupleExprField, COMMA, true) )

    private val parenthesesExpr by ( LPAR * tupleExprField * optional(tupleExprTail) * -RPAR) map {
        (pos, field, tail) ->
        if (tail == null && field is S_TupleExprField_Expr) {
            S_ParenthesesExpr(pos.pos, field.expr)
        } else {
            val fields = listOf(field) + (tail ?: listOf())
            S_TupleExpr(pos.pos, fields)
        }
    }

    private val atExprFromSingle by qualifiedName map { S_PosValue(it.pos, listOf(S_AtExprFrom(null, it))) }

    private val atExprFromItem by ( optional( name * -COLON) * qualifiedName) map {
        ( alias, entityName ) ->
        S_AtExprFrom(alias, entityName)
    }

    private val atExprFromMulti by ( LPAR * separatedTerms(atExprFromItem, COMMA, false ) * -RPAR) map {
        ( pos, items ) ->
        S_PosValue(pos, items)
    }

    private val atExprFrom by ( atExprFromSingle or atExprFromMulti)

    private val atExprAt by (
            ( AT * QUESTION map { S_PosValue(it.t1.pos, R_AtCardinality.ZERO_ONE) } )
            or ( AT * MUL map { S_PosValue(it.t1.pos, R_AtCardinality.ZERO_MANY) } )
            or ( AT * PLUS map { S_PosValue(it.t1.pos, R_AtCardinality.ONE_MANY) } )
            or ( AT map { S_PosValue(it.pos, R_AtCardinality.ONE) } )
    )

    private val atExprWhatSimple by oneOrMore((-DOT * name)) map { path -> S_AtExprWhat_Simple(path) }

    private val atExprWhatName by ( name * -ASSIGN)

    private val atExprWhatComplexItem by ( zeroOrMore(annotation) * optional(atExprWhatName) * expressionRef) map {
        (annotations, name, expr) ->
        val modifiers = S_Modifiers(annotations.map { it })
        S_AtExprWhatComplexField(name, expr, modifiers)
    }

    private val atExprWhatComplex by ( -LPAR * separatedTerms(atExprWhatComplexItem, COMMA, false) * -RPAR) map {
        exprs ->
        S_AtExprWhat_Complex(exprs)
    }

    private val atExprWhat by ( atExprWhatSimple or atExprWhatComplex)

    private val atExprWhere by ( -LCURL * separatedTerms(expressionRef, COMMA, true) * -RCURL) map {
        exprs ->
        S_AtExprWhere(exprs)
    }

    private val atExprLimit by ( -LIMIT * expressionRef )
    private val atExprOffset by ( -OFFSET * expressionRef )

    private val atExprModifiers by (
            ((atExprLimit * optional(atExprOffset)) map { (limit, offset) -> AtExprMods(limit, offset) })
            or ((atExprOffset * optional(atExprLimit)) map { (offset, limit) -> AtExprMods(limit, offset) })
    )

    private val listLiteralExpr by ( LBRACK * separatedTerms(expressionRef, COMMA, true) * -RBRACK) map {
        ( pos, exprs ) ->
        S_ListLiteralExpr(pos.pos, exprs)
    }

    private val mapLiteralExprEntry by ( expressionRef * -COLON * expressionRef) map { (key, value) -> Pair(key, value) }
    private val emptyMapLiteralExpr by ( LBRACK * -COLON * -RBRACK ) map { pos -> S_MapLiteralExpr(pos.pos, listOf()) }
    private val nonEmptyMapLiteralExpr by ( LBRACK * separatedTerms(mapLiteralExprEntry, COMMA, false) * -RBRACK) map {
        ( pos, entries ) ->
        S_MapLiteralExpr(pos.pos, entries)
    }
    private val mapLiteralExpr by emptyMapLiteralExpr or nonEmptyMapLiteralExpr

    private val mirrorStructExpr by mirrorStructType0 map {
        (kw, mutable, type) ->
        S_MirrorStructExpr(kw.pos, mutable != null, type)
    }
    private val callArgValue by (
            ( MUL map { S_CallArgumentValue_Wildcard(it.pos) } )
            or ( expressionRef map { S_CallArgumentValue_Expr(it) } )
    )

    private val createExprArg by ( optional(-optional(DOT) * name * -ASSIGN) * callArgValue) map {
        (name, value) ->
        S_CallArgument(name, value)
    }

    private val createExprArgs by ( -LPAR * separatedTerms(createExprArg, COMMA, true) * -RPAR)

    private val createExpr by (CREATE * qualifiedName * createExprArgs) map {
        (kw, entityName, args) ->
        S_CreateExpr(kw.pos, entityName, args)
    }

    private val virtualTypeExpr by virtualType map { S_TypeExpr(it) }

    private val callArg by ( optional(name * -ASSIGN) * callArgValue) map {
        (name, value) ->
        S_CallArgument(name, value)
    }

    private val callArgs by ( -LPAR * separatedTerms(callArg, COMMA, true) * -RPAR)

    private val baseExprTailMember by ( -DOT * name) map { name -> G_BaseExprTail_Member(name) }
    private val baseExprTailSubscript by ( LBRACK * expressionRef * -RBRACK) map { (pos, expr) -> G_BaseExprTail_Subscript(pos.pos, expr) }
    private val baseExprTailNotNull by (NOTNULL) map { G_BaseExprTail_NotNull(it.pos) }
    private val baseExprTailSafeMember by ( -SAFECALL * name) map { name -> G_BaseExprTail_SafeMember(name) }
    private val baseExprTailUnaryPostfixOp by unaryPostfixOperator map { G_BaseExprTail_UnaryPostfixOp(it.pos, it.value) }

    private val baseExprTailCall by callArgs map { args ->
        G_BaseExprTail_Call(args)
    }

    private val baseExprTailAt by ( atExprAt * atExprWhere * optional(atExprWhat) * optional(atExprModifiers) ) map {
        ( cardinality, where, whatOpt, mods ) ->
        val what = whatOpt ?: S_AtExprWhat_Default()
        G_BaseExprTail_At(cardinality.pos, cardinality.value, where, what, mods?.limit, mods?.offset)
    }

    private val baseExprTailNoCallNoAt by (
            baseExprTailMember
            or baseExprTailSubscript
            or baseExprTailNotNull
            or baseExprTailSafeMember
            or baseExprTailUnaryPostfixOp
    )

    private val baseExprTail by ( baseExprTailNoCallNoAt or baseExprTailCall or baseExprTailAt )

    private val genericTypeExpr by ( genericType * ( baseExprTailMember or baseExprTailCall ) ) map {
        (type, tail) ->
        val baseExpr = S_TypeExpr(type)
        tail.toExpr(baseExpr)
    }

    private val baseExprHead by (
            genericTypeExpr
            or nameExpr
            or dollarExpr
            or attrExpr
            or literalExpr
            or parenthesesExpr
            or createExpr
            or listLiteralExpr
            or mapLiteralExpr
            or mirrorStructExpr
            or virtualTypeExpr
    )

    private val baseExpr: Parser<S_Expr> by ( baseExprHead * zeroOrMore(baseExprTail) ) map {
        ( head, tails ) ->
        G_BaseExprTail.tailsToExpr(head, tails)
    }

    private val baseExprNoCallNoAt by ( baseExprHead * zeroOrMore(baseExprTailNoCallNoAt) ) map {
        ( head, tails ) ->
        G_BaseExprTail.tailsToExpr(head, tails)
    }

    private val ifExpr by ( IF * -LPAR * expressionRef * -RPAR * expressionRef * -ELSE * expressionRef) map {
        ( pos, cond, trueExpr, falseExpr ) ->
        S_IfExpr(pos.pos, cond, trueExpr, falseExpr)
    }

    private val whenConditionExpr by separatedTerms(expressionRef, COMMA, false) map { exprs -> S_WhenConditionExpr(exprs) }
    private val whenConditionElse by ELSE map { S_WhenCondtiionElse(it.pos) }
    private val whenCondition by whenConditionExpr or whenConditionElse

    private val whenExprCase by whenCondition * -ARROW * expressionRef map {
        (cond, expr) ->
        S_WhenExprCase(cond, expr)
    }

    private val whenExprCases by separatedTerms(whenExprCase, oneOrMore(SEMI), false) * zeroOrMore(SEMI) map {
        (cases, _) -> cases
    }

    private val whenExpr by WHEN * optional(-LPAR * expressionRef * -RPAR) * -LCURL * whenExprCases * -RCURL map {
        (pos, expr, cases) ->
        S_WhenExpr(pos.pos, expr, cases)
    }

    private val operandExpr: Parser<S_Expr> by ( baseExpr or ifExpr or whenExpr )

    private val unaryExpr by ( zeroOrMore(unaryPrefixOperator) * operandExpr) map { (ops, expr) ->
        var res = expr
        for (op in ops.reversed()) {
            res = S_UnaryExpr(op.pos, S_PosValue(op.pos, op.value), res)
        }
        res
    }

    private val binaryExprOperand by ( binaryOperator * unaryExpr) map { ( op, expr ) -> S_BinaryExprTail(op, expr) }

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
        (pos, decls) ->
        S_TupleVarDeclarator(pos.pos, decls)
    }

    private val varDeclarator: Parser<S_VarDeclarator> by simpleVarDeclarator or tupleVarDeclarator

    private val varStmt by ( varVal * varDeclarator * optional(-ASSIGN * expression) * -SEMI) map {
        (mutable, declarator, expr) ->
        S_VarStatement(mutable.pos, declarator, expr, mutable.value)
    }

    private val returnStmt by ( RETURN * optional(expression) * -SEMI) map { ( kw, expr ) ->
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

    private val assignStmt by ( baseExpr * assignOp * expression * -SEMI) map {
        (expr1, op, expr2) ->
        S_AssignStatement(expr1, op, expr2)
    }

    private val incrementStmt by ( incrementOperator * baseExpr * -SEMI) map {
        (op, expr) ->
        S_ExprStatement(S_UnaryExpr(op.pos, S_PosValue(op.pos, S_UnaryOp_IncDec(op.value, false)), expr))
    }

    private val blockStmt by ( LCURL * zeroOrMore(statementRef) * -RCURL) map {
        (pos, statements) ->
        S_BlockStatement(pos.pos, statements)
    }

    private val ifStmt by ( IF * -LPAR * expression * -RPAR * statementRef * optional(-ELSE * statementRef) ) map {
        (pos, expr, trueStmt, falseStmt) ->
        S_IfStatement(pos.pos, expr, trueStmt, falseStmt)
    }

    private val whenStmtCase by whenCondition * -ARROW * statementRef * zeroOrMore(SEMI) map {
        (cond, stmt) ->
        S_WhenStatementCase(cond, stmt)
    }

    private val whenStmt by WHEN * optional(-LPAR * expressionRef * -RPAR) * -LCURL * zeroOrMore(whenStmtCase) * -RCURL map {
        (pos, expr, cases) ->
        S_WhenStatement(pos.pos, expr, cases)
    }

    private val whileStmt by ( WHILE * -LPAR * expression * -RPAR * statementRef) map {
        (pos, expr, stmt) ->
        S_WhileStatement(pos.pos, expr, stmt)
    }

    private val forStmt by ( FOR * -LPAR * varDeclarator * -IN * expression * -RPAR * statementRef) map {
        (pos, declarator, expr, stmt) ->
        S_ForStatement(pos.pos, declarator, expr, stmt)
    }

    private val breakStmt by ( BREAK * -SEMI) map { S_BreakStatement(it.pos) }
    private val continueStmt by ( CONTINUE * -SEMI) map { S_ContinueStatement(it.pos) }

    private val callStmt by ( baseExpr * -SEMI) map { expr -> S_ExprStatement(expr) }

    private val createStmt by ( createExpr * -SEMI) map { expr -> S_ExprStatement(expr) }

    private val updateTargetAt by ( atExprFrom * atExprAt * atExprWhere) map {
        (from, cardinality, where) ->
        S_UpdateTarget_Simple(cardinality.value, from.value, where)
    }

    private val updateTargetExpr by baseExprNoCallNoAt map { expr -> S_UpdateTarget_Expr(expr) }

    private val updateTarget by ( updateTargetAt or updateTargetExpr)

    private val updateWhatNameOp by ( -optional(DOT) * name * assignOp) map { (name, op) -> Pair(name, op) }

    private val updateWhatExpr by ( optional(updateWhatNameOp) * expression) map {
        (nameOp, expr) ->
        if (nameOp == null) {
            S_UpdateWhat(expr.startPos, null, null, expr)
        } else {
            val (name, op) = nameOp
            S_UpdateWhat(name.pos, name, op.value, expr)
        }
    }

    private val updateWhat by ( -LPAR * separatedTerms(updateWhatExpr, COMMA, true) * -RPAR )

    private val updateStmt by ( UPDATE * updateTarget * updateWhat * -SEMI ) map {
        (kw, target, what) ->
        S_UpdateStatement(kw.pos, target, what)
    }

    private val deleteStmt by ( DELETE * updateTarget * -SEMI ) map {
        (kw, target) ->
        S_DeleteStatement(kw.pos, target)
    }

    private val guardStmt by ( GUARD * blockStmt ) map {
        (kw, stmt) -> S_GuardStatement(kw.pos, stmt)
    }

    private val statementNoExpr by (
            emptyStmt
            or varStmt
            or assignStmt
            or returnStmt
            or blockStmt
            or ifStmt
            or whenStmt
            or whileStmt
            or forStmt
            or breakStmt
            or continueStmt
            or updateStmt
            or deleteStmt
    )

    private val statement: Parser<S_Statement> by (
            statementNoExpr
            or incrementStmt
            or callStmt
            or createStmt
            or guardStmt
    )

    private val formalParameter by ( attrHeader * optional(-ASSIGN * expression) ) map {
        (attr, expr) -> S_FormalParameter(attr, expr)
    }

    private val formalParameters by ( -LPAR * separatedTerms(formalParameter, COMMA, true) * -RPAR)

    private val opDef by ( OPERATION * name * formalParameters * blockStmt ) map {
        (kw, name, params, body) -> AnnotatedDef { S_OperationDefinition(kw.pos, it, name, params, body) }
    }

    private val functionBodyShort by (-ASSIGN * expression * -SEMI) map { S_FunctionBodyShort(it) }
    private val functionBodyFull by blockStmt map { stmt -> S_FunctionBodyFull(stmt) }
    private val functionBodyNone by SEMI map { null }
    private val functionBody by ( functionBodyShort or functionBodyFull or functionBodyNone)

    private val queryBody by ( functionBodyShort or functionBodyFull)

    private val queryDef by ( QUERY * name * formalParameters * optional(-COLON * type) * queryBody ) map {
        (kw, name, params, type, body) ->
        AnnotatedDef { S_QueryDefinition(kw.pos, it, name, params, type, body) }
    }

    private val functionDef by ( FUNCTION * optional(qualifiedName) * formalParameters * optional(-COLON * type) * functionBody ) map {
        (kw, name, params, type, body) ->
        AnnotatedDef { S_FunctionDefinition(kw.pos, it, name, params, type, body) }
    }

    private val namespaceDef by ( NAMESPACE * optional(qualifiedName) * -LCURL * zeroOrMore(parser(this::annotatedDef)) * -RCURL ) map {
        (kw, name, defs) ->
        AnnotatedDef { S_NamespaceDefinition(kw.pos, it, name, defs) }
    }

    private val absoluteImportModule by qualifiedName map { S_ImportModulePath(null, it) }

    private val relativeImportModule by DOT * optional(qualifiedName) map {
        (pos, moduleName) ->
        S_ImportModulePath(S_RelativeImportModulePath(pos.pos, 0), moduleName)
    }

    private val upImportModule by oneOrMore(CARET) * optional(-DOT * qualifiedName) map {
        (carets, moduleName) ->
        S_ImportModulePath(S_RelativeImportModulePath(carets[0].pos, carets.size), moduleName)
    }

    private val importModule by absoluteImportModule or relativeImportModule or upImportModule

    private val importTargetExactItem by optional(name * -COLON) * qualifiedName * optional(-DOT * MUL) map {
        (alias, name, wildcard) -> S_ExactImportTargetItem(alias, name, wildcard != null)
    }
    private val importTargetExact by -LCURL * separatedTerms(importTargetExactItem, COMMA, true) * -RCURL map {
        items -> S_ExactImportTarget(items)
    }
    private val importTargetWildcard by MUL map { S_WildcardImportTarget() }
    private val importTarget by -DOT * (importTargetExact or importTargetWildcard)

    private val importDef by ( IMPORT * optional( name * -COLON) * importModule * optional(importTarget) * -SEMI ) map {
        (kw, alias, module, target) ->
        AnnotatedDef { S_ImportDefinition(kw.pos, it, alias, module, target ?: S_DefaultImportTarget()) }
    }

    private val includeDef by ( INCLUDE * STRING * -SEMI) map {
        (kw, _) ->
        AnnotatedDef { S_IncludeDefinition(kw.pos) }
    }

    private val constantDef by ( VAL * name * optional(-COLON * typeRef) * -ASSIGN * expressionRef * -SEMI ) map {
        (kw, name, type, expr) ->
        AnnotatedDef { S_GlobalConstantDefinition(kw.pos, it, name, type, expr) }
    }

    private val replDef: Parser<AnnotatedDef> by (
            entityDef
            or objectDef
            or structDef
            or enumDef
            or functionDef
            or namespaceDef
            or importDef
            or opDef
            or queryDef
            or includeDef
    )

    private val anyDef: Parser<AnnotatedDef> by (
            replDef
            or constantDef
    )

    private val annotatedDef by zeroOrMore(modifier) * anyDef map {
        (modifiers, def) -> def.createDef(modifiers)
    }

    private val moduleHeader by zeroOrMore(modifier) * MODULE * -SEMI map {
        (modifiers, kw) -> S_ModuleHeader(S_Modifiers(modifiers), kw.pos)
    }

    private val defReplStep by zeroOrMore(modifier) * replDef map {
        (modifiers, def) ->
        S_DefinitionReplStep(def.createDef(modifiers))
    }

    private val replExprStatement by expression * -SEMI map {
        expr -> S_ExprStatement(expr)
    }

    private val stmtReplStep by statementNoExpr or replExprStatement map {
        stmt -> S_StatementReplStep(stmt)
    }

    private val replStep by defReplStep or stmtReplStep

    private val replCommand by zeroOrMore(replStep) * optional(expression) map {
        (steps, expr) -> S_ReplCommand(steps, expr)
    }

    val replParser: Parser<S_ReplCommand> by replCommand

    override val rootParser by optional(moduleHeader) * zeroOrMore(annotatedDef) map {
        (header, defs) ->
        S_RellFile(header, defs)
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

private class AnnotatedDef(private val creator: (S_Modifiers) -> S_Definition) {
    fun createDef(modifiers: List<S_Modifier>) = creator(S_Modifiers(modifiers))
}

private class AtExprMods(val limit: S_Expr?, val offset: S_Expr?)

private infix fun <T> Parser<RellTokenMatch>.mapNode(transform: (RellTokenMatch) -> T): Parser<S_PosValue<T>> = MapCombinator(this) {
    S_PosValue(it, transform(it))
}
