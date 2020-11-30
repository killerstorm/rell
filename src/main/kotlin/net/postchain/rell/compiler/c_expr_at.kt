package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.V_AtPlaceholderExpr
import net.postchain.rell.compiler.vexpr.V_DbExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_RExpr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.toImmList

class C_AtEntity(val pos: S_Pos, val rEntity: R_Entity, val alias: String, val explicitAlias: Boolean, val index: Int) {
    private val rAtEntity = R_DbAtEntity(rEntity, index)

    fun compile() = rAtEntity
    fun compileExpr() = Db_EntityExpr(rAtEntity)
}

sealed class C_AtFrom(private val outerExprCtx: C_ExprContext) {
    abstract fun innerExprCtx(): C_ExprContext
    abstract fun makeDefaultWhat(): C_AtWhat
    abstract fun findDefinitionByAlias(alias: S_Name): C_NameResolution?
    abstract fun findAttributesByName(name: S_Name): List<C_ExprContextAttr>
    abstract fun findAttributesByType(type: R_Type): List<C_ExprContextAttr>
    abstract fun compile(startPos: S_Pos, resType: R_Type, cardinality: R_AtCardinality, details: C_AtDetails): V_Expr

    abstract fun hasPlaceholder(): Boolean
    protected abstract fun resolvePlaceholder0(pos: S_Pos): V_Expr

    fun resolvePlaceholder(pos: S_Pos): V_Expr {
        if (hasPlaceholder() && outerExprCtx.nameCtx.hasPlaceholder()) {
            throw C_Error(pos, "expr:placeholder:ambiguous", "Placeholder is ambiguous, use an alias")
        }
        return resolvePlaceholder0(pos)
    }
}

class C_AtFrom_Entities(outerExprCtx: C_ExprContext, entities: List<C_AtEntity>): C_AtFrom(outerExprCtx) {
    val entities = entities.toImmList()

    private val innerExprCtx = outerExprCtx.update(nameCtx = C_NameContext.createAt(outerExprCtx.blkCtx, this))

    override fun innerExprCtx() = innerExprCtx

    override fun makeDefaultWhat(): C_AtWhat {
        val fields = entities.map {
            val name = if (entities.size == 1) null else it.alias
            val dbExpr = it.compileExpr()
            val vExpr = V_DbExpr.create(it.pos, dbExpr)
            C_AtWhatField(name, vExpr.type(), vExpr, R_AtWhatFieldFlags.DEFAULT, null)
        }
        return C_AtWhat(fields)
    }

    override fun hasPlaceholder(): Boolean {
        return entities.size == 1 && entities.none { it.explicitAlias }
    }

    override fun resolvePlaceholder0(pos: S_Pos): V_Expr {
        if (entities.size > 1) {
            throw C_Error(pos, "expr:placeholder:multiple_entities:${entities.size}",
                    "Cannot use a placeholder when there are multiple entities")
        } else if (entities.any { it.explicitAlias }) {
            throw C_Error(pos, "expr:placeholder:alias",
                    "Cannot use a placeholder when there is an entity alias")
        }

        val entity = entities[0]
        return C_NameResolution_Entity.createVExpr(entity, pos)
    }

    override fun findDefinitionByAlias(alias: S_Name): C_NameResolution? {
        //TODO use a lookup table
        for (entity in entities) {
            if (entity.alias == alias.str) {
                return C_NameResolution_Entity(alias, entity)
            }
        }
        return null
    }

    override fun findAttributesByName(name: S_Name): List<C_ExprContextAttr> {
        return findContextAttrs { rEntity ->
            val attrRef = C_EntityAttrRef.resolveByName(rEntity, name.str)
            if (attrRef == null) listOf() else listOf(attrRef)
        }
    }

    override fun findAttributesByType(type: R_Type): List<C_ExprContextAttr> {
        return findContextAttrs { rEntity ->
            C_EntityAttrRef.resolveByType(rEntity, type)
        }
    }

