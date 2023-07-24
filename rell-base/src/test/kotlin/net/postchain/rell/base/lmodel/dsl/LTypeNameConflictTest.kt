/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.lib.C_LibFuncCaseCtx
import net.postchain.rell.base.compiler.base.lib.C_LibMemberFunction
import net.postchain.rell.base.compiler.base.lib.C_SpecialLibMemberFunction
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.compiler.vexpr.V_MemberFunctionCall
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.runtime.Rt_UnitValue
import org.junit.Test

class LTypeNameConflictTest: BaseLTest() {
    @Test fun testFunction() {
        val block = makeBlock { function("f", "anything") { body { -> Rt_UnitValue } } }
        val defs = arrayOf("function f(): anything")
        chkNameConflictOK(defs, block, "function f(anything): anything") {
            function("f", "anything") { param("anything"); body { -> Rt_UnitValue } }
        }
        chkNameConflictErr(defs, block, "f") {
            function("f", makeSpecFun())
        }
        chkNameConflictOK(defs, block, "static function f(): anything") {
            staticFunction("f", "anything") { body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "constant f: integer = int[123]") {
            constant("f", 123)
        }
        chkNameConflictErr(defs, block, "f") {
            property("f", "anything") { Rt_UnitValue }
        }
    }

    @Test fun testStaticFunction() {
        val block = makeBlock { staticFunction("f", "anything") { body { -> Rt_UnitValue } } }
        val defs = arrayOf("static function f(): anything")
        chkNameConflictOK(defs, block, "static function f(anything): anything") {
            staticFunction("f", "anything") { param("anything"); body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "function f(): anything") {
            function("f", "anything") { body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "special function f(...)") {
            function("f", makeSpecFun())
        }
        chkNameConflictErr(defs, block, "f") {
            constant("f", 123)
        }
        chkNameConflictOK(defs, block, "property f: anything") {
            property("f", "anything") { Rt_UnitValue }
        }
    }

    @Test fun testSpecialFunction() {
        val block = makeBlock { function("f", makeSpecFun()) }
        val defs = arrayOf("special function f(...)")
        chkNameConflictErr(defs, block, "f") {
            function("f", "anything") { body { -> Rt_UnitValue } }
        }
        chkNameConflictErr(defs, block, "f") {
            function("f", makeSpecFun())
        }
        chkNameConflictOK(defs, block, "static function f(): anything") {
            staticFunction("f", "anything") { body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "constant f: integer = int[123]") {
            constant("f", 123)
        }
        chkNameConflictErr(defs, block, "f") {
            property("f", "anything") { Rt_UnitValue }
        }
    }

    @Test fun testConstant() {
        val block = makeBlock { constant("c", 123) }
        val defs = arrayOf("constant c: integer = int[123]")
        chkNameConflictOK(defs, block, "function c(): anything") {
            function("c", "anything") { body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "special function c(...)") {
            function("c", makeSpecFun())
        }
        chkNameConflictErr(defs, block, "c") {
            staticFunction("c", "anything") { body { -> Rt_UnitValue } }
        }
        chkNameConflictErr(defs, block, "c") {
            constant("c", 123)
        }
        chkNameConflictOK(defs, block, "property c: anything") {
            property("c", "anything") { Rt_UnitValue }
        }
    }

    @Test fun testProperty() {
        val block = makeBlock { property("p", "anything") { Rt_UnitValue } }
        val defs = arrayOf("property p: anything")
        chkNameConflictErr(defs, block, "p") {
            function("p", "anything") { body { -> Rt_UnitValue } }
        }
        chkNameConflictErr(defs, block, "p") {
            function("p", makeSpecFun())
        }
        chkNameConflictOK(defs, block, "static function p(): anything") {
            staticFunction("p", "anything") { body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "constant p: integer = int[123]") {
            constant("p", 123)
        }
        chkNameConflictErr(defs, block, "p") {
            property("p", "anything") { Rt_UnitValue }
        }
    }

    @Test fun testAliasFunction() {
        val (defs, block) = initAlias()
        chkNameConflictOK(defs, block, "function x(): anything", "function c(): anything") {
            function("x", "anything") { alias("c"); body { -> Rt_UnitValue } }
        }
        chkNameConflictErr(defs, block, "p") {
            function("x", "anything") { alias("p"); body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "function x(anything): anything", "function f(anything): anything") {
            function("x", "anything") { alias("f"); param("anything"); body { -> Rt_UnitValue } }
        }
        chkNameConflictErr(defs, block, "g") {
            function("x", "anything") { alias("g"); body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "function x(): anything", "function h(): anything") {
            function("x", "anything") { alias("h"); body { -> Rt_UnitValue } }
        }
    }

    @Test fun testAliasStaticFunction() {
        val (defs, block) = initAlias()
        chkNameConflictErr(defs, block, "c") {
            staticFunction("x", "anything") { alias("c"); body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "static function x(): anything", "static function p(): anything") {
            staticFunction("x", "anything") { alias("p"); body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "static function x(): anything", "static function f(): anything") {
            staticFunction("x", "anything") { alias("f"); body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "static function x(): anything", "static function g(): anything") {
            staticFunction("x", "anything") { alias("g"); body { -> Rt_UnitValue } }
        }
        chkNameConflictOK(defs, block, "static function x(anything): anything", "static function h(anything): anything") {
            staticFunction("x", "anything") { alias("h"); param("anything"); body { -> Rt_UnitValue } }
        }
    }

    private fun initAlias(): Pair<Array<String>, Ld_TypeDefDsl.() -> Unit> {
        val block = makeBlock {
            constant("c", 123)
            property("p", "anything") { Rt_UnitValue }
            function("f", "anything") { body { -> Rt_UnitValue } }
            function("g", makeSpecFun())
            staticFunction("h", "anything") { param("anything"); body { -> Rt_UnitValue } }
        }

        val defs = arrayOf(
            "constant c: integer = int[123]",
            "property p: anything",
            "function f(): anything",
            "special function g(...)",
            "static function h(anything): anything",
        )

        return defs to block
    }

    private fun chkNameConflictOK(
        defs: Array<String>,
        block1: Ld_TypeDefDsl.() -> Unit,
        vararg moreDefs: String,
        block2: Ld_TypeDefDsl.() -> Unit,
    ) {
        val mod = makeModule("test") {
            type("integer")
            type("data") {
                block1(this)
                block2(this)
            }
        }
        chkTypeMems(mod, "data", *defs, *moreDefs)
    }

    private fun chkNameConflictErr(
        defs: Array<String>,
        block1: Ld_TypeDefDsl.() -> Unit,
        name: String,
        block2: Ld_TypeDefDsl.() -> Unit,
    ) {
        val mod = makeModule("test") {
            type("integer")
            type("data") {
                block1(this)
                chkErr("LDE:name_conflict:$name") { block2(this) }
            }
        }
        chkTypeMems(mod, "data", *defs)
    }

    private fun makeBlock(block: Ld_TypeDefDsl.() -> Unit): Ld_TypeDefDsl.() -> Unit = block

    private fun makeSpecFun(): C_LibMemberFunction {
        return object: C_SpecialLibMemberFunction() {
            override fun compileCallFull(
                ctx: C_ExprContext,
                callCtx: C_LibFuncCaseCtx,
                selfType: R_Type,
                args: List<V_Expr>,
                resTypeHint: C_TypeHint
            ): V_MemberFunctionCall {
                throw UnsupportedOperationException()
            }
        }
    }
}
