package net.postchain.rell.model

import net.postchain.rell.compiler.C_EntityAttrRef
import net.postchain.rell.compiler.C_Utils
import net.postchain.rell.runtime.*

class R_MemberExpr(val base: R_Expr, val safe: Boolean, val calculator: R_MemberCalculator)
: R_Expr(C_Utils.effectiveMemberType(calculator.type, safe))
{
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val baseValue = base.evaluate(frame)
        if (safe && baseValue == Rt_NullValue) {
            return Rt_NullValue
        }
        check(baseValue != Rt_NullValue)
        check(baseValue != Rt_UnitValue)
        val value = calculator.calculate(frame, baseValue)
        return value
    }
}

sealed class R_MemberCalculator(val type: R_Type) {
    abstract fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value
}

class R_MemberCalculator_TupleAttr(type: R_Type, val attrIndex: Int): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val values = baseValue.asTuple()
        return values[attrIndex]
    }
}

class R_MemberCalculator_VirtualTupleAttr(type: R_Type, val fieldIndex: Int): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val tuple = baseValue.asVirtualTuple()
        val res = tuple.get(fieldIndex)
        return res
    }
}

class R_MemberCalculator_StructAttr(val attr: R_Attribute): R_MemberCalculator(attr.type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val structValue = baseValue.asStruct()
        return structValue.get(attr.index)
    }
}

class R_MemberCalculator_VirtualStructAttr(type: R_Type, val attr: R_Attribute): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val structValue = baseValue.asVirtualStruct()
        return structValue.get(attr.index)
    }
}

class R_MemberCalculator_DataAttribute(
        type: R_Type,
        private val atBase: Db_AtExprBase,
        private val lambda: R_LambdaBlock
): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val list = lambda.execute(frame, baseValue) {
            atBase.execute(frame, Rt_AtExprExtras.NULL)
        }

        if (list.size != 1) {
            val msg = if (list.isEmpty()) {
                "Object not found in the database: $baseValue (was deleted?)"
            } else {
                "Found more than one object $baseValue in the database: ${list.size}"
            }
            throw Rt_Error("expr_entity_attr_count:${list.size}", msg)
        }

        check(list[0].size == 1)
        val res = list[0][0]
        return res
    }
}

class R_MemberCalculator_SysFn(type: R_Type, val fn: R_SysFunction, val args: List<R_Expr>): R_MemberCalculator(type) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val vArgs = args.map { it.evaluate(frame) }
        val vFullArgs = listOf(baseValue) + vArgs
        return fn.call(frame.defCtx.callCtx, vFullArgs)
    }
}

object R_MemberCalculator_Rowid: R_MemberCalculator(C_EntityAttrRef.ROWID_TYPE) {
    override fun calculate(frame: Rt_CallFrame, baseValue: Rt_Value): Rt_Value {
        val id = baseValue.asObjectId()
        return Rt_RowidValue(id)
    }
}
