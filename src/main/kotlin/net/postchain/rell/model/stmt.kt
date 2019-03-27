package net.postchain.rell.model

import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_Value

class R_Variable(val name: String, val type: R_Type)

sealed class R_StatementResult
class R_StatementResult_Return(val value: Rt_Value?): R_StatementResult()
class R_StatementResult_Break: R_StatementResult()

abstract class R_Statement {
    abstract fun execute(frame: Rt_CallFrame): R_StatementResult?
}

object R_EmptyStatement: R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        return null
    }
}

class R_ValStatement(val ptr: R_VarPtr, val expr: R_Expr): R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        val value = expr.evaluate(frame)
        frame.set(ptr, value, false)
        return null
    }
}

class R_VarStatement(val ptr: R_VarPtr, val expr: R_Expr?): R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        if (expr != null) {
            val value = expr.evaluate(frame)
            frame.set(ptr, value, false)
        }
        return null
    }
}

class R_ReturnStatement(val expr: R_Expr?): R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        val value = expr?.evaluate(frame)
        return R_StatementResult_Return(value)
    }
}

class R_BlockStatement(val stmts: List<R_Statement>, val frameBlock: R_FrameBlock): R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        val res = frame.block(frameBlock) {
            execute0(frame)
        }
        return res
    }

    private fun execute0(frame: Rt_CallFrame): R_StatementResult? {
        for (stmt in stmts) {
            val res = stmt.execute(frame)
            if (res != null) {
                return res
            }
        }
        return null
    }
}

class R_ExprStatement(val expr: R_Expr): R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        expr.evaluate(frame)
        return null
    }
}

class R_AssignStatement(val dstExpr: R_DestinationExpr, val expr: R_Expr, val op: R_BinaryOp?): R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        val dstRef = dstExpr.evaluateRef(frame)
        dstRef ?: return null // Null-safe access (operator ?.).

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

class R_IfStatement(val expr: R_Expr, val trueStmt: R_Statement, val falseStmt: R_Statement): R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        val cond = expr.evaluate(frame)
        val b = cond.asBoolean()
        val stmt = if (b) trueStmt else falseStmt
        val res = stmt.execute(frame)
        return res
    }
}

class R_WhenStatement(val chooser: R_WhenChooser, val stmts: List<R_Statement>): R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        val choice = chooser.choose(frame)
        val res = if (choice == null) null else stmts[choice].execute(frame)
        return res
    }
}

class R_WhileStatement(val expr: R_Expr, val stmt: R_Statement, val frameBlock: R_FrameBlock): R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        while (true) {
            val cond = expr.evaluate(frame)
            val b = cond.asBoolean()
            if (!b) {
                break
            }

            val res = executeBody(frame)
            if (res != null) {
                return if (res is R_StatementResult_Return) res else null
            }
        }
        return null
    }

    private fun executeBody(frame: Rt_CallFrame): R_StatementResult? {
        return frame.block(frameBlock) {
            stmt.execute(frame)
        }
    }
}

sealed class R_ForIterator {
    abstract fun list(v: Rt_Value): Iterable<Rt_Value>
}

object R_ForIterator_Collection: R_ForIterator() {
    override fun list(v: Rt_Value): Iterable<Rt_Value> = v.asCollection()
}

object R_ForIterator_Map: R_ForIterator() {
    override fun list(v: Rt_Value): Iterable<Rt_Value> = v.asMap().keys
}

object R_ForIterator_Range: R_ForIterator() {
    override fun list(v: Rt_Value): Iterable<Rt_Value> = v.asRange()
}

class R_ForStatement(
        val varPtr: R_VarPtr,
        val expr: R_Expr,
        val iterator: R_ForIterator,
        val stmt: R_Statement,
        val frameBlock: R_FrameBlock
): R_Statement()
{
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        val value = expr.evaluate(frame)
        val list = iterator.list(value)

        val res = frame.block(frameBlock) {
            execute0(frame, list)
        }

        return res
    }

    private fun execute0(frame: Rt_CallFrame, list: Iterable<Rt_Value>): R_StatementResult? {
        var first = true
        for (item in list) {
            frame.set(varPtr, item, !first)
            first = false
            val res = stmt.execute(frame)
            if (res != null) {
                return if (res is R_StatementResult_Return) res else null
            }
        }
        return null
    }
}

class R_BreakStatement: R_Statement() {
    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        return R_StatementResult_Break()
    }
}
