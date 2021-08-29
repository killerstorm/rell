/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.R_NullType
import net.postchain.rell.model.R_NullableType
import net.postchain.rell.model.R_Type
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

enum class C_VarFact {
    NO,
    MAYBE,
    YES,
    ;

    fun min(value: C_VarFact)= if (ordinal <= value.ordinal) this else value
    fun max(value: C_VarFact)= if (ordinal >= value.ordinal) this else value

    companion object {
        fun forBoolean(b: Boolean) = if (b) YES else NO
    }
}

abstract class C_VarFactsAccess {
    abstract fun isEmpty(): Boolean
    abstract fun inited(varUid: C_VarUid): C_VarFact?
    abstract fun nulled(varUid: C_VarUid): C_VarFact?
    abstract fun toVarFacts(): C_VarFacts
}

class C_VarFacts private constructor(
        inited: Map<C_VarUid, C_VarFact>,
        nulled: Map<C_VarUid, C_VarFact>
): C_VarFactsAccess() {
    val inited = inited.toImmMap()
    val nulled = nulled.toImmMap()

    override fun isEmpty() = inited.isEmpty() && nulled.isEmpty()
    override fun inited(varUid: C_VarUid) = inited[varUid]
    override fun nulled(varUid: C_VarUid) = nulled[varUid]

    fun and(other: C_VarFacts): C_VarFacts {
        if (other.isEmpty()) return this
        if (isEmpty()) return other

        val mutFacts = C_MutableVarFacts(this)
        mutFacts.andFacts(other)
        return mutFacts.toVarFacts()
    }

    fun put(other: C_VarFacts): C_VarFacts {
        if (other.isEmpty()) {
            return this
        } else if (isEmpty()) {
            return other
        }

        val inited2 = put0(inited, other.inited)
        val nulled2 = put0(nulled, other.nulled)
        return C_VarFacts(inited2, nulled2)
    }

    private fun put0(facts1: Map<C_VarUid, C_VarFact>, facts2: Map<C_VarUid, C_VarFact>): Map<C_VarUid, C_VarFact> {
        if (facts2.isEmpty()) {
            return facts1
        } else if (facts1.isEmpty()) {
            return facts2
        } else {
            val res = facts1.toMutableMap()
            res.putAll(facts2)
            return res.toMap()
        }
    }

    override fun toVarFacts() = this

    override fun toString() = varFactsToString(inited, nulled)

    companion object {
        val EMPTY = C_VarFacts(inited = mapOf(), nulled = mapOf())

        fun of(inited: Map<C_VarUid, C_VarFact> = mapOf(), nulled: Map<C_VarUid, C_VarFact> = mapOf()): C_VarFacts {
            return if (inited.isEmpty() && nulled.isEmpty()) EMPTY else C_VarFacts(inited, nulled)
        }

        fun varTypeToNulled(varUid: C_VarUid, varType: R_Type, valueType: R_Type): Map<C_VarUid, C_VarFact> {
            if (varType !is R_NullableType) return mapOf()
            val fact = when (valueType) {
                R_NullType -> C_VarFact.YES
                !is R_NullableType -> C_VarFact.NO
                else -> C_VarFact.MAYBE
            }
            return mapOf(varUid to fact)
        }

        fun forBranches(ctx: C_ExprContext, cases: List<C_VarFacts>): C_VarFacts {
            val inited = calcBranches(cases, { ctx.factsCtx.inited(it) }, { it.inited })
            val nulled = calcBranches(cases, { ctx.factsCtx.nulled(it) }, { it.nulled })
            return of(inited = inited, nulled = nulled)
        }

        private fun calcBranches(
                cases: List<C_VarFacts>,
                prevGetter: (C_VarUid) -> C_VarFact,
                factsGetter: (C_VarFacts) -> Map<C_VarUid, C_VarFact>
        ): Map<C_VarUid, C_VarFact> {
            val res = mutableMapOf<C_VarUid, C_VarFact>()

            val allVars = cases.flatMap { factsGetter(it).keys }.toSet()

            for (id in allVars) {
                val prevValue = prevGetter(id)
                var resValue: C_VarFact? = null
                for (case in cases) {
                    val value = factsGetter(case)[id] ?: prevValue
                    resValue = if (resValue == null || resValue == value) value else C_VarFact.MAYBE
                }
                if (resValue != null && resValue != prevValue) {
                    res[id] = resValue
                }
            }

            return res.toMap()
        }
    }
}

