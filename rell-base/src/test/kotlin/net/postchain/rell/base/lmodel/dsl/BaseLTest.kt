/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lmodel.dsl

import net.postchain.rell.base.lmodel.L_Module
import net.postchain.rell.base.utils.toImmList
import kotlin.test.assertEquals

abstract class BaseLTest {
    protected fun makeModule(name: String, block: Ld_ModuleDsl.() -> Unit): L_Module {
        return Ld_ModuleDsl.make(name, block)
    }

    protected fun chkDefs(mod: L_Module, vararg expected: String) {
        val defs = mod.namespace.getAllDefs()
        val exp = expected.toImmList()
        val act = defs.map { it.strCode() }
        assertEquals(exp, act)
    }

    protected fun chkTypeMems(mod: L_Module, typeName: String, vararg expected: String) {
        chkTypeMems0(mod, typeName, false, expected.toImmList())
    }

    protected fun chkTypeAllMems(mod: L_Module, typeName: String, vararg expected: String) {
        chkTypeMems0(mod, typeName, true, expected.toImmList())
    }

    private fun chkTypeMems0(mod: L_Module, typeName: String, all: Boolean, expected: List<String>) {
        val typeDef = mod.getTypeDef(typeName)
        val members = if (all) typeDef.allMembers else typeDef.members
        val exp = expected.toImmList()
        val act = members.all.map { it.strCode() }
        assertEquals(exp, act)
    }

    protected fun chkModuleErr(exp: String, block: Ld_ModuleDsl.() -> Unit) {
        val act = try {
            makeModule("test", block)
            "OK"
        } catch (e: Ld_Exception) {
            "LDE:${e.code}"
        }
        assertEquals(exp, act)
    }

    protected fun chkErr(expected: String, block: () -> Unit) {
        val actual = try {
            block()
            "OK"
        } catch (e: Ld_Exception) {
            "LDE:${e.code}"
        }
        assertEquals(expected, actual)
    }
}
