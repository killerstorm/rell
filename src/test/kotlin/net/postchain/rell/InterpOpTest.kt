package net.postchain.rell

import net.postchain.rell.model.RInstanceRefType
import net.postchain.rell.model.RModule
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.NullSqlExecutor
import kotlin.test.assertEquals

class InterpOpTest: AbstractOpTest() {
    override fun checkOpBool(op: String, left: TstVal, right: TstVal, expected: Boolean) {
        checkOp(op, left, right, "boolean[$expected]")
    }

    override fun checkOpBool(op: String, right: TstVal, expected: Boolean) {
        checkOp(op, right, "boolean[$expected]")
    }

    override fun checkOp(op: String, left: TstVal, right: TstVal, expected: String) {
        left as InterpTstVal
        right as InterpTstVal
        val actual = calcOp(op, left.type, right.type, left, right)
        assertEquals(expected, actual)
    }

    override fun checkOp(op: String, right: TstVal, expected: String) {
        right as InterpTstVal
        val actual = calcOp(op, right.type, right)
        assertEquals(expected, actual)
    }

    override fun checkOpErr(op: String, left: String, right: String) {
        val expected = "ct_err:binop_operand_type:$op:$left:$right"
        val actual = calcOp(op, left, right, InterpTstVal.Bool(false), InterpTstVal.Bool(false))
        assertEquals(expected, actual)
    }

    override fun checkOpErr(op: String, right: String) {
        val expected = "ct_err:unop_operand_type:$op:$right"
        val actual = calcOp(op, right, InterpTstVal.Bool(false))
        assertEquals(expected, actual)
    }

    private fun calcOp(op: String, leftType: String, rightType: String, leftVal: InterpTstVal, rightVal: InterpTstVal): String {
        return calcOp0("a $op b", leftType, rightType, leftVal, rightVal)
    }

    private fun calcOp(op: String, rightType: String, rightVal: InterpTstVal): String {
        return calcOp0("$op b", "boolean", rightType, InterpTstVal.Bool(false), rightVal)
    }

    private fun calcOp0(expr: String, leftType: String, rightType: String, leftVal: InterpTstVal, rightVal: InterpTstVal): String {
        val code = """
            class company { name: text; }
            class user { name: text; company; }
            query q(a: $leftType, b: $rightType) = $expr;
        """.trimIndent()

        val res = TestUtils.processModule(code) { module ->
            val rtLeft = leftVal.rt(module)
            val rtRight = rightVal.rt(module)
            TestUtils.execute(module, NullSqlExecutor, arrayOf(rtLeft, rtRight))
        }

        return res
    }

    override fun vBool(v: Boolean): TstVal = InterpTstVal.Bool(v)
    override fun vInt(v: Long): TstVal = InterpTstVal.Integer(v)
    override fun vText(v: String): TstVal = InterpTstVal.Text(v)
    override fun vObj(cls: String, id: Long): TstVal = InterpTstVal.Obj(cls, id)

    private sealed class InterpTstVal(val type: String): TstVal() {
        abstract fun rt(m: RModule): RtValue

        class Bool(val v: Boolean): InterpTstVal("boolean") {
            override fun rt(m: RModule): RtValue = RtBooleanValue(v)
        }

        class Integer(val v: Long): InterpTstVal("integer") {
            override fun rt(m: RModule): RtValue = RtIntValue(v)
        }

        class Text(val v: String): InterpTstVal("text") {
            override fun rt(m: RModule): RtValue = RtTextValue(v)
        }

        class Obj(val cls: String, val id: Long): InterpTstVal(cls) {
            override fun rt(m: RModule): RtValue {
                val c = m.classes.find { it.name == cls }
                val t = RInstanceRefType(c!!)
                return RtObjectValue(t, id)
            }
        }
    }
}