    private fun findContextAttrs(resolver: (R_Entity) -> List<C_EntityAttrRef>): List<C_ExprContextAttr> {
        val attrs = mutableListOf<C_ExprContextAttr>()

        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (entity in entities) {
            val entityAttrs = resolver(entity.rEntity)
            val ctxAttrs = entityAttrs.map { C_ExprContextAttr_DbAtEntity(entity, it) }
            attrs.addAll(ctxAttrs)
        }

        return attrs.toImmList()
    }

    override fun compile(startPos: S_Pos, resType: R_Type, cardinality: R_AtCardinality, details: C_AtDetails): V_Expr {
        val cFrom = details.base.from as C_AtFrom_Entities
        val rFrom = cFrom.entities.map { it.compile() }

        val rWhat = details.base.what.fields.filter { !isIgnoredField(it) }.map {
            var dbExpr = it.expr.toDbExpr()
            if (it.summarization != null) {
                dbExpr = it.summarization.compileDb(innerExprCtx.nsCtx, dbExpr)
            }
            R_DbAtWhatField(it.resultType, dbExpr, it.flags)
        }

        val rBase = R_DbAtExprBase(rFrom, rWhat, details.base.where?.toDbExpr())
        val rLimit = details.limit?.toRExpr()
        val rOffset = details.offset?.toRExpr()
        val rowDecoder = details.res.rowDecoder
        val rExpr = R_DbAtExpr(resType, rBase, cardinality, rLimit, rOffset, rowDecoder)

        return V_RExpr(startPos, rExpr, details.exprFacts)
    }

    private fun isIgnoredField(field: C_AtWhatField) =
            field.flags.omit && field.flags.sort == null && field.summarization == null
}

