package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_RExpr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.Nullable
import net.postchain.rell.utils.toImmList


class C_AtFrom_Iterable(
        outerExprCtx: C_ExprContext,
        fromCtx: C_AtFromContext,
        alias: S_Name?,
        private val item: C_AtFromItem_Iterable
): C_AtFrom(outerExprCtx, fromCtx) {
    private val pos = fromCtx.pos
    private val innerExprCtx: C_ExprContext
    private val placeholderVar: C_LocalVar
    private val varPtr: R_VarPtr

    init {
        placeholderVar = innerBlkCtx.newLocalVar(alias?.str ?: C_Constants.AT_PLACEHOLDER, item.elemType, false, atExprId)
        val phEntry = C_BlockEntry_Var(placeholderVar)

        if (alias == null) {
            innerBlkCtx.addAtPlaceholder(phEntry)
        } else {
            innerBlkCtx.addEntry(alias.pos, alias.str, true, phEntry)
        }

        varPtr = placeholderVar.toRef(innerBlkCtx.blockUid).ptr

        val factsCtx = outerExprCtx.factsCtx.sub(C_VarFacts.of(inited = mapOf(placeholderVar.uid to C_VarFact.YES)))
        innerExprCtx = outerExprCtx.update(blkCtx = innerBlkCtx, factsCtx = factsCtx, atCtx = Nullable(innerAtCtx))
    }

    override fun innerExprCtx() = innerExprCtx

    override fun makeDefaultWhat(): C_AtWhat {
        val vExpr = compilePlaceholderRef(pos)
        val field = C_AtWhatField(null, vExpr.type(), vExpr, C_AtWhatFieldFlags.DEFAULT, null)
        return C_AtWhat(listOf(field))
    }

    override fun findAttributesByName(name: String): List<C_AtFromContextAttr> {
        val memValue = C_MemberResolver.findMemberValueForTypeByName(item.elemType, name)
        memValue ?: return listOf()
        return listOf(C_AtFromContextAttr_ColAtMember(placeholderVar, memValue))
    }

    override fun findAttributesByType(type: R_Type): List<C_AtFromContextAttr> {
        val memValues = C_MemberResolver.findMemberValuesForTypeByType(item.elemType, type)
        return memValues.map { C_AtFromContextAttr_ColAtMember(placeholderVar, it) }
    }

    private fun compilePlaceholderRef(pos: S_Pos): V_Expr {
        val entry: C_BlockEntry = C_BlockEntry_Var(placeholderVar)
        return entry.compile(innerExprCtx, pos, false)
    }

    override fun compile(details: C_AtDetails): V_Expr {
        val rParam = R_VarParam(C_Constants.AT_PLACEHOLDER, item.elemType, varPtr)
        val rFrom = item.compile()
        val rWhere = details.base.where?.toRExpr() ?: R_ConstantExpr.makeBool(true)

        val rLimit = details.limit?.toRExpr()
        val rOffset = details.offset?.toRExpr()
        val extras = R_AtExprExtras(rLimit, rOffset)

        val what = compileWhat(details)
        val summarization = compileSummarization(details.res, what)

        val cBlock = innerBlkCtx.buildBlock()

        val rExpr = R_ColAtExpr(
                type = details.res.resultType,
                block = cBlock.rBlock,
                param = rParam,
                from = rFrom,
                what = what,
                where = rWhere,
                summarization = summarization,
                cardinality = details.cardinality,
                extras = extras
        )

        return V_RExpr(outerExprCtx, details.startPos, rExpr, details.exprFacts)
    }

    private fun compileWhat(details: C_AtDetails): R_ColAtWhat {
        val cFields = details.base.what.allFields
        val fields = cFields.map { compileField(it) }
        val sorting = compileSorting(cFields)
        return R_ColAtWhat(
                fields,
                details.res.selectedFields,
                details.res.groupFields,
                sorting,
                details.res.rowDecoder
        )
    }

    private fun compileSorting(cFields: List<C_AtWhatField>): List<IndexedValue<Comparator<Rt_Value>>> {
        val sorting = cFields
                .withIndex()
                .map { (i, f) ->
                    val sort = f.flags.sort
                    if (sort == null) null else {
                        val type = f.resultType
                        var comparator = type.comparator()
                        if (comparator != null && !sort.value.asc) comparator = comparator.reversed()
                        if (comparator != null) IndexedValue(i, comparator) else {
                            innerExprCtx.msgCtx.error(sort.pos, "at:expr:sort:type:${type}", "Type ${type} is not sortable")
                            null
                        }
                    }
                }
                .filterNotNull()
                .toImmList()
        return sorting
    }

    private fun compileField(cField: C_AtWhatField): R_ColAtWhatField {
        val rExpr = cField.expr.toRExpr()
        val summarization = if (cField.summarization == null) {
            R_ColAtFieldSummarization_None
        } else {
            cField.summarization.compileR(innerExprCtx.appCtx)
        }
        val rFlags = cField.flags.compile()
        return R_ColAtWhatField(rExpr, rFlags, summarization)
    }

    private fun compileSummarization(cResult: C_AtExprResult, rWhat: R_ColAtWhat): R_ColAtSummarization {
        return if (cResult.groupFields.isEmpty() && !cResult.hasAggregateFields) {
            R_ColAtSummarization_None(rWhat.fields.size)
        } else if (cResult.groupFields.isEmpty()) {
            R_ColAtSummarization_All(rWhat)
        } else {
            R_ColAtSummarization_Group(rWhat)
        }
    }
}

private class C_AtFromContextAttr_ColAtMember(
        private val itemVar: C_LocalVar,
        private val memberValue: C_MemberValue
): C_AtFromContextAttr(memberValue.type()) {
    override fun attrNameMsg(qualified: Boolean): C_CodeMsg {
        val memberName = memberValue.memberName()
        val base = if (itemVar.name != "$") itemVar.name else itemVar.type.name
        val code = memberName(memberName, base, qualified)
        val msg = memberName(memberName, base, qualified)
        return C_CodeMsg(code, msg)
    }

    private fun memberName(memberName: C_MemberName, base: String, qualified: Boolean): String {
        return if (qualified) memberName.qualifiedName(base) else memberName.simpleName()
    }

    override fun ownerTypeName() = itemVar.type.name

    override fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        val blockEntry: C_BlockEntry = C_BlockEntry_Var(itemVar)
        val base = blockEntry.compile(ctx, pos, false)
        val memLink = C_MemberLink(base, false, pos)
        return memberValue.compile(ctx, memLink)
    }
}
