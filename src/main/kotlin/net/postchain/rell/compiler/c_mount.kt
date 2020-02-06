package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.mutableMultimapOf
import net.postchain.rell.sql.SqlConstants
import net.postchain.rell.toImmList
import net.postchain.rell.toImmMap

private class C_MountConflictsProcessor(private val chain: String?, private val currentStamp: R_AppUid) {
    fun processConflicts(
            msgCtx: C_MessageContext,
            allEntries: List<C_MntEntry>,
            errorsEntries: MutableSet<C_MntEntry>
    ): List<C_MntEntry> {
        val map = mutableMultimapOf<R_MountName, C_MntEntry>()
        for (entry in allEntries) {
            map.put(entry.mountName, entry)
        }

        val res = mutableListOf<C_MntEntry>()

        for (name in map.keySet()) {
            val entries = map.get(name)
            val (sysEntries, userEntries) = entries.partition { it.pos == null }

            if (entries.size > 1) {
                for (entry in userEntries) {
                    if (errorsEntries.add(entry)) {
                        val otherEntry = entries.first { it !== entry }
                        handleConflict(msgCtx, entry, otherEntry)
                    }
                }
            }

            res.addAll(sysEntries)
            if (sysEntries.isEmpty() && !userEntries.isEmpty()) {
                res.add(userEntries[0])
            }
        }

        return res.toImmList()
    }

    private fun handleConflict(msgCtx: C_MessageContext, entry: C_MntEntry, otherEntry: C_MntEntry) {
        if (entry.def.appLevelName == otherEntry.def.appLevelName || entry.stamp != currentStamp) {
            // Same module and same qualified name -> must be also a name conflict error.
            return
        }

        val error = C_Errors.errMountConflict(chain, entry.mountName, entry.def, entry.pos!!, otherEntry)
        msgCtx.error(error)
    }
}

class C_MntEntry(
        val type: C_DeclarationType,
        val def: R_Definition,
        val pos: S_Pos?,
        val mountName: R_MountName,
        val stamp: R_AppUid
) {
    companion object {
        private val SYSTEM_MOUNT_NAMES = SqlConstants.SYSTEM_OBJECTS.map { R_MountName.of(it) }
        private val SYSTEM_MOUNT_PREFIX = R_MountName.of("sys")

        fun processMountConflicts(msgCtx: C_MessageContext, stamp: R_AppUid, mntTables: C_MountTables): C_MountTables {
            val resChains = mntTables.chains.mapValues { (chain, t) ->
                val nullableChain = if (chain == "") null else chain

                val entities = processSystemMountConflicts(msgCtx, stamp, t.entities)

                C_ChainMountTables(
                        processMountConflicts0(msgCtx, nullableChain, stamp, entities),
                        processMountConflicts0(msgCtx, nullableChain, stamp, t.operations),
                        processMountConflicts0(msgCtx, nullableChain, stamp, t.queries)
                )
            }
            return C_MountTables(resChains)
        }

        private fun processMountConflicts0(
                msgCtx: C_MessageContext,
                chain: String?,
                stamp: R_AppUid,
                mntTable: C_MntTable
        ): C_MntTable {
            val processor = C_MountConflictsProcessor(chain, stamp)
            val resEntries = processor.processConflicts(msgCtx, mntTable.entries, mutableSetOf())
            return C_MntTable(resEntries)
        }

        private fun processSystemMountConflicts(msgCtx: C_MessageContext, stamp: R_AppUid, mntTable: C_MntTable): C_MntTable {
            val b = C_MntTableBuilder(stamp)

            for (entry in mntTable.entries) {
                if (entry.pos != null && (entry.mountName in SYSTEM_MOUNT_NAMES || entry.mountName.startsWith(SYSTEM_MOUNT_PREFIX))) {
                    msgCtx.error(C_Errors.errMountConflictSystem(entry.mountName, entry.def, entry.pos))
                } else {
                    b.add(entry)
                }
            }

            return b.build()
        }
    }
}

class C_MountTablesBuilder(private val stamp: R_AppUid) {
    private val chains = mutableMapOf<String, C_ChainMountTablesBuilder>()

    fun add(tables: C_MountTables) {
        for ((chain, t) in tables.chains) {
            val b = chainBuilder(chain)
            b.entities.add(t.entities)
            b.operations.add(t.operations)
            b.queries.add(t.queries)
        }
    }

    fun addEntity(namePos: S_Pos?, rEntity: R_Entity) {
        addEntity0(C_DeclarationType.ENTITY, namePos, rEntity, rEntity)
    }

    fun addObject(name: S_Name, rObject: R_Object) {
        addEntity0(C_DeclarationType.OBJECT, name.pos, rObject, rObject.rEntity)
    }

    private fun addEntity0(type: C_DeclarationType, namePos: S_Pos?, def: R_Definition, rEntity: R_Entity) {
        val chain = rEntity.external?.chain?.name ?: ""
        val b = chainBuilder(chain)
        b.entities.add(type, def, namePos, rEntity.mountName)
    }

    fun addOperation(name: S_Name, o: R_Operation) {
        val b = chainBuilder("")
        b.operations.add(C_DeclarationType.OPERATION, o, name.pos, o.mountName)
    }

    fun addQuery(name: S_Name, q: R_Query) {
        val b = chainBuilder("")
        b.queries.add(C_DeclarationType.QUERY, q, name.pos, q.mountName)
    }

    fun addQuery(q: R_Query) {
        val b = chainBuilder("")
        b.queries.add(C_DeclarationType.QUERY, q, null, q.mountName)
    }

    private fun chainBuilder(chain: String) = chains.computeIfAbsent(chain) { C_ChainMountTablesBuilder(stamp) }

    fun build(): C_MountTables {
        val resChains = chains.mapValues { (_, v) ->
            C_ChainMountTables(v.entities.build(), v.operations.build(), v.queries.build())
        }
        return C_MountTables(resChains)
    }
}

class C_MountTables(chains: Map<String, C_ChainMountTables>) {
    val chains = chains.toImmMap()

    companion object { val EMPTY = C_MountTables(mapOf()) }
}

class C_ChainMountTables(val entities: C_MntTable, val operations: C_MntTable, val queries: C_MntTable)

class C_ChainMountTablesBuilder(stamp: R_AppUid) {
    val entities = C_MntTableBuilder(stamp)
    val operations = C_MntTableBuilder(stamp)
    val queries = C_MntTableBuilder(stamp)
}

class C_MntTableBuilder(private val stamp: R_AppUid) {
    private val entries = mutableListOf<C_MntEntry>()
    private var finished = false

    fun add(table: C_MntTable) {
        check(!finished)
        entries.addAll(table.entries)
    }

    fun add(entry: C_MntEntry) {
        check(!finished)
        entries.add(entry)
    }

    fun add(type: C_DeclarationType, def: R_Definition, namePos: S_Pos?, mountName: R_MountName) {
        check(!finished)
        entries.add(C_MntEntry(type, def, namePos, mountName, stamp))
    }

    fun build(): C_MntTable {
        check(!finished)
        finished = true
        return C_MntTable(entries)
    }
}

class C_MntTable(entries: List<C_MntEntry>) {
    val entries = entries.toImmList()

    companion object { val EMPTY = C_MntTable(listOf()) }
}
