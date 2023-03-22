/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_BlockEntry_Var
import net.postchain.rell.compiler.base.core.C_LocalVar
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.base.utils.C_Constants
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.R_VarPtr
import net.postchain.rell.model.expr.R_ColAtFieldSummarization_None
import net.postchain.rell.model.expr.R_ColAtParam
import net.postchain.rell.model.expr.R_ColAtWhatExtras
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.tools.api.IdeLocalSymbolLink
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.toImmList


class C_AtFrom_Iterable(
        outerExprCtx: C_ExprContext,
        fromCtx: C_AtFromContext,
        alias: C_Name?,
        private val item: C_AtFromItem_Iterable
): C_AtFrom(outerExprCtx, fromCtx) {
    private val pos = fromCtx.pos

    private val placeholderVar: C_LocalVar = let {
        val metaName = alias?.str ?: C_Constants.AT_PLACEHOLDER
        innerBlkCtx.newLocalVar(metaName, alias?.rName, item.elemType, false, atExprId)
    }

    private val varPtr: R_VarPtr = let {
        val ideInfo = IdeSymbolInfo(IdeSymbolKind.LOC_AT_ALIAS, link = IdeLocalSymbolLink(alias?.pos ?: item.pos))
        val phEntry = C_BlockEntry_Var(placeholderVar, ideInfo)
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

    override fun findMembers(name: R_Name): List<C_AtFromMember> {
        val base = C_AtFromBase_Iterable()
        val members = item.elemType.getValueMembers(name)
        return members.map { C_AtFromMember(base, it) }.toImmList()
    }

    override fun findImplicitAttributesByName(name: R_Name): List<C_AtFromImplicitAttr> {
        val base = C_AtFromBase_Iterable()
        val members = item.elemType.getAtImplicitAttrs(name)
        return members
            .map { C_AtFromImplicitAttr(base, it) }
            .toImmList()
    }

    override fun findImplicitAttributesByType(type: R_Type): List<C_AtFromImplicitAttr> {
        val base = C_AtFromBase_Iterable()
        return item.elemType.getAtImplicitAttrs(type)
            .map { C_AtFromImplicitAttr(base, it) }
            .toImmList()
    }

    private fun compilePlaceholderRef(pos: S_Pos): V_Expr {
        return C_BlockEntry_Var.compile0(innerExprCtx, pos, placeholderVar)
    }

    override fun compile(details: C_AtDetails): V_Expr {
        val rParam = R_ColAtParam(item.elemType, varPtr)
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

    private inner class C_AtFromBase_Iterable: C_AtFromBase() {
        override fun nameMsg(): C_CodeMsg {
            return "${placeholderVar.metaName}:${item.elemType.name}" toCodeMsg placeholderVar.metaName
        }

        override fun compile(pos: S_Pos): V_Expr {
            return compilePlaceholderRef(pos)
        }
    }
}
