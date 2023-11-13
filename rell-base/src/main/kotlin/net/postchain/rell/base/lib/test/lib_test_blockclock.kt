/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.test

import com.google.common.math.LongMath
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.runtime.Rt_Exception
import net.postchain.rell.base.runtime.Rt_IntValue
import net.postchain.rell.base.runtime.Rt_NullValue
import net.postchain.rell.base.runtime.Rt_UnitValue
import net.postchain.rell.base.runtime.utils.Rt_Utils

object Lib_Test_BlockClock {
    const val DEFAULT_FIRST_BLOCK_TIME = 1577836800_000L // 2020-01-01 00:00:00 UTC
    const val DEFAULT_BLOCK_INTERVAL = 10_000L

    val NAMESPACE = Ld_NamespaceDsl.make {
        namespace("rell.test") {
            constant("DEFAULT_FIRST_BLOCK_TIME", DEFAULT_FIRST_BLOCK_TIME)
            constant("DEFAULT_BLOCK_INTERVAL", DEFAULT_BLOCK_INTERVAL)

            property("last_block_time", type = "timestamp") {
                bodyContext { ctx ->
                    val t0 = ctx.exeCtx.testBlockClock.getLastBlockTime()
                    val t = Rt_Utils.checkNotNull(t0) {
                        "no_last_block_time" toCodeMsg "No last block time"
                    }
                    Rt_IntValue.get(t)
                }
            }

            property("last_block_time_or_null", type = "timestamp?") {
                bodyContext { ctx ->
                    val t = ctx.exeCtx.testBlockClock.getLastBlockTime()
                    if (t == null) Rt_NullValue else Rt_IntValue.get(t)
                }
            }

            property("next_block_time", type = "timestamp") {
                bodyContext { ctx ->
                    val t = ctx.exeCtx.testBlockClock.getNextBlockTime()
                    Rt_IntValue.get(t)
                }
            }

            property("block_interval", type = "timestamp") {
                bodyContext { ctx ->
                    val t = ctx.exeCtx.testBlockClock.getBlockInterval()
                    Rt_IntValue.get(t)
                }
            }

            function("set_block_interval", result = "integer") {
                param(name = "interval", type = "integer")
                bodyContext { ctx, a ->
                    val clock = ctx.exeCtx.testBlockClock
                    val res = clock.getBlockInterval()
                    clock.setBlockInterval(a.asInteger())
                    Rt_IntValue.get(res)
                }
            }

            function("set_next_block_time", result = "unit") {
                param(name = "time", type = "timestamp")
                bodyContext { ctx, a ->
                    ctx.exeCtx.testBlockClock.setNextBlockTime(a.asInteger())
                    Rt_UnitValue
                }
            }

            function("set_next_block_time_delta", result = "unit") {
                param(name = "delta", type = "integer")
                bodyContext { ctx, a ->
                    ctx.exeCtx.testBlockClock.setNextBlockTimeDelta(a.asInteger())
                    Rt_UnitValue
                }
            }
        }
    }
}

class Rt_TestBlockClock(state: State = DEFAULT_STATE) {
    private var blockInterval: Long = state.blockInterval
    private var nextBlockTime: Long? = state.nextBlockTime
    private var lastBlockTime: Long? = state.lastBlockTime

    fun getBlockInterval() = blockInterval
    fun getLastBlockTime() = lastBlockTime

    fun setLastBlockTime(time: Long) {
        checkBlockTime(time)
        lastBlockTime = time
        nextBlockTime = null
    }

    fun getNextBlockTime(): Long {
        val manual = nextBlockTime
        if (manual != null) {
            return manual
        }

        val last = lastBlockTime
        if (last != null) {
            val interval = blockInterval
            val next = checkedAdd(last, interval)
            return next
        }

        return Lib_Test_BlockClock.DEFAULT_FIRST_BLOCK_TIME
    }

    fun setBlockInterval(interval: Long) {
        Rt_Utils.check(interval > 0) {
            "block_interval:non_positive:$interval" toCodeMsg "Block interval must be positive (was: $interval)"
        }
        blockInterval = interval
    }

    fun setNextBlockTime(time: Long) {
        checkBlockTime(time)
        nextBlockTime = time
    }

    fun setNextBlockTimeDelta(delta: Long) {
        Rt_Utils.check(delta > 0) {
            "block_time_delta:non_positive:$delta" toCodeMsg "Block time delta must be positive (was: $delta)"
        }
        val last = lastBlockTime
        last ?: return
        nextBlockTime = checkedAdd(last, delta)
    }

    private fun checkBlockTime(time: Long) {
        Rt_Utils.check(time >= 0) {
            "block_time:negative:$time" toCodeMsg "Block time cannot be negative (was: $time)"
        }

        val last = lastBlockTime
        if (last != null) {
            Rt_Utils.check(time > last) {
                "block_time:too_old:$last:$time" toCodeMsg
                        "Block time must be newer than the last time (last: $last, next: $time)"
            }
        }
    }

    fun moveNextBlockTime(diff: Long) {
        val next = getNextBlockTime()
        val newNext = checkedAdd(next, diff)
        setNextBlockTime(newNext)
    }

    private fun checkedAdd(a: Long, b: Long): Long {
        return try {
            LongMath.checkedAdd(a, b)
        } catch (e: ArithmeticException) {
            throw Rt_Exception.common("time_overflow:$a:$b", "Time overflow: $a + $b")
        }
    }

    fun toState(): State = State(
        blockInterval = blockInterval,
        nextBlockTime = nextBlockTime,
        lastBlockTime = lastBlockTime,
    )

    class State(
        val blockInterval: Long,
        val nextBlockTime: Long?,
        val lastBlockTime: Long?,
    )

    companion object {
        val DEFAULT_STATE: State = State(
            blockInterval = Lib_Test_BlockClock.DEFAULT_BLOCK_INTERVAL,
            nextBlockTime = null,
            lastBlockTime = null,
        )
    }
}
