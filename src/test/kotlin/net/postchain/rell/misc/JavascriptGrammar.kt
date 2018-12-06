package net.postchain.rell.misc

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.ParserReference
import com.github.h0tk3y.betterParse.lexer.Token
import com.github.h0tk3y.betterParse.parser.Parser
import net.postchain.rell.parser.S_Grammar
import java.lang.IllegalStateException

import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

fun main(args: Array<String>) {
    println("var RELL_GRAMMAR = {")

    val tokenizer = S_Grammar.tokenizer

    println("    \"lex\": {")
    println("        \"generals\": {")
    println("            \"IDENTIFIER\": \"${tokenizer.tkIdentifier.name}\",")
    println("            \"INTEGER\": \"${tokenizer.tkInteger.name}\",")
    println("            \"STRING\": \"${tokenizer.tkString.name}\",")
    println("            \"BYTE_ARRAY\": \"${tokenizer.tkByteArray.name}\",")
    println("        },")
    println("        \"keywords\": {")
    for (t in tokenizer.tkKeywords.values.sortedBy { it.pattern }) {
        println("            \"${t.pattern}\" : \"${t.name}\",")
    }
    println("        },")
    println("        \"delims\": {")
    for (t in tokenizer.tkDelims.sortedBy { it.pattern }) {
        println("            \"${t.pattern}\" : \"${t.name}\",")
    }
    println("        },")
    println("    },")

    val parsers = mutableMapOf<Any, String>()

    for (p in S_Grammar::class.memberProperties) {
        p.isAccessible = true

        val v = try { p.getter.call(S_Grammar) }
        catch (e: IllegalArgumentException) {
            p.getter.call()
        }

        if (v is Token) {
            // ignore
        } else if (v is Parser<*>) {
            parsers[v] = p.name
        }
    }

    println("    \"syntax\": {")
    for ((parser, name) in parsers) {
        val js = parserToJavascript(parsers, parser, true)
        println("        \"$name\": $js,")
    }
    println("    },")
    println("};");
}

private fun parserToJavascript(nameMap: Map<Any, String>, parser: Any, top: Boolean): String {
    if (!top && parser in nameMap) {
        val name = nameMap.getValue(parser)
        return """"$name""""
    }

    return when (parser) {
        is Token -> "\"${parser.name}\""
        is MapCombinator<*, *> -> parserToJavascript(nameMap, parser.innerParser, false)
        is SkipParser -> parserToJavascript(nameMap, parser.innerParser, false)
        is AndCombinator<*> -> {
            var subs = subParsersAnd(parser)
            val ps = subs.joinToString(",") { parserToJavascript(nameMap, it, false) }
            """{"type":"and",parsers:[$ps]}"""
        }
        is OrCombinator<*> -> {
            var subs = subParsersOr(parser)
            val ps = subs.joinToString(",") { parserToJavascript(nameMap, it, false) }
            """{"type":"or",parsers:[$ps]}"""
        }
        is RepeatCombinator<*> -> {
            check(parser.atLeast >= 0)
            check(parser.atMost == -1)
            val zero = parser.atLeast == 0
            val sub = parserToJavascript(nameMap, parser.parser, false)
            """{"type":"rep","zero":$zero,"parser":$sub}"""
        }
        is SeparatedCombinator<*, *> -> {
            val term = parserToJavascript(nameMap, parser.termParser, false)
            val sep = parserToJavascript(nameMap, parser.separatorParser, false)
            """{"type":"sep","zero":${parser.acceptZero},"term":$term,"sep":$sep}"""
        }
        is OptionalCombinator<*> -> {
            val sub = parserToJavascript(nameMap, parser.parser, false)
            """{"type":"opt",parser:$sub}"""
        }
        is ParserReference<*> -> parserToJavascript(nameMap, parser.parser, false)
        else -> throw IllegalStateException(parser::class.java.simpleName)
    }
}

private fun subParsersAnd(p: Any): List<Any> {
    if (p is AndCombinator<*>) {
        return p.consumers.flatMap { subParsersAnd(it) }
    } else {
        return listOf(p)
    }
}

private fun subParsersOr(p: Any): List<Any> {
    if (p is OrCombinator<*>) {
        return p.parsers.flatMap { subParsersOr(it) }
    } else {
        return listOf(p)
    }
}
