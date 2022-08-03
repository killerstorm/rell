/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.lib.C_Lib_Math
import net.postchain.rell.model.R_DecimalType
import net.postchain.rell.model.R_IntegerType
import net.postchain.rell.model.R_TextType
import net.postchain.rell.model.expr.Db_SysFunction
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.immListOf
import java.math.BigDecimal

object C_Lib_Type_Integer: C_Lib_Type("integer", R_IntegerType) {
    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        b.add(typeName.str, type, listOf(R_TextType), IntFns.FromText_1)
        b.add(typeName.str, type, listOf(R_TextType, R_IntegerType), IntFns.FromText_2)
        b.add(typeName.str, type, listOf(R_DecimalType), C_Lib_Type_Decimal.ToInteger)
    }

    override fun bindConstants() = immListOf(
        C_LibUtils.constValue("MIN_VALUE", Long.MIN_VALUE),
        C_LibUtils.constValue("MAX_VALUE", Long.MAX_VALUE),
    )

    override fun bindStaticFunctions(b: C_GlobalFuncBuilder) {
        b.add("parseHex", R_IntegerType, listOf(R_TextType), IntFns.FromHex, depError("from_hex"))
        b.add("from_text", R_IntegerType, listOf(R_TextType), IntFns.FromText_1)
        b.add("from_text", R_IntegerType, listOf(R_TextType, R_IntegerType), IntFns.FromText_2)
        b.add("from_hex", R_IntegerType, listOf(R_TextType), IntFns.FromHex)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        b.add("abs", R_IntegerType, listOf(), C_Lib_Math.Abs_Integer)
        b.add("min", R_IntegerType, listOf(R_IntegerType), C_Lib_Math.Min_Integer)
        b.add("min", R_DecimalType, listOf(R_DecimalType), IntFns.Min_Decimal)
        b.add("max", R_IntegerType, listOf(R_IntegerType), C_Lib_Math.Max_Integer)
        b.add("max", R_DecimalType, listOf(R_DecimalType), IntFns.Max_Decimal)
        b.add("str", R_TextType, listOf(), IntFns.ToText_1)
        b.add("str", R_TextType, listOf(R_IntegerType), IntFns.ToText_2)
        b.add("hex", R_TextType, listOf(), IntFns.ToHex, depError("to_hex"))
        b.add("to_decimal", R_DecimalType, listOf(), C_Lib_Type_Decimal.FromInteger)
        b.add("to_text", R_TextType, listOf(), IntFns.ToText_1)
        b.add("to_text", R_TextType, listOf(R_IntegerType), IntFns.ToText_2)
        b.add("to_hex", R_TextType, listOf(), IntFns.ToHex)
        b.add("signum", R_IntegerType, listOf(), IntFns.Sign, depError("sign"))
        b.add("sign", R_IntegerType, listOf(), IntFns.Sign)
    }

    override fun bindAliases(b: C_SysNsProtoBuilder) {
        bindAlias(b, "timestamp")
    }
}

private object IntFns {
    val Min_Decimal = C_SysFunction.simple2(Db_SysFunction.simple("min", "LEAST"), pure = true) { a, b ->
        val v1 = a.asInteger()
        val v2 = b.asDecimal()
        val r = BigDecimal(v1).min(v2)
        Rt_DecimalValue.of(r)
    }

    val Max_Decimal = C_SysFunction.simple2(Db_SysFunction.simple("max", "GREATEST"), pure = true) { a, b ->
        val v1 = a.asInteger()
        val v2 = b.asDecimal()
        val r = BigDecimal(v1).max(v2)
        Rt_DecimalValue.of(r)
    }

    val ToText_1 = C_SysFunction.simple1(Db_SysFunction.cast("int.to_text", "TEXT"), pure = true) { a ->
        val v = a.asInteger()
        Rt_TextValue(v.toString())
    }

    val ToText_2 = C_SysFunction.simple2(pure = true) { a, b ->
        val v = a.asInteger()
        val r = b.asInteger()
        if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
            throw Rt_Error("fn_int_str_radix:$r", "Invalid radix: $r")
        }
        val s = v.toString(r.toInt())
        Rt_TextValue(s)
    }

    val ToHex = C_SysFunction.simple1(pure = true) { a ->
        val v = a.asInteger()
        Rt_TextValue(java.lang.Long.toHexString(v))
    }

    val Sign = C_SysFunction.simple1(Db_SysFunction.simple("sign", "SIGN"), pure = true) { a ->
        val v = a.asInteger()
        val r = java.lang.Long.signum(v).toLong()
        Rt_IntValue(r)
    }

    val FromText_1 = C_SysFunction.simple1(pure = true) { a ->
        calcFromText(a, 10)
    }

    val FromText_2 = C_SysFunction.simple2(pure = true) { a, b ->
        val r = b.asInteger()
        if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
            throw Rt_Error("fn:integer.from_text:radix:$r", "Invalid radix: $r")
        }
        calcFromText(a, r.toInt())
    }

    private fun calcFromText(a: Rt_Value, radix: Int): Rt_Value {
        val s = a.asString()
        val r = try {
            java.lang.Long.parseLong(s, radix)
        } catch (e: NumberFormatException) {
            throw Rt_Error("fn:integer.from_text:$s", "Invalid number: '$s'")
        }
        return Rt_IntValue(r)
    }

    val FromHex = C_SysFunction.simple1(pure = true) { a ->
        val s = a.asString()
        val r = try {
            java.lang.Long.parseUnsignedLong(s, 16)
        } catch (e: NumberFormatException) {
            throw Rt_Error("fn:integer.from_hex:$s", "Invalid hex number: '$s'")
        }
        Rt_IntValue(r)
    }
}
