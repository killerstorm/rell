/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.runtime

import net.postchain.rell.model.*
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.toImmList
import java.util.*

class Rt_FrameCaller(val frame: Rt_CallFrame, val pos: R_StackPos)

class Rt_CallFrame(
        val defCtx: Rt_DefinitionContext,
        private val rFrame: R_CallFrame,
        private val caller: Rt_FrameCaller?,
        state: Rt_CallFrameState?,
        hasGuardBlock: Boolean
) {
    val exeCtx = defCtx.exeCtx
    val sqlExec = exeCtx.sqlExec
    val appCtx = exeCtx.appCtx

    private var curBlock = rFrame.rootBlock
    private val values = Array<Rt_Value?>(rFrame.size) { null }

    private var beforeGuardBlock = hasGuardBlock

    init {
        if (state != null) {
            check(rFrame.size >= state.values.size)
            for (i in 0 until state.values.size) {
                values[i] = state.values[i].orElse(null)
            }
        }
    }

    fun <T> block(block: R_FrameBlock, code: () -> T): T {
        val oldBlock = curBlock
        check(block.parentUid == oldBlock.uid) { "expected current block ${block.parentUid}, was ${oldBlock.uid}" }
        check(block.offset + block.size <= values.size)

        for (i in 0 until block.size) {
            check(values[block.offset + i] == null)
        }

        curBlock = block
        try {
            val res = code()
            return res
        } finally {
            curBlock = oldBlock
            for (i in 0 until block.size) {
                values[block.offset + i] = null
            }
        }
    }

    fun set(ptr: R_VarPtr, varType: R_Type, value: Rt_Value, overwrite: Boolean) {
        R_Expr.typeCheck(this, varType, value)
        val offset = checkPtr(ptr)
        if (!overwrite) {
            check(values[offset] == null)
        }
        values[offset] = value
    }

    fun get(ptr: R_VarPtr): Rt_Value {
        val value = getOpt(ptr)
        check(value != null) { "Variable not initialized: $ptr" }
        return value
    }

    fun getOpt(ptr: R_VarPtr): Rt_Value? {
        val offset = checkPtr(ptr)
        val value = values[offset]
        return value
    }

    private fun checkPtr(ptr: R_VarPtr): Int {
        val block = curBlock
        check(ptr.blockUid == block.uid) { "wrong var block: var_ptr = $ptr, cur_block = ${block.uid}" }
        val offset = ptr.offset
        check(offset >= 0)
        check(offset < block.offset + block.size)
        return offset
    }

    fun stackTrace(lastPos: R_FilePos): List<R_StackPos> {
        val res = mutableListOf<R_StackPos>()

        res.add(R_StackPos(defCtx.defId, lastPos))

        var frame: Rt_CallFrame? = this
        while (frame != null) {
            val frameCaller = frame.caller
            if (frameCaller == null) break
            res.add(frameCaller.pos)
            frame = frameCaller.frame
        }

        return res.toImmList()
    }

    fun dumpState(): Rt_CallFrameState {
        checkEquals(curBlock.uid, rFrame.rootBlock.uid)
        val valuesList = values.map { Optional.ofNullable(it) }.toList()
        return Rt_CallFrameState(valuesList)
    }

    fun guardCompleted() {
        beforeGuardBlock = false
    }

    fun dbUpdateAllowed() = defCtx.dbUpdateAllowed && !beforeGuardBlock

    fun checkDbUpdateAllowed() {
        if (!defCtx.dbUpdateAllowed) {
            throw Rt_Error("no_db_update:def", "Database modifications are not allowed in this context")
        } else if (beforeGuardBlock) {
            throw Rt_Error("no_db_update:guard", "Database modifications are not allowed inside or before a guard block")
        }
    }

    fun checkBlock(block: R_FrameBlockUid) {
        check(block == curBlock.uid) { "wrong block: expected $block was ${curBlock.uid}" }
    }
}

class Rt_CallFrameState(values: List<Optional<Rt_Value>>) {
    val values = values.toImmList()

    companion object {
        val EMPTY = Rt_CallFrameState(listOf())
    }
}
