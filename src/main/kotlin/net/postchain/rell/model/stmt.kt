package net.postchain.rell.model

import net.postchain.rell.runtime.RtCallFrame
import net.postchain.rell.runtime.RtRequireError
import net.postchain.rell.runtime.RtValue

class RVariable(val name: String, val type: RType)

sealed class RStatementResult
class RStatementResult_Return(val value: RtValue?): RStatementResult()
class RStatementResult_Break: RStatementResult()

abstract class RStatement {
    abstract fun execute(frame: RtCallFrame): RStatementResult?
}

object REmptyStatement: RStatement() {
    override fun execute(frame: RtCallFrame): RStatementResult? {
        return null
    }
}

class RValStatement(val ptr: RVarPtr, val expr: RExpr): RStatement() {
    override fun execute(frame: RtCallFrame): RStatementResult? {
        val value = expr.evaluate(frame)
        frame.set(ptr, value, false)
        return null
    }
}

class RVarStatement(val ptr: RVarPtr, val expr: RExpr?): RStatement() {
    override fun execute(frame: RtCallFrame): RStatementResult? {
        if (expr != null) {
            val value = expr.evaluate(frame)
            frame.set(ptr, value, false)
        }
        return null
    }
}

class RReturnStatement(val expr: RExpr?): RStatement() {
    override fun execute(frame: RtCallFrame): RStatementResult? {
        val value = expr?.evaluate(frame)
        return RStatementResult_Return(value)
    }
}

class RBlockStatement(val stmts: List<RStatement>, val frameBlock: RFrameBlock): RStatement() {
    override fun execute(frame: RtCallFrame): RStatementResult? {
        val res = frame.block(frameBlock) {
            execute0(frame)
        }
        return res
    }

    private fun execute0(frame: RtCallFrame): RStatementResult? {
        for (stmt in stmts) {
            val res = stmt.execute(frame)
            if (res != null) {
                return res
            }
        }
        return null
    }
}

class RExprStatement(val expr: RExpr): RStatement() {
    override fun execute(frame: RtCallFrame): RStatementResult? {
        expr.evaluate(frame)
        return null
    }
}

class RAssignStatement(val dstExpr: RDestinationExpr, val expr: RExpr, val op: RBinaryOp?): RStatement() {
    override fun execute(frame: RtCallFrame): RStatementResult? {
        val dstRef = dstExpr.evaluateRef(frame)
        val value = if (op != null) {
            val left = dstRef.get()
            val right = expr.evaluate(frame)
            op.evaluate(left, right)
        } else {
            expr.evaluate(frame)
        }
        dstRef.set(value)
        return null
    }
}

class RIfStatement(val expr: RExpr, val trueStmt: RStatement, val falseStmt: RStatement): RStatement() {
    override fun execute(frame: RtCallFrame): RStatementResult? {
        val cond = expr.evaluate(frame)
        val b = cond.asBoolean()
        val stmt = if (b) trueStmt else falseStmt
        val res = stmt.execute(frame)
        return res
    }
}

class RWhileStatement(val expr: RExpr, val stmt: RStatement, val frameBlock: RFrameBlock): RStatement() {
    override fun execute(frame: RtCallFrame): RStatementResult? {
        while (true) {
            val cond = expr.evaluate(frame)
            val b = cond.asBoolean()
            if (!b) {
                break
            }

            val res = executeBody(frame)
            if (res != null) {
                return if (res is RStatementResult_Return) res else null
            }
        }
        return null
    }

    private fun executeBody(frame: RtCallFrame): RStatementResult? {
        return frame.block(frameBlock) {
            stmt.execute(frame)
        }
    }
}

sealed class RForIterator {
    abstract fun list(v: RtValue): Iterable<RtValue>
}

object RForIterator_Collection: RForIterator() {
    override fun list(v: RtValue): Iterable<RtValue> = v.asCollection()
}

object RForIterator_Map: RForIterator() {
    override fun list(v: RtValue): Iterable<RtValue> = v.asMap().keys
}

object RForIterator_Range: RForIterator() {
    override fun list(v: RtValue): Iterable<RtValue> = v.asRange()
}

class RForStatement(
        val varPtr: RVarPtr,
        val expr: RExpr,
        val iterator: RForIterator,
        val stmt: RStatement,
        val frameBlock: RFrameBlock
): RStatement()
{
    override fun execute(frame: RtCallFrame): RStatementResult? {
        val value = expr.evaluate(frame)
        val list = iterator.list(value)

        val res = frame.block(frameBlock) {
            execute0(frame, list)
        }

        return res
    }

    private fun execute0(frame: RtCallFrame, list: Iterable<RtValue>): RStatementResult? {
        var first = true
        for (item in list) {
            frame.set(varPtr, item, !first)
            first = false
            val res = stmt.execute(frame)
            if (res != null) {
                return if (res is RStatementResult_Return) res else null
            }
        }
        return null
    }
}

class RBreakStatement(): RStatement() {
    override fun execute(frame: RtCallFrame): RStatementResult? {
        return RStatementResult_Break()
    }
}
