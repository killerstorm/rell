package net.postchain.rell.sql

import mu.KLogger
import mu.KLogging
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Messages
import net.postchain.rell.runtime.Rt_ModuleContext
import java.util.concurrent.atomic.AtomicLong

private val ORD_TABLES = 0
private val ORD_RECORDS = 1

class SqlInit private constructor(private val modCtx: Rt_ModuleContext, private val logLevel: Int) {
    private val initCtx = SqlInitCtx(logger)

    companion object : KLogging() {
        val LOG_ALL = 0
        val LOG_PLAN_SIMPLE = 1000
        val LOG_PLAN_COMPLEX = 2000
        val LOG_STEP_SIMPLE = 3000
        val LOG_STEP_COMPLEX = 4000
        val LOG_TITLE = 5000
        val LOG_NONE = Integer.MAX_VALUE

        fun init(modCtx: Rt_ModuleContext, logLevel: Int): List<String> {
            val obj = SqlInit(modCtx, logLevel)
            return obj.init()
        }
    }

    private fun init(): List<String> {
        log(LOG_TITLE, "Initializing database (chain_id = ${modCtx.sqlCtx.mainChainMapping.chainId})")

        val dbEmpty = SqlInitPlanner.plan(modCtx, initCtx)
        initCtx.checkErrors()

        executePlan(dbEmpty)

        return initCtx.msgs.warningCodes()
    }

    private fun executePlan(dbEmpty: Boolean) {
        val steps = initCtx.steps()
        if (steps.isEmpty()) {
            log(LOG_ALL, "Nothing to do")
            return
        }

        val stepLogLevel = if (dbEmpty) LOG_STEP_SIMPLE else LOG_STEP_COMPLEX
        log(stepLogLevel, "Database init plan: ${steps.size} step(s)")

        val planLogLevel = if (dbEmpty) LOG_PLAN_SIMPLE else LOG_PLAN_COMPLEX
        for (step in steps) {
            log(planLogLevel, "    ${step.title}")
        }

        val stepCtx = SqlStepCtx(modCtx, modCtx.globalCtx.sqlExec)
        for (step in steps) {
            log(stepLogLevel, "Step: ${step.title}")
            step.action.run(stepCtx)
        }

        log(LOG_ALL, "Database initialization done")
    }

    private fun log(level: Int, s: String) {
        if (level >= logLevel) {
            logger.info(s)
        }
    }
}

private class SqlInitPlanner private constructor(private val modCtx: Rt_ModuleContext, private val initCtx: SqlInitCtx) {
    private val sqlCtx = modCtx.sqlCtx
    private val mapping = sqlCtx.mainChainMapping

    companion object {
        fun plan(modCtx: Rt_ModuleContext, initCtx: SqlInitCtx): Boolean {
            val obj = SqlInitPlanner(modCtx, initCtx)
            return obj.plan()
        }
    }

    private fun plan(): Boolean {
        val con = modCtx.globalCtx.sqlExec.connection()
        val tables = SqlUtils.getExistingChainTables(con, mapping)

        val metaExists = SqlMeta.checkMetaTablesExisting(mapping, tables, initCtx.msgs)
        initCtx.checkErrors()

        val metaData = processMeta(metaExists, tables)
        initCtx.checkErrors()

        SqlClassIniter.processClasses(modCtx, initCtx, metaData)
        return !metaExists
    }

    private fun processMeta(metaExists: Boolean, tables: Map<String, SqlTable>): Map<String, MetaClass> {
        if (!metaExists) {
            initCtx.step(ORD_TABLES, "Create ROWID table and function", SqlStepAction_ExecSql(SqlGen.genRowidSql(mapping)))
            initCtx.step(ORD_TABLES, "Create meta tables", SqlStepAction_ExecSql(SqlMeta.genMetaTablesCreate(modCtx.sqlCtx)))
        }

        val metaData = if (!metaExists) mapOf() else SqlMeta.loadMetaData(modCtx.globalCtx.sqlExec, mapping, initCtx.msgs)
        initCtx.checkErrors()

        SqlMeta.checkDataTables(modCtx, tables, metaData, initCtx.msgs)
        initCtx.checkErrors()

        return metaData
    }
}

