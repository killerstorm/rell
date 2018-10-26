package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.sql.gensql
import org.junit.Test

class SQLGenTest {

    fun testresource(f: String) {
        val contractText = javaClass.getResource(f).readText()
        val ast = S_Grammar.parseToEnd(contractText)
        val model = ast.compile()
        println(gensql(model, true))
    }


    //@Test
    fun contract03() { testresource("contract03.rell")   }

    //@Test
    fun contract01() { testresource("contract01.rell")   }

    //@Test
    fun jsoncontract() { testresource("json.rell")   }
}
