package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import net.postchain.rell.model.*
import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.runtime.RTGlobalContext
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
        val op = make_operation(model.operations[0])
        op.call(RTGlobalContext(null),
                arrayOf(1, 2));
    }


    @Test
    fun closure () {
        val lambda = RLambda(RClosureType("Hello?"), listOf(RAttrib("a", RIntegerType)),
                RBinOpExpr(RIntegerType, "+",
                        RVarRef(RIntegerType, RAttrib("a", RIntegerType)),
                        RIntegerLiteral(RIntegerType, 1)))
        val funcall = RFuncall(RIntegerType,
                lambda, listOf(RVarRef(RIntegerType, RAttrib("b", RIntegerType))))
        val condition = RBinOpExpr(RBooleanType, "==", funcall, RIntegerLiteral(RIntegerType, 3))
        val require = RCallStatement(RFunCallExpr(RUnitType, "require", listOf(condition)))
        val op = ROperation("hello", arrayOf(RAttrib("b", RIntegerType)), arrayOf(require))
        val opx = make_operation(op)
        opx.call(RTGlobalContext(null), arrayOf<Any?>(2))
    }
}