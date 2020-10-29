package net.postchain.rell.model

import net.postchain.rell.runtime.*

class R_ColAtExpr(
        type: R_Type,
        val block: R_FrameBlock,
        val param: R_VarParam,
        val from: R_Expr,
        val what: R_Expr,
        val where: R_Expr,
        cardinality: R_AtCardinality,
        limit: R_Expr?,
        offset: R_Expr?
): R_AtExpr(type, cardinality, limit, offset) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val resList = evalList(frame)
        val res = evalResult(resList)
        return res
    }

    private fun evalList(frame: Rt_CallFrame): MutableList<Rt_Value> {
        val resList = mutableListOf<Rt_Value>()

        val limit = evalLimit(frame)
        if (limit != null && limit <= 0L) return resList

        val collection = from.evaluate(frame).asCollection()
        val offset = evalOffset(frame)
        val maxCount = getMaxCount(limit)

        frame.block(block) {
            var pos = 0L

            for (item in collection) {
                frame.set(param.ptr, param.type, item, true)
                val whereValue = where.evaluate(frame)
                if (!whereValue.asBoolean()) continue

                if (offset != null && pos < offset) {
                    pos += 1
                    continue
                }

                val whatValue = what.evaluate(frame)
                resList.add(whatValue)
                if (resList.size >= maxCount) break

                pos += 1
            }
        }

        checkCount(cardinality, resList.size, !cardinality.many && resList.size == 2, "values")
        return resList
    }

    private fun getMaxCount(limit: Long?): Long {
        val realLimit = limit ?: Long.MAX_VALUE
        return if (cardinality.many) realLimit else Math.min(realLimit, 2)
    }
}
