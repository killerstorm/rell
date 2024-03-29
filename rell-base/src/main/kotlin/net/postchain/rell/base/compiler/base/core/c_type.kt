/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.core

import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.utils.C_CodeMsgSupplier
import net.postchain.rell.base.compiler.base.utils.C_Error
import net.postchain.rell.base.compiler.base.utils.C_Errors
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.compiler.vexpr.V_TypeAdapterExpr
import net.postchain.rell.base.lib.type.Lib_Type_BigInteger
import net.postchain.rell.base.lib.type.Lib_Type_Decimal
import net.postchain.rell.base.lmodel.L_TypeUtils
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.*
import net.postchain.rell.base.mtype.M_Type
import net.postchain.rell.base.mtype.M_Type_Function
import net.postchain.rell.base.mtype.M_Type_Tuple
import net.postchain.rell.base.mtype.M_Types
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.immSetOf
import net.postchain.rell.base.utils.toImmSet

class C_TypeHint private constructor(val mTypes: Set<M_Type>) {
    fun getFunctionType(): R_FunctionType? {
        return mTypes
            .mapNotNull { it as? M_Type_Function }
            .mapNotNull { L_TypeUtils.getRType(it) as? R_FunctionType }
            .singleOrNull()
    }

    fun getTupleFieldHint(index: Int): C_TypeHint {
        check(index >= 0) { index }
        val mResTypes = mTypes
            .mapNotNull { it as? M_Type_Tuple }
            .mapNotNull { it.fieldTypes.getOrNull(index) }
            .filter { it != M_Types.NOTHING }
        return ofTypes(mResTypes)
    }

    companion object {
        val NONE: C_TypeHint = C_TypeHint(immSetOf())

        fun ofTypes(types: List<M_Type>): C_TypeHint {
            return if (types.isEmpty()) NONE else C_TypeHint(types.toImmSet())
        }

        fun ofType(mType: M_Type): C_TypeHint {
            return ofTypes(immListOf(mType))
        }

        fun ofType(type: R_Type?): C_TypeHint {
            type ?: return NONE
            return ofType(type.mType)
        }

        fun combined(hints: List<C_TypeHint>): C_TypeHint {
            val mTypes = hints.flatMap { it.mTypes }.toImmSet()
            return C_TypeHint(mTypes)
        }
    }
}

sealed class C_TypeAdapter {
    abstract fun adaptExpr(ctx: C_ExprContext, expr: V_Expr): V_Expr
    abstract fun adaptExprR(expr: R_Expr): R_Expr
    abstract fun adaptExprDb(expr: Db_Expr): Db_Expr
    abstract fun toRAdapter(): R_TypeAdapter
}

object C_TypeAdapter_Direct: C_TypeAdapter() {
    override fun adaptExpr(ctx: C_ExprContext, expr: V_Expr) = expr
    override fun adaptExprR(expr: R_Expr) = expr
    override fun adaptExprDb(expr: Db_Expr) = expr
    override fun toRAdapter(): R_TypeAdapter = R_TypeAdapter_Direct
}

object C_TypeAdapter_IntegerToBigInteger: C_TypeAdapter() {
    override fun adaptExpr(ctx: C_ExprContext, expr: V_Expr): V_Expr {
        return V_TypeAdapterExpr(ctx, R_BigIntegerType, expr, this)
    }

    override fun adaptExprR(expr: R_Expr): R_Expr {
        return R_TypeAdapterExpr(R_BigIntegerType, expr, toRAdapter())
    }

    override fun adaptExprDb(expr: Db_Expr): Db_Expr {
        return Db_CallExpr(R_BigIntegerType, Lib_Type_BigInteger.FromInteger_Db, listOf(expr))
    }

    override fun toRAdapter(): R_TypeAdapter = R_TypeAdapter_IntegerToBigInteger
}

object C_TypeAdapter_IntegerToDecimal: C_TypeAdapter() {
    override fun adaptExpr(ctx: C_ExprContext, expr: V_Expr): V_Expr {
        return V_TypeAdapterExpr(ctx, R_DecimalType, expr, this)
    }

    override fun adaptExprR(expr: R_Expr): R_Expr {
        return R_TypeAdapterExpr(R_DecimalType, expr, toRAdapter())
    }

    override fun adaptExprDb(expr: Db_Expr): Db_Expr {
        return Db_CallExpr(R_DecimalType, Lib_Type_Decimal.FromInteger_Db, listOf(expr))
    }

    override fun toRAdapter(): R_TypeAdapter = R_TypeAdapter_IntegerToDecimal
}

object C_TypeAdapter_BigIntegerToDecimal: C_TypeAdapter() {
    override fun adaptExpr(ctx: C_ExprContext, expr: V_Expr): V_Expr {
        return V_TypeAdapterExpr(ctx, R_DecimalType, expr, this)
    }

