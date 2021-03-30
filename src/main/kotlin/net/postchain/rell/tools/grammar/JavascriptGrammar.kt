/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.grammar

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.ParserReference
import net.postchain.rell.compiler.parser.RellToken
import net.postchain.rell.compiler.parser.S_Grammar
import net.postchain.rell.module.RellVersions
import org.apache.commons.collections4.MapUtils

fun main(args: Array<String>) {
    val timestamp = System.currentTimeMillis()

    println("var RELL_GRAMMAR = {")

    val tokenizer = S_Grammar.tokenizer

    println("    \"version\": \"${RellVersions.VERSION_STR}\",")
    println("    \"timestamp\": \"$timestamp\",")
    println("    \"timestampStr\": \"${GrammarUtils.timestampToString(timestamp)}\",")
    println("    \"lex\": {")
    println("        \"generals\": {")
    println("            \"IDENTIFIER\": \"${tokenizer.tkIdentifier.name}\",")
    println("            \"INTEGER\": \"${tokenizer.tkInteger.name}\",")
    println("            \"DECIMAL\": \"${tokenizer.tkDecimal.name}\",")
    println("            \"STRING\": \"${tokenizer.tkString.name}\",")
    println("            \"BYTE_ARRAY\": \"${tokenizer.tkByteArray.name}\",")
    println("        },")
    println("        \"keywords\": {")
    for (t in tokenizer.tkKeywords.values.sortedBy { it.token.pattern }) {
        println("            \"${t.token.pattern}\" : \"${t.name}\",")
    }
    println("        },")
    println("        \"delims\": {")
    for (t in tokenizer.tkDelims.sortedBy { it.token.pattern }) {
        println("            \"${t.token.pattern}\" : \"${t.name}\",")
    }
    println("        },")
    println("    },")

    val nameToParser = GrammarUtils.getParsers()
    val parserToName = MapUtils.invertMap(nameToParser)

    println("    \"syntax\": {")
    for (name in nameToParser.keys.sorted()) {
        val parser = nameToParser.getValue(name)
        val js = parserToJavascript(parserToName, parser, true)
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
        is RellToken -> "\"${parser.name}\""
        is MapCombinator<*, *> -> parserToJavascript(nameMap, parser.innerParser, false)
        is SkipParser -> parserToJavascript(nameMap, parser.innerParser, false)
        is AndCombinator<*> -> {
            var subs = GrammarUtils.andParsers(parser)
            val ps = subs.joinToString(",") { parserToJavascript(nameMap, it, false) }
            """{"type":"and",parsers:[$ps]}"""
        }
        is OrCombinator<*> -> {
            var subs = GrammarUtils.orParsers(parser)
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
