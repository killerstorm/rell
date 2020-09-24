/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.sql

import com.google.common.collect.Sets
import mu.KLogger
import mu.KLogging
import net.postchain.base.BaseEContext
import net.postchain.base.BlockchainRid
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.core.EContext
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_ExecutionContext
import net.postchain.rell.runtime.Rt_Messages
import net.postchain.rell.runtime.Rt_StackTraceError
import net.postchain.rell.runtime.Rt_Utils
import net.postchain.rell.utils.PostchainUtils

private val ORD_TABLES = 0
private val ORD_RECORDS = 1

class SqlInitLogging(
        val header: Boolean = false,
        val plan: Boolean = false,
        val planEmptyDb: Boolean = false,
        val step: Boolean = false,
        val stepEmptyDb: Boolean = false,
        val metaNoCode: Boolean = false,
        val allOther: Boolean = false
) {
    companion object {
        val LOG_NONE = 0
        val LOG_HEADER = 1000
        val LOG_STEP_COMPLEX = 2000
        val LOG_STEP_SIMPLE = 3000
        val LOG_PLAN_COMPLEX = 4000
        val LOG_PLAN_SIMPLE = 5000
        val LOG_ALL = Integer.MAX_VALUE

        fun ofLevel(level: Int) = SqlInitLogging(
                header = level >= LOG_HEADER,
                plan = level >= LOG_PLAN_COMPLEX,
                planEmptyDb = level >= LOG_PLAN_SIMPLE,
                step = level >= LOG_STEP_COMPLEX,
                stepEmptyDb = level >= LOG_STEP_SIMPLE,
                metaNoCode = level > LOG_NONE,
                allOther = level >= LOG_ALL
        )
    }
}

class SqlInit private constructor(
        private val exeCtx: Rt_ExecutionContext,
        private val initPostchain: Boolean,
        private val logging: SqlInitLogging
) {
    private val sqlCtx = exeCtx.appCtx.sqlCtx

    private val initCtx = SqlInitCtx(logger, logging, SqlObjectsInit(exeCtx))

    companion object : KLogging() {
        fun init(exeCtx: Rt_ExecutionContext, initPostchain: Boolean, logging: SqlInitLogging): List<String> {
            val obj = SqlInit(exeCtx, initPostchain, logging)
            return obj.init()
        }
    }

    private fun init(): List<String> {
        log(logging.header, "Initializing database (chain_iid = ${sqlCtx.mainChainMapping.chainId})")

        val dbEmpty = SqlInitPlanner.plan(exeCtx, initCtx)
        initCtx.checkErrors()

        if (initPostchain) {
            initPostchain()
        }

        exeCtx.appCtx.objectsInitialization(initCtx.objsInit) {
            executePlan(dbEmpty)
        }

        return initCtx.msgs.warningCodes()
    }

    private fun executePlan(dbEmpty: Boolean) {
        val steps = initCtx.steps()
        if (steps.isEmpty()) {
            log(logging.allOther, "Nothing to do")
            return
        }

        val stepLogAllowed = if (dbEmpty) logging.stepEmptyDb else logging.step
        log(stepLogAllowed, "Database init plan: ${steps.size} step(s)")

        val planLogAllowed = if (dbEmpty) logging.planEmptyDb else logging.plan
        for (step in steps) {
            log(planLogAllowed, "    ${step.title}")
        }

        val stepCtx = SqlStepCtx(logger, exeCtx, initCtx.objsInit)
        for (step in steps) {
            log(stepLogAllowed, "Step: ${step.title}")
            step.action.run(stepCtx)
        }

        log(logging.allOther, "Database initialization done")
    }

    private fun initPostchain() {
        val chainId = exeCtx.appCtx.sqlCtx.mainChainMapping.chainId
        val bcRid: BlockchainRid = BlockchainRid.EMPTY_RID

        val sqlAccess: SQLDatabaseAccess = PostgreSQLDatabaseAccess()
        exeCtx.sqlExec.connection { con ->
            sqlAccess.initializeApp(con, PostchainUtils.DATABASE_VERSION)
            val eCtx: EContext = BaseEContext(con, chainId, 0, sqlAccess)
            sqlAccess.initializeBlockchain(eCtx, bcRid)
        }
    }

    private fun log(allowed: Boolean, s: String) {
        if (allowed) {
            logger.info(s)
        }
    }
}

private class SqlInitPlanner private constructor(private val exeCtx: Rt_ExecutionContext, private val initCtx: SqlInitCtx) {
    private val sqlCtx = exeCtx.appCtx.sqlCtx
    private val mapping = sqlCtx.mainChainMapping