    override fun adaptExprR(expr: R_Expr): R_Expr {
        return R_TypeAdapterExpr(R_DecimalType, expr, toRAdapter())
    }

    override fun adaptExprDb(expr: Db_Expr): Db_Expr {
        return Db_CallExpr(R_DecimalType, Lib_Type_Decimal.FromBigInteger_Db, listOf(expr))
    }

    override fun toRAdapter(): R_TypeAdapter = R_TypeAdapter_BigIntegerToDecimal
}

class C_TypeAdapter_Nullable(private val dstType: R_Type, private val innerAdapter: C_TypeAdapter): C_TypeAdapter() {
    override fun adaptExpr(ctx: C_ExprContext, expr: V_Expr): V_Expr {
        return V_TypeAdapterExpr(ctx, dstType, expr, this)
    }

    override fun adaptExprR(expr: R_Expr): R_Expr {
        val rAdapter = toRAdapter()
        return R_TypeAdapterExpr(dstType, expr, rAdapter)
    }

    override fun adaptExprDb(expr: Db_Expr): Db_Expr {
        // Not completely right, but Db_Exprs do not support nullable anyway.
        return expr
    }

    override fun toRAdapter(): R_TypeAdapter {
        val rInnerAdapter = innerAdapter.toRAdapter()
        return R_TypeAdapter_Nullable(rInnerAdapter)
    }
}

object C_Types {
    fun match(dstType: R_Type, srcType: R_Type, errPos: S_Pos, errSupplier: C_CodeMsgSupplier) {
        val err = match0(dstType, srcType, errPos, errSupplier)
        if (err != null) {
            throw err
        }
    }

    fun matchOpt(
            msgCtx: C_MessageContext,
            dstType: R_Type,
            srcType: R_Type,
            errPos: S_Pos,
            errSupplier: C_CodeMsgSupplier
    ): Boolean {
        val err = match0(dstType, srcType, errPos, errSupplier)
        return if (err != null) {
            msgCtx.error(err)
            false
        } else {
            true
        }
    }

    private fun match0(dstType: R_Type, srcType: R_Type, errPos: S_Pos, errSupplier: C_CodeMsgSupplier): C_Error? {
        return if (dstType.isNotError() && srcType.isNotError() && !dstType.isAssignableFrom(srcType)) {
            C_Errors.errTypeMismatch(errPos, srcType, dstType, errSupplier)
        } else {
            null
        }
    }

    fun adapt(dstType: R_Type, srcType: R_Type, errPos: S_Pos, errSupplier: C_CodeMsgSupplier): C_TypeAdapter {
        val adapter = dstType.getTypeAdapter(srcType)
        if (adapter == null) {
            throw C_Errors.errTypeMismatch(errPos, srcType, dstType, errSupplier)
        }
        return adapter
    }

    fun adaptSafe(
            msgCtx: C_MessageContext,
            dstType: R_Type,
            srcType: R_Type,
            errPos: S_Pos,
            errSupplier: C_CodeMsgSupplier
    ): C_TypeAdapter {
        val adapter = dstType.getTypeAdapter(srcType)
        return if (adapter != null) adapter else {
            C_Errors.errTypeMismatch(msgCtx, errPos, srcType, dstType, errSupplier)
            C_TypeAdapter_Direct
        }
    }

    fun checkNotUnit(
            msgCtx: C_MessageContext,
            pos: S_Pos,
            type: R_Type,
            name: String?,
            kindSupplier: C_CodeMsgSupplier
    ): R_Type {
        if (type != R_UnitType) return type
        val kind = kindSupplier()
        val nameCode = name ?: "?"
        msgCtx.error(pos, "type:${kind.code}:unit:$nameCode", "Type of ${kind.msg} cannot be ${type.str()}")
        return R_CtErrorType
    }

    fun commonType(a: R_Type, b: R_Type, errPos: S_Pos, errSupplier: C_CodeMsgSupplier): R_Type {
        val res = commonTypeOpt(a, b)
        return res ?: throw C_Errors.errTypeMismatch(errPos, b, a, errSupplier)
    }

    fun commonTypeOpt(a: R_Type, b: R_Type): R_Type? {
        return R_Type.commonTypeOpt(a, b)
    }

    fun commonTypesOpt(a: R_MapKeyValueTypes, b: R_MapKeyValueTypes): R_MapKeyValueTypes? {
        val key = commonTypeOpt(a.key, b.key)
        key ?: return null
        val value = commonTypeOpt(a.value, b.value)
        return if (value == null) null else R_MapKeyValueTypes(key, value)
    }

    fun toNullable(type: R_Type): R_Type {
        return if (type is R_NullableType || type == R_NullType || type.isError()) type else R_NullableType(type)
    }

    fun removeNullable(type: R_Type): R_Type {
        return if (type is R_NullableType) type.valueType else type
    }
}