private class SqlClassIniter private constructor(
        private val modCtx: Rt_ModuleContext,
        private val initCtx: SqlInitCtx,
        private val metaData: Map<String, MetaClass>
) {
    private val sqlCtx = modCtx.sqlCtx
    private var nextMetaClsId = 1 + (metaData.values.map { it.id }.max() ?: -1)

    private fun processClasses() {
        val sqlCtx = modCtx.sqlCtx

        for (cls in sqlCtx.module.topologicalClasses) {
            if (cls.sqlMapping.autoCreateTable()) {
                processClass(cls, MetaClassType.CLASS)
            }
        }

        for (obj in sqlCtx.module.objects.values) {
            val ins = processClass(obj.rClass, MetaClassType.OBJECT)
            if (ins) {
                initCtx.step(ORD_RECORDS, "Create record for object '${obj.rClass.name}'", SqlStepAction_InsertObject(obj))
            }
        }

        val codeClasses = sqlCtx.module.topologicalClasses
                .plus(sqlCtx.module.objects.values.map { it.rClass })
                .map { it.name }
                .toSet()

        for (metaCls in metaData.values.filter { it.name !in codeClasses }) {
            initCtx.msgs.warning("dbinit:no_code:${metaCls.type}:${metaCls.name}",
                    "Table for undefined ${metaCls.type.en} '${metaCls.name}' found")
        }
    }

    private fun processClass(cls: R_Class, type: MetaClassType): Boolean {
        val metaCls = metaData[cls.name]
        if (metaCls == null) {
            processNewClass(cls, type)
            return true
        } else {
            processExistingClass(cls, type, metaCls)
            return false
        }
    }

    private fun processNewClass(cls: R_Class, type: MetaClassType) {
        val sqls = mutableListOf<String>()
        sqls += SqlGen.genClass(sqlCtx, cls)

        val id = nextMetaClsId++
        sqls += SqlMeta.genMetaClassInserts(sqlCtx, id, cls, type)

        initCtx.step(ORD_TABLES, "Create table and meta for '${cls.name}'", SqlStepAction_ExecSql(sqls))
    }

    private fun processExistingClass(cls: R_Class, type: MetaClassType, metaCls: MetaClass) {
        if (type != metaCls.type) {
            initCtx.msgs.error("meta:cls:diff_type:${metaCls.type}:$type",
                    "Cannot initialize database: '${cls.name}' was ${metaCls.type}, now $type")
        }

        val newLog = cls.flags.log
        if (newLog != metaCls.log) {
            initCtx.msgs.error("meta:cls:diff_log:${metaCls.log}:$newLog",
                    "Log annotation of '${cls.name}' was ${metaCls.log}, now $newLog")
        }

        for (attr in cls.attributes.values) {
            val metaAttr = metaCls.attrs[attr.name]
            if (metaAttr != null) {
                val oldType = metaAttr.type
                val newType = attr.type.sqlAdapter.metaName(sqlCtx)
                if (newType != oldType) {
                    initCtx.msgs.error("meta:attr:diff_type:${cls.name}:${attr.name}:${metaAttr.type}:$newType",
                            "Type of attribute '${cls.name}.${attr.name}' changed: was $oldType, now $newType")
                }
            }
        }

        val newAttrs = cls.attributes.keys.filter { it !in metaCls.attrs }
        if (!newAttrs.isEmpty()) {
            processNewAttrs(cls, metaCls.id, newAttrs)
        }

        val oldAttrs = metaCls.attrs.keys.filter { it !in cls.attributes }.sorted()
        if (!oldAttrs.isEmpty()) {
            val codeList = oldAttrs.joinToString(",")
            val msgList = oldAttrs.joinToString(", ")
            initCtx.msgs.warning("dbinit:no_code:attrs:${cls.name}:$codeList",
                    "Table columns for undefined attributes of ${metaCls.type.en} '${cls.name}' found: $msgList")
        }
    }

    private fun processNewAttrs(cls: R_Class, metaClsId: Int, newAttrs: List<String>) {
        val attrsStr = newAttrs.joinToString()

        val recs = SqlUtils.recordsExist(modCtx.globalCtx.sqlExec, sqlCtx, cls)
        if (!recs) {
            val action = SqlStepAction_AddColumns_NoRecords(cls)
            initCtx.step(ORD_TABLES, "Add table columns for '${cls.name}' (no records): $attrsStr", action)
        } else {
            processNewAttrsExistingRecords(cls, newAttrs)
        }

        val rAttrs = newAttrs.map { cls.attributes.getValue(it) }
        val metaSql = SqlMeta.genMetaAttrsInserts(sqlCtx, metaClsId, rAttrs)
        initCtx.step(ORD_TABLES, "Add meta attributes for '${cls.name}': $attrsStr", SqlStepAction_ExecSql(metaSql))
    }

    private fun processNewAttrsExistingRecords(cls: R_Class, newAttrs: List<String>) {
        val exprAttrs = mutableListOf<R_CreateExprAttr>()
        for (name in newAttrs) {
            val attr = cls.attributes.getValue(name)
            if (attr.expr != null) {
                exprAttrs.add(R_CreateExprAttr_Default(attr))
            } else {
                val type = attr.type
                val value = type.defaultValue()
                if (value != null) {
                    val expr = R_ConstantExpr(value)
                    exprAttrs.add(R_CreateExprAttr_Specified(attr, expr))
                } else {
                    initCtx.msgs.error("meta:attr:new_no_def_value:${cls.name}:$name:$type",
                            "New attribute '${cls.name}.$name' has no default value")
                }
            }
        }

        if (exprAttrs.size == newAttrs.size) {
            val attrsStr = newAttrs.joinToString()
            val action = SqlStepAction_AddColumns_CopyRecords(cls, exprAttrs)
            initCtx.step(ORD_RECORDS, "Add table columns for '${cls.name}' (copy existing records): $attrsStr", action)
        }
    }

    companion object {
        fun processClasses(modCtx: Rt_ModuleContext, initCtx: SqlInitCtx, metaData: Map<String, MetaClass>) {
            val obj = SqlClassIniter(modCtx, initCtx, metaData)
            obj.processClasses()
        }
    }
}