    companion object {
        fun plan(exeCtx: Rt_ExecutionContext, initCtx: SqlInitCtx): Boolean {
            val obj = SqlInitPlanner(exeCtx, initCtx)
            return obj.plan()
        }
    }

    private fun plan(): Boolean {
        val tables = exeCtx.sqlExec.connection { con ->
            SqlUtils.getExistingChainTables(con, mapping)
        }

        val metaExists = SqlMeta.checkMetaTablesExisting(mapping, tables, initCtx.msgs)
        initCtx.checkErrors()

        val metaData = processMeta(metaExists, tables)
        initCtx.checkErrors()

        SqlEntityIniter.processEntities(exeCtx, initCtx, metaData, tables)
        return !metaExists
    }

    private fun processMeta(metaExists: Boolean, tables: Map<String, SqlTable>): Map<String, MetaEntity> {
        if (!metaExists) {
            initCtx.step(ORD_TABLES, "Create ROWID table and function", SqlStepAction_ExecSql(SqlGen.genRowidSql(mapping)))
            initCtx.step(ORD_TABLES, "Create meta tables", SqlStepAction_ExecSql(SqlMeta.genMetaTablesCreate(sqlCtx)))
        }

        val metaData = if (!metaExists) mapOf() else SqlMeta.loadMetaData(exeCtx.sqlExec, mapping, initCtx.msgs)
        initCtx.checkErrors()

        SqlMeta.checkDataTables(sqlCtx, tables, metaData, initCtx.msgs)
        initCtx.checkErrors()

        return metaData
    }
}

