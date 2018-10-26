package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtRequireError
import net.postchain.rell.runtime.RtValue

class RVariable(val name: String, val type: RType)

sealed class RStatementResult
class RStatementResult_Return(val value: RtValue?): RStatementResult()
class RStatementResult_Break: RStatementResult()

abstract class RStatement {
    abstract fun execute(env: RtEnv): RStatementResult?
}

object REmptyStatement: RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        return null
    }
}

class RValStatement(val offset: Int, val expr: RExpr): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        val value = expr.evaluate(env)
        env.set(offset, value)
        return null
    }
}

class RVarStatement(val offset: Int, val expr: RExpr?): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        if (expr != null) {
            val value = expr.evaluate(env)
            env.set(offset, value)
        }
        return null
    }
}

class RReturnStatement(val expr: RExpr?): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        val value = expr?.evaluate(env)
        return RStatementResult_Return(value)
    }
}

class RBlockStatement(val stmts: List<RStatement>): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        for (stmt in stmts) {
            val res = stmt.execute(env)
            if (res != null) {
                return res
            }
        }
        return null
    }
}

class RExprStatement(val expr: RExpr): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        expr.evaluate(env)
        return null
    }
}

class RAssignStatement(val offset: Int, val expr: RExpr, val op: RBinaryOp?): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        val value = if (op != null) {
            val left = env.get(offset)
            val right = expr.evaluate(env)
            op.evaluate(left, right)
        } else {
            expr.evaluate(env)
        }
        env.set(offset, value)
        return null
    }
}

class RIfStatement(val expr: RExpr, val trueStmt: RStatement, val falseStmt: RStatement): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        val cond = expr.evaluate(env)
        val b = cond.asBoolean()
        val stmt = if (b) trueStmt else falseStmt
        val res = stmt.execute(env)
        return res
    }
}

class RWhileStatement(val expr: RExpr, val stmt: RStatement): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        while (true) {
            val cond = expr.evaluate(env)
            val b = cond.asBoolean()
            if (!b) {
                break
            }

            val res = stmt.execute(env)
            if (res != null) {
                return if (res is RStatementResult_Return) res else null
            }
        }
        return null
    }
}

sealed class RForIterator {
    abstract fun list(v: RtValue): Iterable<RtValue>
}

object RForIterator_List: RForIterator() {
    override fun list(v: RtValue): Iterable<RtValue> = v.asList()
}

object RForIterator_Range: RForIterator() {
    override fun list(v: RtValue): Iterable<RtValue> = v.asRange()
}

class RForStatement(val varOffset: Int, val expr: RExpr, val iterator: RForIterator, val stmt: RStatement): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        val value = expr.evaluate(env)
        val list = iterator.list(value)

        for (item in list) {
            env.set(varOffset, item)
            val res = stmt.execute(env)
            if (res != null) {
                return if (res is RStatementResult_Return) res else null
            }
        }

        return null
    }
}

class RBreakStatement(): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        return RStatementResult_Break()
    }
}

class RRequireStatement(val expr: RExpr, val msgExpr: RExpr?): RStatement() {
    override fun execute(env: RtEnv): RStatementResult? {
        val condValue = expr.evaluate(env)
        val cond = condValue.asBoolean()
        if (!cond) {
            val msg = if (msgExpr == null) "" else {
                val msgValue = msgExpr.evaluate(env)
                msgValue.asString()
            }
            throw RtRequireError(msg)
        }
        return null
    }
}
