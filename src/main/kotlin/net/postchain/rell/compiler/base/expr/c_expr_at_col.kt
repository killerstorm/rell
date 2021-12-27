/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_BlockEntry
import net.postchain.rell.compiler.base.core.C_BlockEntry_Var
import net.postchain.rell.compiler.base.core.C_LocalVar
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.base.utils.C_Constants
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.R_VarParam
import net.postchain.rell.model.R_VarPtr
import net.postchain.rell.model.expr.R_ColAtFieldSummarization_None
import net.postchain.rell.model.expr.R_ColAtWhatExtras
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList


class C_AtFrom_Iterable(
        outerExprCtx: C_ExprContext,
        fromCtx: C_AtFromContext,
        alias: C_Name?,
        private val item: C_AtFromItem_Iterable
): C_AtFrom(outerExprCtx, fromCtx) {
    private val pos = fromCtx.pos
    private val placeholderIdeInfo = IdeSymbolInfo(IdeSymbolKind.LOC_AT_ALIAS)

    private val placeholderVar: C_LocalVar = let {
        val metaName = alias?.str ?: C_Constants.AT_PLACEHOLDER
        innerBlkCtx.newLocalVar(metaName, alias?.rName, item.elemType, false, atExprId)
    }

    private val varPtr: R_VarPtr = let {
        val phEntry = C_BlockEntry_Var(placeholderVar, placeholderIdeInfo)
        if (alias == null) {
            innerBlkCtx.addAtPlaceholder(phEntry)
        } else {
            innerBlkCtx.addEntry(alias.pos, alias.rName, true, phEntry)
        }
        placeholderVar.toRef(innerBlkCtx.blockUid).ptr
    }

    private val innerExprCtx: C_ExprContext = let {
        val factsCtx = outerExprCtx.factsCtx.sub(C_VarFacts.of(inited = mapOf(placeholderVar.uid to C_VarFact.YES)))
        outerExprCtx.update(blkCtx = innerBlkCtx, factsCtx = factsCtx, atCtx = innerAtCtx)
    }

    override fun innerExprCtx() = innerExprCtx

    override fun makeDefaultWhat(): V_DbAtWhat {
        val vExpr = compilePlaceholderRef(pos)
        val field = V_DbAtWhatField(outerExprCtx.appCtx, null, vExpr.type, vExpr, V_AtWhatFieldFlags.DEFAULT, null)
        return V_DbAtWhat(listOf(field))
    }

    override fun findAttributesByName(name: R_Name): List<C_AtFromContextAttr> {
        val memValue = C_MemberResolver.findMemberValueForTypeByName(outerExprCtx.globalCtx, item.elemType, name)
        memValue ?: return immListOf()
        return immListOf(C_AtFromContextAttr_ColAtMember(placeholderVar, memValue))
    }

    override fun findAttributesByType(type: R_Type): List<C_AtFromContextAttr> {
        val memValues = C_MemberResolver.findMemberValuesForTypeByType(outerExprCtx.globalCtx, item.elemType, type)
        return memValues.map { C_AtFromContextAttr_ColAtMember(placeholderVar, it) }
    }

    private fun compilePlaceholderRef(pos: S_Pos): V_Expr {
        val entry: C_BlockEntry = C_BlockEntry_Var(placeholderVar, placeholderIdeInfo)
        return entry.compile(innerExprCtx, pos, false)
    }

    override fun compile(details: C_AtDetails): V_Expr {
        val rParam = R_VarParam(C_Constants.AT_PLACEHOLDER, item.elemType, varPtr)
        val rFrom = item.compile()
        val what = compileWhat(details)
        val extras = V_AtExprExtras(details.limit, details.offset)

        val cBlock = innerBlkCtx.buildBlock()

        return V_ColAtExpr(
                outerExprCtx,
                details.startPos,
                result = details.res,
                from = rFrom,
                what = what,
                where = details.base.where,
                cardinality = details.cardinality.value,
                extras = extras,
                block = cBlock.rBlock,
                param = rParam,
                resVarFacts = details.exprFacts
        )
    }

    private fun compileWhat(details: C_AtDetails): V_ColAtWhat {
        val cFields = details.base.what.allFields
        val fields = cFields.map { compileField(it) }
        val sorting = compileSorting(cFields)

        val extras = R_ColAtWhatExtras(
                fields.size,
                details.res.selectedFields,
                details.res.groupFields,
                sorting,
                details.res.rowDecoder
        )

        return V_ColAtWhat(fields, extras)
    }

    private fun compileSorting(cFields: List<V_DbAtWhatField>): List<IndexedValue<Comparator<Rt_Value>>> {
        val sorting = cFields
                .withIndex()
                .mapNotNull { (i, f) ->
                    val sort = f.flags.sort
                    if (sort == null) null else {
                        val type = f.resultType
                        var comparator = type.comparator()
                        if (comparator != null && !sort.value.asc) comparator = comparator.reversed()
                        if (comparator != null) IndexedValue(i, comparator) else {
                            innerExprCtx.msgCtx.error(sort.pos, "at:expr:sort:type:${type.strCode()}",
                                    "Type ${type.str()} is not sortable")
                            null
                        }
                    }
                }
                .toImmList()
        return sorting
    }

    private fun compileField(cField: V_DbAtWhatField): V_ColAtWhatField {
        val summarization = if (cField.summarization == null) {
            R_ColAtFieldSummarization_None
        } else {
            cField.summarization.compileR(innerExprCtx.appCtx)
        }
        val rFlags = cField.flags.compile()
        return V_ColAtWhatField(cField.expr, rFlags, summarization)
    }
}

private class C_AtFromContextAttr_ColAtMember(
        private val itemVar: C_LocalVar,
        private val memberValue: C_MemberValue
): C_AtFromContextAttr(memberValue.type()) {
    override fun attrNameMsg(qualified: Boolean): C_CodeMsg {
        val memberName = memberValue.memberName()
        val base = if (itemVar.rName != null) itemVar.rName.str else itemVar.type.name
        val code = memberName(memberName, base, qualified)
        val msg = memberName(memberName, base, qualified)
        return C_CodeMsg(code, msg)
    }

    private fun memberName(memberName: C_MemberName, base: String, qualified: Boolean): String {
        return if (qualified) memberName.qualifiedName(base) else memberName.simpleName()
    }

    override fun ownerTypeName() = itemVar.type.name
    override fun ideSymbolInfo() = memberValue.ideSymbolInfo()

    override fun compile(ctx: C_ExprContext, pos: S_Pos): V_Expr {
        val ideInfo = ideSymbolInfo()
        val blockEntry: C_BlockEntry = C_BlockEntry_Var(itemVar, ideInfo)
        val base = blockEntry.compile(ctx, pos, false)
        val memLink = C_MemberLink(base, false, pos)
        return memberValue.compile(ctx, memLink)
    }
}