private class SqlEntityIniter private constructor(
        private val exeCtx: Rt_ExecutionContext,
        private val initCtx: SqlInitCtx,
        private val metaData: Map<String, MetaEntity>,
        private val sqlTables: Map<String, SqlTable>
) {
    private val sqlCtx = exeCtx.appCtx.sqlCtx

    private var nextMetaEntityId = 1 + (metaData.values.map { it.id }.max() ?: -1)

    private fun processEntities() {
        val appDefs = sqlCtx.appDefs

        for (entity in appDefs.topologicalEntities) {
            if (entity.sqlMapping.autoCreateTable()) {
                processEntity(entity, MetaEntityType.ENTITY)
            }
        }

        for (obj in appDefs.objects) {
            val ins = processEntity(obj.rEntity, MetaEntityType.OBJECT)
            if (ins) {
                val entityName = msgEntityName(obj.rEntity)
                initCtx.step(ORD_RECORDS, "Create record for object $entityName", SqlStepAction_InsertObject(obj))
                initCtx.objsInit.addObject(obj)
            }
        }

        val codeEntities = appDefs.entities
                .plus(appDefs.objects.map { it.rEntity })
                .map { it.metaName }
                .toSet()

        for (metaEntity in metaData.values.filter { it.name !in codeEntities }) {
            if (initCtx.logging.metaNoCode) {
                // No need to print this warning in a Console App.
                initCtx.msgs.warning("dbinit:no_code:${metaEntity.type}:${metaEntity.name}",
                        "Table for undefined ${metaEntity.type.en} '${metaEntity.name}' found")
            }
        }
    }

    private fun processEntity(entity: R_Entity, type: MetaEntityType): Boolean {
        val metaEntity = metaData[entity.metaName]
        if (metaEntity == null) {
            processNewEntity(entity, type)
            return true
        } else {
            processExistingEntity(entity, type, metaEntity)
            return false
        }
    }

    private fun processNewEntity(entity: R_Entity, type: MetaEntityType) {
        val sqls = mutableListOf<String>()
        sqls += SqlGen.genEntity(sqlCtx, entity)

        val id = nextMetaEntityId++
        sqls += SqlMeta.genMetaEntityInserts(sqlCtx, id, entity, type)

        val entityName = msgEntityName(entity)
        initCtx.step(ORD_TABLES, "Create table and meta for $entityName", SqlStepAction_ExecSql(sqls))
    }

    private fun processExistingEntity(entity: R_Entity, type: MetaEntityType, metaCls: MetaEntity) {
        if (type != metaCls.type) {
            val clsName = msgEntityName(entity)
            initCtx.msgs.error("meta:entity:diff_type:${entity.metaName}:${metaCls.type}:$type",
                    "Cannot initialize database: $clsName was ${metaCls.type.en}, now ${type.en}")
        }

        val newLog = entity.flags.log
        if (newLog != metaCls.log) {
            val oldLog = metaCls.log
            val clsName = msgEntityName(entity)
            initCtx.msgs.error("meta:entity:diff_log:${entity.metaName}:$oldLog:$newLog",
                    "Log annotation of $clsName was $oldLog, now $newLog")
        }

        checkAttrTypes(entity, metaCls)
        checkOldAttrs(entity, metaCls)
        checkSqlIndexes(entity)

        val newAttrs = entity.attributes.keys.filter { it !in metaCls.attrs }
        if (!newAttrs.isEmpty()) {
            processNewAttrs(entity, metaCls.id, newAttrs)
        }
    }

    private fun checkAttrTypes(entity: R_Entity, metaEntity: MetaEntity) {
        for (attr in entity.attributes.values) {
            val metaAttr = metaEntity.attrs[attr.name]
            if (metaAttr != null) {
                val oldType = metaAttr.type
                val newType = attr.type.sqlAdapter.metaName(sqlCtx)
                if (newType != oldType) {
                    val entityName = msgEntityName(entity)
                    initCtx.msgs.error("meta:attr:diff_type:${entity.metaName}:${attr.name}:$oldType:$newType",
                            "Type of attribute '${attr.name}' of entity $entityName changed: was $oldType, now $newType")
                }
            }
        }
    }

    private fun checkOldAttrs(entity: R_Entity, metaEntity: MetaEntity) {
        val oldAttrs = metaEntity.attrs.keys.filter { it !in entity.attributes }.sorted()
        if (!oldAttrs.isEmpty()) {
            val codeList = oldAttrs.joinToString(",")
            val msgList = oldAttrs.joinToString(", ")
            if (initCtx.logging.metaNoCode) {
                val entityName = msgEntityName(entity)
                initCtx.msgs.warning("dbinit:no_code:attrs:${entity.metaName}:$codeList",
                        "Table columns for undefined attributes of ${metaEntity.type.en} $entityName found: $msgList")
            }
        }
    }

    private fun checkSqlIndexes(entity: R_Entity) {
        val table = sqlTables.getValue(entity.sqlMapping.table(sqlCtx))
        val sqlIndexes = table.indexes.filter { !(it.unique && it.cols == listOf(SqlConstants.ROWID_COLUMN)) }

        val codeIndexes = mutableListOf<SqlIndex>()
        codeIndexes.addAll(entity.keys.map { SqlIndex("", true, it.attribs) })
        codeIndexes.addAll(entity.indexes.map { SqlIndex("", false, it.attribs) })

        compareSqlIndexes(entity, "database", sqlIndexes, "code", codeIndexes, true)
        compareSqlIndexes(entity, "database", sqlIndexes, "code", codeIndexes, false)
        compareSqlIndexes(entity, "code", codeIndexes, "database", sqlIndexes, true)
        compareSqlIndexes(entity, "code", codeIndexes, "database", sqlIndexes, false)
    }

    private fun compareSqlIndexes(
            entity: R_Entity,
            aPlace: String,
            aIndexes: List<SqlIndex>,
            bPlace: String,
            bIndexes: List<SqlIndex>,
            unique: Boolean
    ) {
        val a = aIndexes.filter { it.unique == unique }.map { it.cols }.toSet()
        val b = bIndexes.filter { it.unique == unique }.map { it.cols }.toSet()
        val indexType = if (unique) "key" else "index"

        val aOnly = Sets.difference(a, b)
        for (cols in aOnly) {
            val colsShort = cols.joinToString(",")
            val entityName = msgEntityName(entity)
            initCtx.msgs.error("dbinit:index_diff:${entity.metaName}:$aPlace:$indexType:$colsShort",
                    "Entity $entityName: $indexType $cols exists in $aPlace, but not in $bPlace")
        }
    }

    private fun processNewAttrs(entity: R_Entity, metaEntityId: Int, newAttrs: List<String>) {
        val attrsStr = newAttrs.joinToString()

        val recs = SqlUtils.recordsExist(exeCtx.sqlExec, sqlCtx, entity)

        val entityName = msgEntityName(entity)

        val exprAttrs = makeCreateExprAttrs(entity, newAttrs, recs)
        if (exprAttrs.size == newAttrs.size) {
            val action = SqlStepAction_AddColumns_AlterTable(entity, exprAttrs, recs)
            val details = if (recs) "records exist" else "no records"
            initCtx.step(ORD_RECORDS, "Add table columns for $entityName ($details): $attrsStr", action)
        }

        val rAttrs = newAttrs.map { entity.attributes.getValue(it) }
        val metaSql = SqlMeta.genMetaAttrsInserts(sqlCtx, metaEntityId, rAttrs)
        initCtx.step(ORD_TABLES, "Add meta attributes for $entityName: $attrsStr", SqlStepAction_ExecSql(metaSql))
    }

    private fun makeCreateExprAttrs(entity: R_Entity, newAttrs: List<String>, existingRecs: Boolean): List<R_CreateExprAttr> {
        val res = mutableListOf<R_CreateExprAttr>()

        val keys = entity.keys.flatMap { it.attribs }.toSet()
        val indexes = entity.indexes.flatMap { it.attribs }.toSet()

        val entityName = msgEntityName(entity)

        for (name in newAttrs) {
            val attr = entity.attributes.getValue(name)
            if (attr.expr != null || !existingRecs) {
                res.add(R_CreateExprAttr_Default(attr))
            } else {
                initCtx.msgs.error("meta:attr:new_no_def_value:${entity.metaName}:$name",
                        "New attribute '$name' of entity $entityName has no default value")
            }

            if (name in keys) {
                initCtx.msgs.error("meta:attr:new_key:${entity.metaName}:$name",
                        "New attribute '$name' of entity $entityName is a key, adding key attributes not supported")
            }
            if (name in indexes) {
                initCtx.msgs.error("meta:attr:new_index:${entity.metaName}:$name",
                        "New attribute '$name' of entity $entityName is an index, adding index attributes not supported")
            }
        }

        return res
    }

    companion object {
        fun processEntities(
                exeCtx: Rt_ExecutionContext,
                initCtx: SqlInitCtx,
                metaData: Map<String, MetaEntity>,
                sqlTables: Map<String, SqlTable>
        ) {
            val obj = SqlEntityIniter(exeCtx, initCtx, metaData, sqlTables)
            obj.processEntities()
        }
    }
}