private class SqlInitCtx(logger: KLogger) {
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

private class SqlStepCtx(val modCtx: Rt_ModuleContext, val sqlExec: SqlExecutor)

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
        ctx.modCtx.insertObjectRecord(rObject)
    }
}

private class SqlStepAction_AddColumns_NoRecords(private val cls: R_Class): SqlStepAction() {
    override fun run(ctx: SqlStepCtx) {
        addColumns(cls, ctx) {}
    }

    companion object {
        private val TEMP_TIME = System.currentTimeMillis()
        private val TEMP_COUNTER = AtomicLong(0)

        private fun uniqueTag(): String {
            val ctr = TEMP_COUNTER.getAndIncrement()
            return "${TEMP_TIME}_$ctr"
        }

        fun addColumns(cls: R_Class, ctx: SqlStepCtx, copier: (String) -> Unit) {
            val sqlCtx = ctx.modCtx.sqlCtx

            val table = cls.sqlMapping.table(sqlCtx)

            // Using a unique tag for the "new" name to avoid index/constraint name conflict: the new table will be
            // called "c0.sys.temp.new_A.X" and then renamed to "c0.X", but indexes and constraints will not be
            // renamed and will remain "c0.sys.temp.new_A.X", so running database initialization for the second time
            // for the same class would cause a name conflict if not using a unique tag.
            val newTable = cls.sqlMapping.table(sqlCtx) + "." + uniqueTag()

            val oldTable = cls.sqlMapping.tempTable(sqlCtx, "old")

            val createTableSql = SqlGen.genClass(sqlCtx, cls, newTable)
            ctx.sqlExec.execute(createTableSql)

            copier(newTable)

            ctx.sqlExec.execute("""ALTER TABLE "$table" RENAME TO "$oldTable";""")
            ctx.sqlExec.execute("""ALTER TABLE "$newTable" RENAME TO "$table";""")
            ctx.sqlExec.execute("""DROP TABLE "$oldTable";""")
        }
    }
}

private class SqlStepAction_AddColumns_CopyRecords(
        private val cls: R_Class,
        private val attrs: List<R_CreateExprAttr>
): SqlStepAction() {
    override fun run(ctx: SqlStepCtx) {
        SqlStepAction_AddColumns_NoRecords.addColumns(cls, ctx) { newTable ->
            val frame = ctx.modCtx.createRootFrame()
            val copySql = R_CreateExpr.buildCopySql(ctx.modCtx.sqlCtx, cls, newTable, attrs)
            copySql.execute(frame)
        }
    }
}