class C_MutableVarFacts(facts: C_VarFacts = C_VarFacts.EMPTY): C_VarFactsAccess() {
    private val inited = facts.inited.toMutableMap()
    private val nulled = facts.nulled.toMutableMap()

    override fun isEmpty() = inited.isEmpty() && nulled.isEmpty()
    override fun inited(varUid: C_VarUid) = inited[varUid]
    override fun nulled(varUid: C_VarUid) = nulled[varUid]

    fun clear() {
        inited.clear()
        nulled.clear()
    }

    fun putFacts(facts: C_VarFacts) {
        inited.putAll(facts.inited)
        nulled.putAll(facts.nulled)
    }

    fun andFacts(facts: C_VarFacts) {
        andFacts(inited, facts.inited, Companion::andInited)
        andFacts(nulled, facts.nulled, Companion::andNulled)
    }

    override fun toVarFacts(): C_VarFacts {
        return C_VarFacts.of(inited = inited.toMap(), nulled = nulled.toMap())
    }

    override fun toString() = varFactsToString(inited, nulled)

    companion object {
        private fun andFacts(
                a: MutableMap<C_VarUid, C_VarFact>,
                b: Map<C_VarUid, C_VarFact>,
                op: (C_VarFact, C_VarFact) -> C_VarFact?
        ) {
            for (varId in b.keys) {
                val af = a[varId]
                val bf = b[varId]!!
                val resf = if (af == null) bf else op(af, bf)
                if (resf != null) {
                    a[varId] = resf
                } else if (af != null) {
                    a.remove(varId)
                }
            }
        }

        private fun andInited(a: C_VarFact, b: C_VarFact): C_VarFact? {
            return a.min(b)
        }

        private fun andNulled(a: C_VarFact, b: C_VarFact): C_VarFact? {
            return if (a == b) a else C_VarFact.MAYBE
        }
    }
}

private fun varFactsToString(inited: Map<C_VarUid, C_VarFact>, nulled: Map<C_VarUid, C_VarFact>): String {
    fun str(map: Map<C_VarUid, C_VarFact>) = "{" + map.entries.joinToString(",") { (k, v) -> "${k.name}=$v" } + "}"
    return "[inited=" + str(inited) + ", nulled=" + str(nulled) + "]"
}

class C_VarFactsContext {
    private val parent: C_VarFactsContext?
    private val facts: C_VarFactsAccess

    constructor(facts: C_VarFactsAccess) {
        parent = null
        this.facts = facts
    }

    private constructor(parent: C_VarFactsContext, facts: C_VarFactsAccess) {
        this.parent = parent
        this.facts = facts
    }

    fun sub(subFacts: C_VarFactsAccess): C_VarFactsContext {
        return if (subFacts.isEmpty()) this else C_VarFactsContext(this, subFacts)
    }

    fun inited(varUid: C_VarUid) = get(varUid, C_VarFactsAccess::inited) ?: C_VarFact.NO
    fun nulled(varUid: C_VarUid) = get(varUid, C_VarFactsAccess::nulled) ?: C_VarFact.MAYBE

    private fun get(varUid: C_VarUid, getter: (C_VarFactsAccess, C_VarUid) -> C_VarFact?): C_VarFact? {
        var ctx: C_VarFactsContext? = this
        while (ctx != null) {
            val fact = getter(ctx.facts, varUid)
            if (fact != null) {
                return fact
            }
            ctx = ctx.parent
        }
        return null
    }

    fun toVarFacts(): C_VarFacts {
        val list = CommonUtils.chainToList(this) { it.parent }.reversed()
        val res = C_MutableVarFacts()
        for (ctx in list) {
            val facts = ctx.facts.toVarFacts()
            res.putFacts(facts)
        }
        return res.toVarFacts()
    }

    override fun toString() = facts.toString()

    companion object {
        val EMPTY = C_VarFactsContext(C_VarFacts.EMPTY)
    }
}

class C_BlockVarFacts(private val ctx: C_VarFactsContext) {
    private val mutFacts = C_MutableVarFacts()
    private var blockModCount: Long = 0

