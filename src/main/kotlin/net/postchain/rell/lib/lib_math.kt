/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.model.R_DecimalType
import net.postchain.rell.model.R_IntegerType
import net.postchain.rell.model.expr.Db_SysFunction
import net.postchain.rell.runtime.Rt_DecimalValue
import net.postchain.rell.runtime.Rt_Error
import net.postchain.rell.runtime.Rt_IntValue

object C_Lib_Math {
    val Abs_Integer = MathFns.Abs_Integer
    val Abs_Decimal = MathFns.Abs_Decimal

    val Min_Integer = MathFns.Min_Integer
    val Min_Decimal = MathFns.Min_Decimal

    val Max_Integer = MathFns.Max_Integer
    val Max_Decimal = MathFns.Max_Decimal

    fun bind(nsBuilder: C_SysNsProtoBuilder) {
        val fb = C_GlobalFuncBuilder(null)

        fb.add("abs", R_IntegerType, listOf(R_IntegerType), MathFns.Abs_Integer)
        fb.add("abs", R_DecimalType, listOf(R_DecimalType), MathFns.Abs_Decimal)
        fb.add("min", R_IntegerType, listOf(R_IntegerType, R_IntegerType), MathFns.Min_Integer)
        fb.add("min", R_DecimalType, listOf(R_DecimalType, R_DecimalType), MathFns.Min_Decimal)
        fb.add("max", R_IntegerType, listOf(R_IntegerType, R_IntegerType), MathFns.Max_Integer)
        fb.add("max", R_DecimalType, listOf(R_DecimalType, R_DecimalType), MathFns.Max_Decimal)

        C_LibUtils.bindFunctions(nsBuilder, fb.build())
    }
}

private object MathFns {
    val Abs_Integer = C_SysFunction.simple1(Db_SysFunction.simple("abs", "ABS"), pure = true) { a ->
        val v = a.asInteger()
        if (v == Long.MIN_VALUE) {
            throw Rt_Error("abs:integer:overflow:$v", "Integer overflow: $v")
        }
        val r = Math.abs(v)
        Rt_IntValue(r)
    }

    val Abs_Decimal = C_SysFunction.simple1(Db_SysFunction.simple("abs", "ABS"), pure = true) { a ->
        val v = a.asDecimal()
        val r = v.abs()
        Rt_DecimalValue.of(r)
    }

    val Min_Integer = C_SysFunction.simple2(Db_SysFunction.simple("min", "LEAST"), pure = true) { a, b ->
        val v1 = a.asInteger()
        val v2 = b.asInteger()
        val r = Math.min(v1, v2)
        Rt_IntValue(r)
    }

    val Min_Decimal = C_SysFunction.simple2(Db_SysFunction.simple("min", "LEAST"), pure = true) { a, b ->
        val v1 = a.asDecimal()
        val v2 = b.asDecimal()
        val r = v1.min(v2)
        Rt_DecimalValue.of(r)
    }

    val Max_Integer = C_SysFunction.simple2(Db_SysFunction.simple("max", "GREATEST"), pure = true) { a, b ->
        val v1 = a.asInteger()
        val v2 = b.asInteger()
        val r = Math.max(v1, v2)
        Rt_IntValue(r)
    }

    val Max_Decimal = C_SysFunction.simple2(Db_SysFunction.simple("max", "GREATEST"), pure = true) { a, b ->
        val v1 = a.asDecimal()
        val v2 = b.asDecimal()
        val r = v1.max(v2)
        Rt_DecimalValue.of(r)
    }
}
