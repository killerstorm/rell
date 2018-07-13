package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import org.junit.Test

class SQLGenTest {

    fun testresource(f: String) {
        val contractText = javaClass.getResource(f).readText()
        val ast = S_Grammar.parseToEnd(contractText)
        val model = makeModule(ast)
        println(gensql(model))
    }


    @Test
    fun contract01() { testresource("contract01.rell")   }

    @Test
    fun contract02() { testresource("contract02.rell")   }


    /*

    operation update_project_description(issuer_pubkey: signer, name, description) {
        update project@{ issuer@{pubkey=issuer_pubkey}, name = name}
        ( description );
    }
     */

}
