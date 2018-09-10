package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import net.postchain.rell.model.makeModule
import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.runtime.make_operation
import org.junit.Test

class InterpTest
{
    @Test
    fun yeah() {
        val contractText = """
            operation nemesis(a: integer, b: integer) {
                require( ((a + b) == 3) );
            }

            """
        val ast = S_Grammar.parseToEnd(contractText)
        val model = makeModule(ast)
        val opfun = make_operation(model.operations[0])
        opfun(arrayOf(1, 2));
    }

}