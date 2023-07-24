/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.def.C_GlobalFunction
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty_RtValue
import net.postchain.rell.base.compiler.vexpr.V_GlobalFunctionCall
import net.postchain.rell.base.runtime.Rt_IntValue
import net.postchain.rell.base.runtime.Rt_UnitValue
import net.postchain.rell.base.utils.LazyPosString
import net.postchain.rell.base.utils.ide.IdeSymbolInfo
import org.junit.Test

class LNamespaceNameConflictTest: BaseLTest() {
    @Test fun testNamespace() {
        val block = makeBlock { namespace("ns") {} }
        val defs = arrayOf("namespace ns")

        chkNameConflictOK(defs, block, "type ns.foo", "type ns.bar") {
            namespace("ns") { type("foo") }
            namespace("ns") { type("bar") }
        }
        chkNameConflictErr(defs, block, "ns") { type("ns") }
        chkNameConflictErr(defs, block, "ns") { struct("ns") {} }
        chkNameConflictErr(defs, block, "ns") { constant("ns", "anything", Rt_UnitValue) }
        chkNameConflictErr(defs, block, "ns") { property("ns", "anything") { bodyContext { Rt_UnitValue } } }
        chkNameConflictErr(defs, block, "ns") { property("ns", makeSpecProp()) }
        chkNameConflictErr(defs, block, "ns") { function("ns", "anything") { body { -> Rt_UnitValue } } }
        chkNameConflictErr(defs, block, "ns") { function("ns", makeSpecFun()) }
    }

    @Test fun testFunction() {
        val block = makeBlock { function("f", "anything") { body { -> Rt_UnitValue } } }
        val defs = arrayOf("function f(): anything")

        chkNameConflictErr(defs, block, "f") { namespace("f") {} }
        chkNameConflictErr(defs, block, "f") { type("f") }
        chkNameConflictErr(defs, block, "f") { struct("f") {} }
        chkNameConflictErr(defs, block, "f") { constant("f", "anything", Rt_UnitValue) }
        chkNameConflictErr(defs, block, "f") { property("f", "anything") { bodyContext { Rt_UnitValue } } }
        chkNameConflictErr(defs, block, "f") { property("f", makeSpecProp()) }
        chkNameConflictOK(defs, block, "function f(anything): anything") {
            function("f", "anything") { param("anything"); body { -> Rt_UnitValue } }
        }
        chkNameConflictErr(defs, block, "f") { function("f", makeSpecFun()) }
    }

    @Test fun testOther() {
        chkNameConflictCommon("data", "type data") { type("data") }
        chkNameConflictCommon("data", "struct data") { struct("data") {} }
        chkNameConflictCommon("c", "constant c: anything = unit") { constant("c", "anything", Rt_UnitValue) }
        chkNameConflictCommon("p", "property p: anything") { property("p", "anything") { bodyContext { Rt_UnitValue } } }
        chkNameConflictCommon("p", "property p") { property("p", makeSpecProp()) }
        chkNameConflictCommon("f", "special function f()") { function("f", makeSpecFun()) }
    }

    private fun chkNameConflictCommon(name: String, def: String, block: Ld_NamespaceDsl.() -> Unit) {
        val defs = arrayOf(def)
        chkNameConflictErr(defs, block, name) { namespace(name) {} }
        chkNameConflictErr(defs, block, name) { type(name) }
        chkNameConflictErr(defs, block, name) { struct(name) {} }
        chkNameConflictErr(defs, block, name) { constant(name, "anything", Rt_UnitValue) }
        chkNameConflictErr(defs, block, name) { property(name, "anything") { bodyContext { Rt_UnitValue } } }
        chkNameConflictErr(defs, block, name) { property(name, makeSpecProp()) }
        chkNameConflictErr(defs, block, name) { function(name, "anything") { body { -> Rt_UnitValue } } }
        chkNameConflictErr(defs, block, name) { function(name, makeSpecFun()) }
    }

