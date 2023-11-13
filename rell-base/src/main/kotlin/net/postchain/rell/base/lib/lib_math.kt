/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.compiler.base.lib.C_SysFunctionBody
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.expr.Db_SysFunction
import net.postchain.rell.base.runtime.Rt_BigIntegerValue
import net.postchain.rell.base.runtime.Rt_DecimalValue
import net.postchain.rell.base.runtime.Rt_Exception
import net.postchain.rell.base.runtime.Rt_IntValue

object Lib_Math {
    val Abs_Integer = C_SysFunctionBody.simple(Db_SysFunction.simple("abs", "ABS"), pure = true) { a ->
        val v = a.asInteger()
        if (v == Long.MIN_VALUE) {
            throw Rt_Exception.common("abs:integer:overflow:$v", "Integer overflow: $v")
        }
        val r = Math.abs(v)
        Rt_IntValue.get(r)
    }

    val Abs_BigInteger = C_SysFunctionBody.simple(Db_SysFunction.simple("abs", "ABS"), pure = true) { a ->
        val v = a.asBigInteger()
        val r = v.abs()
        Rt_BigIntegerValue.get(r)
    }

    val Abs_Decimal = C_SysFunctionBody.simple(Db_SysFunction.simple("abs", "ABS"), pure = true) { a ->
        val v = a.asDecimal()
        val r = v.abs()
        Rt_DecimalValue.get(r)
    }

    val Min_Integer = C_SysFunctionBody.simple(Db_SysFunction.simple("min", "LEAST"), pure = true) { a, b ->
        val v1 = a.asInteger()
        val v2 = b.asInteger()
        val r = Math.min(v1, v2)
        Rt_IntValue.get(r)
    }

    val Min_BigInteger = C_SysFunctionBody.simple(Db_SysFunction.simple("min", "LEAST"), pure = true) { a, b ->
        val v1 = a.asBigInteger()
        val v2 = b.asBigInteger()
        val r = v1.min(v2)
        Rt_BigIntegerValue.get(r)
    }

    val Min_Decimal = C_SysFunctionBody.simple(Db_SysFunction.simple("min", "LEAST"), pure = true) { a, b ->
        val v1 = a.asDecimal()
        val v2 = b.asDecimal()
        val r = v1.min(v2)
        Rt_DecimalValue.get(r)
    }

    val Max_Integer = C_SysFunctionBody.simple(Db_SysFunction.simple("max", "GREATEST"), pure = true) { a, b ->
        val v1 = a.asInteger()
        val v2 = b.asInteger()
        val r = Math.max(v1, v2)
        Rt_IntValue.get(r)
    }

    val Max_BigInteger = C_SysFunctionBody.simple(Db_SysFunction.simple("max", "GREATEST"), pure = true) { a, b ->
        val v1 = a.asBigInteger()
        val v2 = b.asBigInteger()
        val r = v1.max(v2)
        Rt_BigIntegerValue.get(r)
    }

    val Max_Decimal = C_SysFunctionBody.simple(Db_SysFunction.simple("max", "GREATEST"), pure = true) { a, b ->
        val v1 = a.asDecimal()
        val v2 = b.asDecimal()
        val r = v1.max(v2)
        Rt_DecimalValue.get(r)
    }

    val NAMESPACE = Ld_NamespaceDsl.make {
        defFnAbs(this, "integer", Abs_Integer)
        defFnAbs(this, "big_integer", Abs_BigInteger)
        defFnAbs(this, "decimal", Abs_Decimal)

        defFnMinMax(this, "integer", Min_Integer, Max_Integer)
        defFnMinMax(this, "big_integer", Min_BigInteger, Max_BigInteger)
        defFnMinMax(this, "decimal", Min_Decimal, Max_Decimal)
    }

    private fun defFnAbs(d: Ld_NamespaceDsl, type: String, fn: C_SysFunctionBody) {
        d.function("abs", type) {
            param(type)
            bodyRaw(fn)
        }
    }

    private fun defFnMinMax(d: Ld_NamespaceDsl, type: String, fnMin: C_SysFunctionBody, fnMax: C_SysFunctionBody) {
        d.function("min", type) {
            param(type)
            param(type)
            bodyRaw(fnMin)
        }

        d.function("max", type) {
            param(type)
            param(type)
            bodyRaw(fnMax)
        }
    }
}
