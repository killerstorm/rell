/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.mtype

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.lexer.Tokenizer
import com.github.h0tk3y.betterParse.parser.Parser
import net.postchain.rell.base.utils.FixedDefaultTokenizer

abstract class M_TypeGrammar<T>: Grammar<T>() {
    final override val tokenizer: Tokenizer by lazy { FixedDefaultTokenizer(tokens) }

    @Suppress("unused")
    private val WHITESPACE by token("\\s+", ignore = true)

    // Some tokens aren't used in this class, but they are used by subclass(es), and they must be defined here,
    // because the order is important: "::" must be defined before ":".

    protected val LT by token("<")
    protected val GT by token(">")
    protected val COMMA by token(",")
    protected val COLON_COLON by token("::")
    protected val COLON by token(":")
    protected val DOT by token("\\.")
    protected val LPAREN by token("\\(")
    protected val RPAREN by token("\\)")
    protected val QUESTION by token("\\?")
    protected val ARROW by token("->")
    protected val PLUS by token("\\+")
    protected val MINUS by token("-")
    protected val ASTERISK by token("\\*")

    protected val NAME by token("[A-ZA-z_][A-Za-z0-9_]*")

    protected abstract val name: Parser<String>

    private val nameRef: Parser<String> by parser { name }

    private val typeRef: Parser<M_AstType> by parser { type0 }

    private val nameType by nameRef map { M_AstType_Name(it) }

    private val wildcardTypeSet by ASTERISK map { M_AstTypeSet_All }
    private val superTypeSet by -PLUS * typeRef map { type -> M_AstTypeSet_SuperOf(type) }
    private val subTypeSet by -MINUS * typeRef map { type -> M_AstTypeSet_SubOf(type) }
    private val simpleTypeSet by typeRef map { type -> M_AstTypeSet_One(type) }

    protected val typeSet0: Parser<M_AstTypeSet> by wildcardTypeSet or superTypeSet or subTypeSet or simpleTypeSet

    private val genericType by nameRef * -LT * separatedTerms(typeSet0, COMMA) * -GT map {
        (name, args) -> M_AstType_Generic(name, args)
    }

    private val functionType by -LPAREN * separatedTerms(typeRef, COMMA, true) * -RPAREN * -ARROW * typeRef map {
        (params, result) -> M_AstType_Function(result, params)
    }

    private val tupleTypeField by optional(nameRef * -COLON) * typeRef map { (name, type) -> name to type }

    private val tupleType by -LPAREN * separatedTerms(tupleTypeField, COMMA) * -RPAREN map {
        fields -> M_AstType_Tuple(fields)
    }

    private val baseType by genericType or nameType or tupleType

    private val nullableType by baseType * -QUESTION map { M_AstType_Nullable(it) }

    protected val type0: Parser<M_AstType> by
        nullableType or
        functionType or
        baseType
}
