package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import net.postchain.rell.model.*
import net.postchain.rell.parser.S_Grammar
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
        val model = ast.compile()
    }

    @Test
    fun closure () {
//        val lambda = RLambdaExpr(RClosureType("Hello?"), listOf(RAttrib("a", RIntegerType)),
//                RBinaryExpr(RIntegerType, "+",
//                        RVarExpr(RAttrib("a", RIntegerType), 0),
//                        RIntegerLiteralExpr(RIntegerType, 1)))
//        val funcall = RFuncall(RIntegerType,
//                lambda, listOf(RVarExpr(RAttrib("b", RIntegerType), 0)))
//        val condition = RBinaryExpr(RBooleanType, "==", funcall, RIntegerLiteralExpr(RIntegerType, 3))
//        val require = RCallStatement(RCallExpr(RUnitType, "require", listOf(condition)))
//        val op = ROperation("hello", arrayOf(RAttrib("b", RIntegerType)), arrayOf(require))
    }
}