class C_AtFrom_Iterable(
        outerExprCtx: C_ExprContext,
        private val pos: S_Pos,
        alias: S_Name?,
        private val item: C_AtFromItem_Iterable
): C_AtFrom(outerExprCtx) {
    private val innerBlkCtx = outerExprCtx.blkCtx.createSubContext("@")

    private val innerExprCtx: C_ExprContext
    private val varPtr: R_VarPtr
    private val hasPlaceholder: Boolean

    init {
        var factsCtx = outerExprCtx.factsCtx

        if (alias == null) {
            varPtr = innerBlkCtx.addPlaceholder(item.elemType)
            hasPlaceholder = true
        } else {
            val cVar = innerBlkCtx.addLocalVar(alias, item.elemType, false)
            factsCtx = factsCtx.sub(C_VarFacts.of(inited = mapOf(cVar.uid to C_VarFact.YES)))
            varPtr = cVar.ptr
            hasPlaceholder = false
        }

        innerExprCtx = outerExprCtx.update(
                blkCtx = innerBlkCtx,
                nameCtx = C_NameContext.createAt(innerBlkCtx, this),
                factsCtx = factsCtx
        )
    }

    override fun innerExprCtx() = innerExprCtx

    override fun makeDefaultWhat(): C_AtWhat {
        val vExpr = V_AtPlaceholderExpr(pos, item.elemType, varPtr)
        val field = C_AtWhatField(null, vExpr.type(), vExpr, R_AtWhatFieldFlags.DEFAULT, null)
        return C_AtWhat(listOf(field))
    }

    override fun hasPlaceholder() = hasPlaceholder

    override fun resolvePlaceholder0(pos: S_Pos): V_Expr {
        val ph = innerBlkCtx.lookupPlaceholder()
        if (ph == null) {
            throw C_Error(pos, "expr:placeholder:none", "Placeholder not defined")
        }
        return V_AtPlaceholderExpr(pos, item.elemType, ph)
    }

    override fun findDefinitionByAlias(alias: S_Name): C_NameResolution? {
        // The alias is added to the inner block scope.
        return null
    }

    override fun findAttributesByName(name: S_Name): List<C_ExprContextAttr> {
        val memValue = C_MemberResolver.findMemberValueForType(item.elemType, name.str)
        memValue ?: return listOf()

        val base = V_AtPlaceholderExpr(name.pos, item.elemType, varPtr)
        val memRef = C_MemberRef(name.pos, base, name, false)
        return listOf(C_ExprContextAttr_ColAtMember(innerExprCtx, memValue, memRef))
    }

    override fun findAttributesByType(type: R_Type): List<C_ExprContextAttr> {
        return listOf()
    }

    override fun compile(startPos: S_Pos, resType: R_Type, cardinality: R_AtCardinality, details: C_AtDetails): V_Expr {
        checkConstraints(startPos)

        val cBlock = innerBlkCtx.buildBlock()
        val rParam = R_VarParam(C_Constants.AT_PLACEHOLDER, item.elemType, varPtr)
        val rFrom = item.compile()
        val rWhere = details.base.where?.toRExpr() ?: R_ConstantExpr.makeBool(true)
        val rLimit = details.limit?.toRExpr()
        val rOffset = details.offset?.toRExpr()

        val what = compileWhat(details)
        val summarization = compileSummarization(details.res, what)

        val rExpr = R_ColAtExpr(
                type = resType,
                block = cBlock.rBlock,
                param = rParam,
                from = rFrom,
                what = what,
                where = rWhere,
                summarization = summarization,
                cardinality = cardinality,
                limit = rLimit,
                offset = rOffset
        )

        return V_RExpr(startPos, rExpr, details.exprFacts)
    }

    private fun checkConstraints(startPos: S_Pos) {
        if (!innerBlkCtx.isCollectionAtAllowed()) {
            innerBlkCtx.frameCtx.msgCtx.error(startPos, "expr:at:bad_context", "Collection-at now allowed here")
        }
    }

    private fun compileWhat(details: C_AtDetails): R_ColAtWhat {
        val cFields = details.base.what.fields
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
                        if (comparator != null && !sort.asc) comparator = comparator.reversed()
                        if (comparator != null) IndexedValue(i, comparator) else {
                            innerExprCtx.msgCtx.error(f.expr.pos, "at:expr:sort:type:${type}", "Type ${type} is not sortable")
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
            cField.summarization.compileR(innerExprCtx.nsCtx)
        }
        return R_ColAtWhatField(rExpr, cField.flags, summarization)
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

sealed class C_AtFromItem(val pos: S_Pos)
class C_AtFromItem_Entity(pos: S_Pos, val alias: String, val entity: R_Entity): C_AtFromItem(pos)

sealed class C_AtFromItem_Iterable(pos: S_Pos, val vExpr: V_Expr, val elemType: R_Type): C_AtFromItem(pos) {
    protected abstract fun compile0(rExpr: R_Expr): R_ColAtFrom

    fun compile(): R_ColAtFrom {
        val rExpr = vExpr.toRExpr()
        return compile0(rExpr)
    }
}

class C_AtFromItem_Collection(pos: S_Pos, vExpr: V_Expr, elemType: R_Type): C_AtFromItem_Iterable(pos, vExpr, elemType) {
    override fun compile0(rExpr: R_Expr): R_ColAtFrom = R_ColAtFrom_Collection(rExpr)
}

class C_AtFromItem_Map(pos: S_Pos, vExpr: V_Expr, private val tupleType: R_TupleType)
    : C_AtFromItem_Iterable(pos, vExpr, tupleType)
{
    override fun compile0(rExpr: R_Expr): R_ColAtFrom = R_ColAtFrom_Map(rExpr, tupleType)
}

class C_AtWhatField(
        val name: String?,
        val resultType: R_Type,
        val expr: V_Expr,
        val flags: R_AtWhatFieldFlags,
        val summarization: C_AtSummarization?
)

class C_AtWhat(fields: List<C_AtWhatField>) {
    val fields = fields.toImmList()
}

class C_AtExprBase(
        val from: C_AtFrom,
        val what: C_AtWhat,
        val where: V_Expr?
)

class C_AtExprResult(
        val type: R_Type,
        val rowDecoder: R_AtExprRowDecoder,
        selectedFields: List<Int>,
        groupFields: List<Int>,
        val hasAggregateFields: Boolean
) {
    val selectedFields = selectedFields.toImmList()
    val groupFields = groupFields.toImmList()
}

class C_AtDetails(
        val base: C_AtExprBase,
        val limit: V_Expr?,
        val offset: V_Expr?,
        val res: C_AtExprResult,
        val exprFacts: C_ExprVarFacts
)

class C_AtSummarizationPos(val exprPos: S_Pos, val ann: C_AtSummarizationKind)

sealed class C_AtSummarization(protected val pos: C_AtSummarizationPos, protected val valueType: R_Type) {
    abstract fun isGroup(): Boolean
    abstract fun getResultType(hasGroup: Boolean): R_Type
    abstract fun compileR(ctx: C_NamespaceContext): R_ColAtFieldSummarization
    abstract fun compileDb(ctx: C_NamespaceContext, dbExpr: Db_Expr): Db_Expr

    companion object {
        fun typeError(msgCtx: C_MessageContext, type: R_Type, pos: C_AtSummarizationPos) {
            val code = "at:what:aggr:bad_type:${pos.ann}:${type.toStrictString()}"
            val msg = "Invalid type of @${pos.ann.annotation} expression: ${type.toStrictString()}"
            msgCtx.error(pos.exprPos, code, msg)
        }
    }
}

class C_AtSummarization_Group(pos: C_AtSummarizationPos, valueType: R_Type): C_AtSummarization(pos, valueType) {
    override fun isGroup() = true
    override fun getResultType(hasGroup: Boolean) = valueType

    override fun compileR(ctx: C_NamespaceContext): R_ColAtFieldSummarization {
        C_Utils.checkGroupValueType(ctx, pos.exprPos, valueType)
        return R_ColAtFieldSummarization_Group()
    }

    override fun compileDb(ctx: C_NamespaceContext, dbExpr: Db_Expr) = dbExpr
}

sealed class C_AtSummarization_Aggregate(
        pos: C_AtSummarizationPos,
        valueType: R_Type
): C_AtSummarization(pos, valueType) {
    protected abstract fun compileDb0(): Db_SysFunction?

    final override fun isGroup() = false

    final override fun compileDb(ctx: C_NamespaceContext, dbExpr: Db_Expr): Db_Expr {
        val dbFn = compileDb0()
        if (dbFn == null) {
            typeError(ctx.msgCtx, valueType, pos)
            return dbExpr
        }
        return Db_CallExpr(dbExpr.type, dbFn, listOf(dbExpr))
    }
}

class C_AtSummarization_Aggregate_Sum(
        pos: C_AtSummarizationPos,
        valueType: R_Type,
        private val rOp: R_BinaryOp,
        private val zeroValue: Rt_Value
): C_AtSummarization_Aggregate(pos, valueType) {
    override fun getResultType(hasGroup: Boolean) = valueType
    override fun compileR(ctx: C_NamespaceContext) = R_ColAtFieldSummarization_Aggregate_Sum(rOp, zeroValue)
    override fun compileDb0() = Db_SysFn_Aggregation_Sum
}

class C_AtSummarization_Aggregate_MinMax(
        pos: C_AtSummarizationPos,
        valueType: R_Type,
        private val rCmpOp: R_CmpOp,
        private val rCmpType: R_CmpType?,
        private val rComparator: Comparator<Rt_Value>?,
        private val dbFn: Db_SysFunction
): C_AtSummarization_Aggregate(pos, valueType) {
    override fun getResultType(hasGroup: Boolean): R_Type {
        return if (hasGroup) valueType else C_Types.toNullable(valueType)
    }

    override fun compileR(ctx: C_NamespaceContext): R_ColAtFieldSummarization {
        return if (rComparator == null) {
            typeError(ctx.msgCtx, valueType, pos)
            R_ColAtFieldSummarization_None
        } else {
            R_ColAtFieldSummarization_Aggregate_MinMax(rCmpOp, rComparator)
        }
    }

    override fun compileDb0(): Db_SysFunction? {
        // Postgres doesn't support MIN/MAX for BOOLEAN and BYTEA.
        return if (rCmpType == null || valueType == R_BooleanType || valueType == R_ByteArrayType) null else dbFn
    }
}
