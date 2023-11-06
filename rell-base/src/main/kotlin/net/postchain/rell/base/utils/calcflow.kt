/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.utils

import java.util.*

// TODO check job is added only once and only to one manager
// TODO check that "after" job was added to the same manager

sealed class CfResult<T> {
    companion object {
        fun <T> direct(value: T): CfResult<T> = CfResult_Direct(value)
        fun <T, R> after(job: CfJob<R>, block: (R) -> CfResult<T>): CfResult<T> = CfResult_After(job, block)

        fun <T, R> afterOrDirect(job: CfJob<R>?, block: (R?) -> CfResult<T>): CfResult<T> {
            return if (job == null) {
                block(null)
            } else {
                after(job, block)
            }
        }
    }
}

sealed class CfJob<T> {
    abstract fun getResult(): T
}

private class CfResult_Direct<T>(val value: T): CfResult<T>()
private class CfResult_After<T, R>(val job: CfJob<R>, val block: (R) -> CfResult<T>): CfResult<T>()

class CfManager {
    private val items: Queue<CfItem<*>> = ArrayDeque()

    fun <T> job(block: () -> CfResult<T>): CfJob<T> {
        val job = CfJobImpl(block)
        items.add(CfItem_Simple(job))
        return job
    }

    fun execute() {
        while (items.isNotEmpty()) {
            val item = items.remove()
            item.execute()
        }
    }

    private fun <T> processResult(job: CfJobImpl<T>, res: CfResult<T>) {
        when (res) {
            is CfResult_Direct<T> -> {
                val after = job.setResult(res.value)
                items.addAll(after)
            }
            is CfResult_After<T, *> -> {
                val item = afterItem(job, res)
                res.job as CfJobImpl<*>
                if (res.job.isCompleted()) {
                    items.add(item)
                } else {
                    res.job.addAfter(item)
                }
            }
        }
    }

    private fun <T, R> afterItem(job: CfJobImpl<T>, res: CfResult_After<T, R>): CfItem<T> {
        res.job as CfJobImpl<R>
        return CfItem_After(job, res.job, res.block)
    }

    private inner class CfItem_Simple<T>(job: CfJobImpl<T>): CfItem<T>(job) {
        override fun execute() {
            val res = job.block()
            processResult(job, res)
        }
    }

    private inner class CfItem_After<T, R>(
        job: CfJobImpl<T>,
        val sourceJob: CfJobImpl<R>,
        val block: (R) -> CfResult<T>,
    ) : CfItem<T>(job) {
        override fun execute() {
            val sourceValue = sourceJob.getResult()
            val res = block(sourceValue)
            processResult(job, res)
        }
    }
}

private abstract class CfItem<T>(protected val job: CfJobImpl<T>) {
    abstract fun execute()
}

private class CfJobImpl<T>(val block: () -> CfResult<T>): CfJob<T>() {
    private var completed = false
    private var result: T? = null
    private var after = mutableListOf<CfItem<*>>()

    fun isCompleted(): Boolean = completed

    fun setResult(value: T): List<CfItem<*>> {
        check(!completed)
        result = value
        completed = true

        val resAfter = after
        after = mutableListOf()
        return resAfter
    }

    override fun getResult(): T {
        check(completed)
        return checkNotNull(result) //TODO allow nullable type
    }

    fun addAfter(item: CfItem<*>) {
        check(!completed)
        check(result == null)
        after.add(item)
    }
}
