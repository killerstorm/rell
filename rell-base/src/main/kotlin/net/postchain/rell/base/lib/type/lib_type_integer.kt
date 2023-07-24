/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.lib.Lib_Math
import net.postchain.rell.base.lmodel.L_ParamArity
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_IntegerType
import net.postchain.rell.base.runtime.*
import java.math.BigDecimal
import java.math.BigInteger

object Lib_Type_Integer {
    val NAMESPACE = Ld_NamespaceDsl.make {
        type("integer", rType = R_IntegerType) {
            alias("timestamp")

            constant("MIN_VALUE", Long.MIN_VALUE)
            constant("MAX_VALUE", Long.MAX_VALUE)

            constructor(pure = true) {
                param("text")
                param("integer", arity = L_ParamArity.ZERO_ONE)
                bodyOpt1 { a, b ->
                    val r = b?.asInteger() ?: 10
                    calcFromText(a, r)
                }
            }

            constructor {
                param("decimal")
                bodyFunction(Lib_Type_Decimal.ToInteger)
            }

            staticFunction("from_text", "integer", pure = true) {
                param("text")
                param("integer", arity = L_ParamArity.ZERO_ONE)
                bodyOpt1 { a, b ->
                    val r = b?.asInteger() ?: 10
                    calcFromText(a, r)
                }
            }

            staticFunction("from_hex", "integer", pure = true) {
                alias("parseHex", C_MessageType.ERROR)
                param("text")
                body { a ->
                    val s = a.asString()
                    val r = try {
                        java.lang.Long.parseUnsignedLong(s, 16)
                    } catch (e: NumberFormatException) {
                        throw Rt_Exception.common("fn:integer.from_hex:$s", "Invalid hex number: '$s'")
                    }
                    Rt_IntValue(r)
                }
            }

            function("abs", "integer") {
                bodyFunction(Lib_Math.Abs_Integer)
            }

            function("min", "integer") {
                param("integer")
                bodyFunction(Lib_Math.Min_Integer)
            }

            function("min", "big_integer", pure = true) {
                param("big_integer")
                dbFunctionSimple("min", "LEAST")
                body { a, b ->
                    val v1 = a.asInteger()
                    val v2 = b.asBigInteger()
                    val r = BigInteger.valueOf(v1).min(v2)
                    Rt_BigIntegerValue.of(r)
                }
            }

            function("min", "decimal", pure = true) {
                param("decimal")
                dbFunctionSimple("min", "LEAST")
                body { a, b ->
                    val v1 = a.asInteger()
                    val v2 = b.asDecimal()
                    val r = BigDecimal(v1).min(v2)
                    Rt_DecimalValue.of(r)
                }
            }

            function("max", "integer") {
                param("integer")
                bodyFunction(Lib_Math.Max_Integer)
            }

            function("max", "big_integer", pure = true) {
                param("big_integer")
                dbFunctionSimple("max", "GREATEST")
                body { a, b ->
                    val v1 = a.asInteger()
                    val v2 = b.asBigInteger()
                    val r = BigInteger.valueOf(v1).max(v2)
                    Rt_BigIntegerValue.of(r)
                }
            }

            function("max", "decimal", pure = true) {
                param("decimal")
                dbFunctionSimple("max", "GREATEST")
                body { a, b ->
                    val v1 = a.asInteger()
                    val v2 = b.asDecimal()
                    val r = BigDecimal(v1).max(v2)
                    Rt_DecimalValue.of(r)
                }
            }

            function("to_big_integer", "big_integer") {
                bodyFunction(Lib_Type_BigInteger.FromInteger)
            }

            function("to_decimal", "decimal") {
                bodyFunction(Lib_Type_Decimal.FromInteger)
            }

            function("to_text", "text", pure = true) {
                alias("str")
                dbFunctionCast("int.to_text", "TEXT")
                body { a ->
                    val v = a.asInteger()
                    Rt_TextValue(v.toString())
                }
            }

            function("to_text", "text", pure = true) {
                alias("str")
                param("integer")
                body { a, b ->
                    val v = a.asInteger()
                    val r = b.asInteger()
                    if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
                        throw Rt_Exception.common("fn_int_str_radix:$r", "Invalid radix: $r")
                    }
                    val s = v.toString(r.toInt())
                    Rt_TextValue(s)
                }
            }

            function("to_hex", "text", pure = true) {
                alias("hex", C_MessageType.ERROR)
                body { a ->
                    val v = a.asInteger()
                    Rt_TextValue(java.lang.Long.toHexString(v))
                }
            }

            function("sign", "integer", pure = true) {
                alias("signum", C_MessageType.ERROR)
                dbFunctionSimple("sign", "SIGN")
                body { a ->
                    val v = a.asInteger()
                    val r = java.lang.Long.signum(v).toLong()
                    Rt_IntValue(r)
                }
            }
        }
    }

    private fun calcFromText(a: Rt_Value, radix: Long): Rt_Value {
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            throw Rt_Exception.common("fn:integer.from_text:radix:$radix", "Invalid radix: $radix")
        }

        val s = a.asString()
        val r = try {
            java.lang.Long.parseLong(s, radix.toInt())
        } catch (e: NumberFormatException) {
            throw Rt_Exception.common("fn:integer.from_text:$s", "Invalid number: '$s'")
        }

        return Rt_IntValue(r)
    }
}
