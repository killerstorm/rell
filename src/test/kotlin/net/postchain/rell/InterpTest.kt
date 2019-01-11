package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
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
        val model = ast.compile(false)
    }

    @Test
    fun closure () {
//        val lambda = RLambdaExpr(R_ClosureType("Hello?"), listOf(R_Attrib("a", R_IntegerType)),
//                R_BinaryExpr(R_IntegerType, "+",
//                        R_VarExpr(R_Attrib("a", R_IntegerType), 0),
//                        RIntegerLiteralExpr(R_IntegerType, 1)))
//        val funcall = RFuncall(R_IntegerType,
//                lambda, listOf(R_VarExpr(R_Attrib("b", R_IntegerType), 0)))
//        val condition = R_BinaryExpr(R_BooleanType, "==", funcall, RIntegerLiteralExpr(R_IntegerType, 3))
//        val require = RCallStatement(R_CallExpr(R_UnitType, "require", listOf(condition)))
//        val op = R_Operation("hello", arrayOf(R_Attrib("b", R_IntegerType)), arrayOf(require))
    }
}