    fun putFacts(facts: C_VarFacts) {
        mutFacts.putFacts(facts)
        ++blockModCount
    }

    fun subContext(): C_VarFactsContext {
        val facts = C_BlkVarFactsAccess(blockModCount)
        return ctx.sub(facts)
    }

    fun copyFacts(): C_VarFacts = mutFacts.toVarFacts()

    private inner class C_BlkVarFactsAccess(val modCount: Long): C_VarFactsAccess() {
        override fun isEmpty(): Boolean {
            checkModCount()
            return mutFacts.isEmpty()
        }

        override fun inited(varUid: C_VarUid): C_VarFact? {
            checkModCount()
            return mutFacts.inited(varUid)
        }

        override fun nulled(varUid: C_VarUid): C_VarFact? {
            checkModCount()
            return mutFacts.nulled(varUid)
        }

        override fun toVarFacts(): C_VarFacts {
            checkModCount()
            return mutFacts.toVarFacts()
        }

        private fun checkModCount() {
            check(blockModCount == modCount) { "Block facts have been modified since sub-context creation" }
        }

        override fun toString() = mutFacts.toString()
    }
}

class C_ExprVarFacts private constructor(
        val trueFacts: C_VarFacts,
        val falseFacts: C_VarFacts,
        val postFacts: C_VarFacts
) {
    fun isEmpty() = trueFacts.isEmpty() && falseFacts.isEmpty()

    fun and(other: C_ExprVarFacts): C_ExprVarFacts {
        if (other.isEmpty()) return this
        if (isEmpty()) return other
        val trueFacts2 = trueFacts.and(other.trueFacts)
        val falseFacts2 = falseFacts.and(other.falseFacts)
        val postFacts2 = postFacts.and(other.postFacts)
        return C_ExprVarFacts(trueFacts2, falseFacts2, postFacts2)
    }

    fun update(
            trueFacts: C_VarFacts? = null,
            falseFacts: C_VarFacts? = null,
            postFacts: C_VarFacts? = null
    ): C_ExprVarFacts {
        if (trueFacts == null && falseFacts == null && postFacts == null) return this
        return of(
                trueFacts = trueFacts ?: this.trueFacts,
                falseFacts = falseFacts ?: this.falseFacts,
                postFacts = postFacts ?: this.postFacts
        )
    }

    companion object {
        val EMPTY = C_ExprVarFacts(C_VarFacts.EMPTY, C_VarFacts.EMPTY, C_VarFacts.EMPTY)

        fun of(
                trueFacts: C_VarFacts = C_VarFacts.EMPTY,
                falseFacts: C_VarFacts = C_VarFacts.EMPTY,
                postFacts: C_VarFacts = C_VarFacts.EMPTY
        ): C_ExprVarFacts {
            return if (trueFacts.isEmpty() && falseFacts.isEmpty() && postFacts.isEmpty()) EMPTY else
                C_ExprVarFacts(trueFacts, falseFacts, postFacts)
        }

        fun forNullCheck(value: V_Expr, nullIfTrue: Boolean): C_ExprVarFacts {
            val varId = value.varId()
            if (varId == null) {
                return EMPTY
            }

            val trueNulled = mapOf(varId to C_VarFact.forBoolean(nullIfTrue))
            val falseNulled = mapOf(varId to C_VarFact.forBoolean(!nullIfTrue))
            val trueFacts = C_VarFacts.of(nulled = trueNulled)
            val falseFacts = C_VarFacts.of(nulled = falseNulled)
            return of(trueFacts = trueFacts, falseFacts = falseFacts)
        }

        fun forNullCast(preFacts: C_VarFacts, value: V_Expr): C_ExprVarFacts {
            var varFacts = preFacts

            val varId = value.varId()
            if (varId != null) {
                varFacts = varFacts.and(C_VarFacts.of(nulled = mapOf(varId to C_VarFact.NO)))
            }

            return of(postFacts = varFacts)
        }

        fun forSubExpressions(vararg values: V_Expr): C_ExprVarFacts {
            return forSubExpressions(values.toImmList())
        }

        fun forSubExpressions(values: List<V_Expr>): C_ExprVarFacts {
            val postFacts = values.fold(C_VarFacts.EMPTY) { facts, value -> facts.and(value.varFacts.postFacts) }
            return of(postFacts = postFacts)
        }
    }
}
