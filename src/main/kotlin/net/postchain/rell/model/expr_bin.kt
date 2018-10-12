package net.postchain.rell.model

import net.postchain.rell.runtime.*

sealed class RCmpOp(val code: String, val checker: (Int) -> Boolean) {
    fun check(cmp: Int): Boolean = checker(cmp)
}

object RCmpOp_Eq: RCmpOp("==", { it == 0 })
object RCmpOp_Ne: RCmpOp("!=", { it != 0 })
object RCmpOp_Lt: RCmpOp("<", { it < 0 })
object RCmpOp_Gt: RCmpOp(">", { it > 0 })
object RCmpOp_Le: RCmpOp("<=", { it <= 0 })
object RCmpOp_Ge: RCmpOp(">=", { it >= 0 })

sealed class RCmpType {
    abstract fun compare(left: RtValue, right: RtValue): Int
}

object RCmpType_Boolean: RCmpType() {
    override fun compare(left: RtValue, right: RtValue): Int {
        val l = left.asBoolean()
        val r = right.asBoolean()
        return l.compareTo(r)
    }
}

object RCmpType_Integer: RCmpType() {
    override fun compare(left: RtValue, right: RtValue): Int {
        val l = left.asInteger()
        val r = right.asInteger()
        return l.compareTo(r)
    }
}

object RCmpType_Text: RCmpType() {
    override fun compare(left: RtValue, right: RtValue): Int {
        val l = left.asString()
        val r = right.asString()
        return l.compareTo(r)
    }
}

object RCmpType_Object: RCmpType() {
    override fun compare(left: RtValue, right: RtValue): Int {
        val l = left.asObjectId()
        val r = right.asObjectId()
        return l.compareTo(r)
    }
}

sealed class RBinaryOp(val code: String) {
    open fun evaluate(left: RtValue): RtValue? = null
    abstract fun evaluate(left: RtValue, right: RtValue): RtValue

    fun evaluate(env: RtEnv, left: RExpr, right: RExpr): RtValue {
        val leftValue = left.evaluate(env)
        val scValue = evaluate(leftValue)
        if (scValue != null) {
            return scValue
        }

        val rightValue = right.evaluate(env)
        val resValue = evaluate(leftValue, rightValue)
        return resValue
    }
}

class RBinaryOp_Cmp(val cmpOp: RCmpOp, val cmpType: RCmpType): RBinaryOp(cmpOp.code) {
    override fun evaluate(left: RtValue, right: RtValue): RtValue {
        val cmp = cmpType.compare(left, right)
        val res = cmpOp.check(cmp)
        return RtBooleanValue(res)
    }
}

sealed class RBinaryOp_Logic(code: String): RBinaryOp(code) {
    abstract fun evaluate(left: Boolean): Boolean?
    abstract fun evaluate(left: Boolean, right: Boolean): Boolean

    override fun evaluate(left: RtValue): RtValue? {
        val lb = left.asBoolean()
        val res = evaluate(lb)
        return if (res == null) null else RtBooleanValue(res)
    }

    override fun evaluate(left: RtValue, right: RtValue): RtValue {
        val lb = left.asBoolean()
        val rb = right.asBoolean()
        val res = evaluate(lb, rb)
        return RtBooleanValue(res)
    }
}

object RBinaryOp_And: RBinaryOp_Logic("and") {
    override fun evaluate(left: Boolean): Boolean? {
        return if (!left) false else null
    }

    override fun evaluate(left: Boolean, right: Boolean): Boolean {
        return left && right
    }
}

object RBinaryOp_Or: RBinaryOp_Logic("or") {
    override fun evaluate(left: Boolean): Boolean? {
        return if (left) true else null
    }

    override fun evaluate(left: Boolean, right: Boolean): Boolean {
        return left || right
    }
}

class RBinaryExpr(type: RType, val op: RBinaryOp, val left: RExpr, val right: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val resValue = op.evaluate(env, left, right)
        return resValue
    }
}

sealed class RBinaryOp_Arith(code: String): RBinaryOp(code) {
    abstract fun evaluate(left: Long, right: Long): Long

    override final fun evaluate(left: RtValue, right: RtValue): RtValue {
        val leftVal = left.asInteger()
        val rightVal = right.asInteger()
        val resVal = evaluate(leftVal, rightVal)
        return RtIntValue(resVal)
    }
}

object RBinaryOp_Add: RBinaryOp_Arith("+") {
    override fun evaluate(left: Long, right: Long): Long = left + right
}

object RBinaryOp_Sub: RBinaryOp_Arith("-") {
    override fun evaluate(left: Long, right: Long): Long = left - right
}

object RBinaryOp_Mul: RBinaryOp_Arith("*") {
    override fun evaluate(left: Long, right: Long): Long = left * right
}

object RBinaryOp_Div: RBinaryOp_Arith("/") {
    override fun evaluate(left: Long, right: Long): Long = left / right
}

object RBinaryOp_Mod: RBinaryOp_Arith("%") {
    override fun evaluate(left: Long, right: Long): Long = left % right
}

object RBinaryOp_Concat: RBinaryOp("+") {
    override fun evaluate(left: RtValue, right: RtValue): RtValue {
        val leftVal = left.asString()
        val rightVal = right.asString()
        val resVal = leftVal + rightVal
        return RtTextValue(resVal)
    }
}
