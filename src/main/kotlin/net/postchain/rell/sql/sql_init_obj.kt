/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.sql

import net.postchain.rell.model.R_Object
import net.postchain.rell.runtime.Rt_Error
import net.postchain.rell.runtime.Rt_ExecutionContext
import java.util.*

class SqlObjectsInit(private val exeCtx: Rt_ExecutionContext) {
    private var addDone = false
    private val states = mutableMapOf<R_Object, ObjState>()
    private val initStack: Deque<ObjState> = ArrayDeque()

    fun addObject(obj: R_Object) {
        check(!addDone)
        check(obj !in states) { "Object already in the map: '${obj.appLevelName}'" }
        states[obj] = ObjState(obj)
    }

    fun initObject(obj: R_Object) {
        addDone = false
        pushState(obj) {
            it.init()
        }
    }

    fun forceObject(obj: R_Object) {
        addDone = false
        pushState(obj) {
            it.force()
        }
    }

    private fun pushState(obj: R_Object, code: (ObjState) -> Unit) {
        val state = states.getValue(obj)
        initStack.push(state)
        try {
            code(state)
        } finally {
            initStack.pop()
        }
    }

    private inner class ObjState(val obj: R_Object) {
        private var started: Boolean = false
        private var finished: Boolean = false

        fun init() {
            if (!finished) {
                init0()
            }
        }

        fun force() {
            check(!finished) { "Object must have been already initialized: '${obj.appLevelName}'" }
            init0()
        }

        private fun init0() {
            if (started) {
                throw cycleError()
            }

            started = true
            val frame = exeCtx.appCtx.createRootFrame(obj.pos, exeCtx.sqlExec, false)
            obj.insert(frame)
            finished = true
        }

        private fun cycleError(): Rt_Error {
            check(!initStack.isEmpty()) { "Invalid state: '${obj.appLevelName}'" }

            val cycle = mutableListOf<ObjState>()
            var found = false
            for (state in initStack.reversed()) {
                found = found || state === this
                if (found) cycle.add(state)
            }

            val shortStr = cycle.joinToString(",") { it.obj.appLevelName }
            val fullStr = cycle.joinToString(", ") { it.obj.appLevelName }

            return Rt_Error("obj:init_cycle:$shortStr",
                    "Cannot initialize object '${obj.appLevelName}' because it depends on itself: $fullStr")
        }
    }
}
