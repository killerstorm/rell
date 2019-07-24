package net.postchain.rell.tools.grammar

import com.github.h0tk3y.betterParse.combinators.AndCombinator
import com.github.h0tk3y.betterParse.combinators.OrCombinator
import com.github.h0tk3y.betterParse.parser.Parser
import net.postchain.rell.parser.RellToken
import net.postchain.rell.parser.S_Grammar
import org.apache.commons.lang3.time.FastDateFormat
import java.util.*
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

object GrammarUtils {
    fun getParsers(): Map<String, Any> {
        val parsers = mutableMapOf<String, Any>()

        for (p in S_Grammar::class.memberProperties) {
            p.isAccessible = true

            val v = try { p.getter.call(S_Grammar) }
            catch (e: IllegalArgumentException) {
                p.getter.call()
            }

            if (v is RellToken) {
                // ignore
            } else if (v is Parser<*>) {
                parsers[p.name] = v
            }
        }

        return parsers
    }

    fun andParsers(p: Any): List<Any> {
        if (p is AndCombinator<*>) {
            return p.consumers.flatMap { andParsers(it) }
        } else {
            return listOf(p)
        }
    }

    fun orParsers(p: Any): List<Any> {
        if (p is OrCombinator<*>) {
            return p.parsers.flatMap { orParsers(it) }
        } else {
            return listOf(p)
        }
    }

    fun timestampToString(timestamp: Long): String {
        val tz = TimeZone.getTimeZone("UTC")
        return FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ssZ", tz).format(timestamp)
    }
}