private class SqlInitCtx(logger: KLogger, val logging: SqlInitLogging, val objsInit: SqlObjectsInit) {
    val msgs = Rt_Messages(logger)

    private val steps = mutableListOf<SqlInitStep>()

    fun checkErrors() = msgs.checkErrors()
    fun step(order: Int, title: String, action: SqlStepAction) = steps.add(SqlInitStep(order, steps.size, title, action))
    fun steps() = steps.toList().sorted()
}

private class SqlInitStep(val order: Int, val order2: Int, val title: String, val action: SqlStepAction): Comparable<SqlInitStep> {
    override fun compareTo(other: SqlInitStep): Int {
        var d = Integer.compare(order, other.order)
        if (d == 0) d = Integer.compare(order2, other.order2)
        return d
    }
}

private class SqlStepCtx(val logger: KLogger, val exeCtx: Rt_ExecutionContext, val objsInit: SqlObjectsInit) {
    val sqlCtx = exeCtx.appCtx.sqlCtx
    val sqlExec = exeCtx.sqlExec
}

private sealed class SqlStepAction {
    abstract fun run(ctx: SqlStepCtx)
}

private class SqlStepAction_ExecSql(sqls: List<String>): SqlStepAction() {
    private val sqls: List<String> = sqls.toList()

    constructor(sql: String): this(listOf(sql))

    override fun run(ctx: SqlStepCtx) {
        val sql = SqlGen.joinSqls(sqls)
        ctx.sqlExec.execute(sql)
    }
}

private class SqlStepAction_InsertObject(private val rObject: R_Object): SqlStepAction() {
    override fun run(ctx: SqlStepCtx) {
        try {
            ctx.objsInit.initObject(rObject)
        } catch (e: Rt_StackTraceError) {
            ctx.logger.error {
                val head = "Failed to insert record for object '${rObject.appLevelName}': ${e.message}"
                Rt_Utils.appendStackTrace(head, e.stack)
            }
            throw e
        }
    }
}

private class SqlStepAction_AddColumns_AlterTable(
        private val entity: R_Entity,
        private val attrs: List<R_CreateExprAttr>,
        private val existingRecs: Boolean
): SqlStepAction() {
    override fun run(ctx: SqlStepCtx) {
        val sql = R_CreateExpr.buildAddColumnsSql(ctx.sqlCtx, entity, attrs, existingRecs)
        val frame = ctx.exeCtx.appCtx.createRootFrame(entity.pos, ctx.sqlExec)
        sql.execute(frame)
    }
}

private fun msgEntityName(rEntity: R_Entity): String {
    val meta = rEntity.metaName
    val app = rEntity.appLevelName
    return if (meta == app) {
        "'$app'"
    } else {
        "'$app' (meta: $meta)"
    }
}