    @Test fun testLink() {
        val block = makeBlock {
            namespace("ns") { function("f", "anything") { body { -> Rt_UnitValue } } }
            link(target = "ns.f", name = "l")
        }
        val defs0 = arrayOf("namespace ns", "function ns.f(): anything")
        val defs = defs0 + arrayOf("function l(): anything")

        chkNameConflictErr(defs, block, "l") { type("l") }
        chkNameConflictErr(defs, block, "l") { struct("l") {} }
        chkNameConflictErr(defs, block, "l") { constant("l", "anything", Rt_UnitValue) }
        chkNameConflictErr(defs, block, "l") { property("l", "anything") { bodyContext { Rt_UnitValue } } }
        chkNameConflictErr(defs, block, "l") { property("l", makeSpecProp()) }
        chkNameConflictErr(defs, block, "l") { function("l", makeSpecFun()) }

        chkNameConflictOK(defs0, block, "function l(anything): anything", "function l(): anything") {
            function("l", "anything") { param("anything"); body { -> Rt_UnitValue } }
        }
    }

    @Test fun testAliasType() {
        chkAliasType("ns")
        chkAliasType("t")
        chkAliasType("s")
        chkAliasType("c")
        chkAliasType("p1")
        chkAliasType("p2")
        chkAliasType("f1")
        chkAliasType("f2")
    }

    private fun chkAliasType(alias: String) {
        val (defs, block) = initAlias()
        chkNameConflictErr(defs, block, alias) { type("x") { alias(alias) } }
    }

    @Test fun testAliasFunction() {
        chkAliasFunction("ns")
        chkAliasFunction("t")
        chkAliasFunction("s")
        chkAliasFunction("c")
        chkAliasFunction("p1")
        chkAliasFunction("p2")
        chkAliasFunction("f2")

        val (defs, block) = initAlias()
        chkNameConflictOK(defs, block, "function x(anything): anything", "function f1(anything): anything") {
            function("x", "anything") {
                alias("f1")
                param("anything")
                body { -> Rt_UnitValue }
            }
        }
    }

    private fun chkAliasFunction(alias: String) {
        val (defs, block) = initAlias()
        chkNameConflictErr(defs, block, alias) { function("x", "anything") { alias(alias); body { -> Rt_UnitValue } } }
    }

    private fun initAlias(): Pair<Array<String>, Ld_NamespaceDsl.() -> Unit> {
        val block = makeBlock {
            namespace("ns") {}
            type("t")
            struct("s") {}
            constant("c", "anything", Rt_UnitValue)
            property("p1", "anything") { bodyContext { Rt_UnitValue } }
            property("p2", makeSpecProp())
            function("f1", "anything") { body { -> Rt_UnitValue } }
            function("f2", makeSpecFun())
        }

        val defs = arrayOf(
            "namespace ns",
            "type t",
            "struct s",
            "constant c: anything = unit",
            "property p1: anything",
            "property p2",
            "function f1(): anything",
            "special function f2()",
        )

        return defs to block
    }

    private fun chkNameConflictOK(
        defs: Array<String>,
        block1: Ld_NamespaceDsl.() -> Unit,
        vararg moreDefs: String,
        block2: Ld_NamespaceDsl.() -> Unit,
    ) {
        val mod = makeModule("test") {
            block1(this)
            block2(this)
        }
        chkDefs(mod, *defs, *moreDefs)
    }

    private fun chkNameConflictErr(
        defs: Array<String>,
        block1: Ld_NamespaceDsl.() -> Unit,
        name: String,
        block2: Ld_NamespaceDsl.() -> Unit,
    ) {
        val mod = makeModule("test") {
            block1(this)
            chkErr("LDE:name_conflict:$name") { block2(this) }
        }
        chkDefs(mod, *defs)
    }

    private fun makeBlock(block: Ld_NamespaceDsl.() -> Unit): Ld_NamespaceDsl.() -> Unit = block

    private fun makeSpecProp(): C_NamespaceProperty {
        return C_NamespaceProperty_RtValue(IdeSymbolInfo.DEF_CONSTANT, Rt_IntValue(123))
    }

    private fun makeSpecFun(): C_GlobalFunction {
        return object: C_GlobalFunction() {
            override fun compileCall(
                ctx: C_ExprContext,
                name: LazyPosString,
                args: List<S_CallArgument>,
                resTypeHint: C_TypeHint
            ): V_GlobalFunctionCall {
                throw UnsupportedOperationException()
            }
        }
    }
}
