package net.postchain.rell.parser

import net.postchain.rell.Getter
import net.postchain.rell.Setter
import net.postchain.rell.model.*
import net.postchain.rell.toImmList

private object C_DefConflictsProcessor_NsEntry: C_DefConflictsProcessor<String, C_NsEntry>() {
    override fun isSystemEntry(entry: C_NsEntry) = entry.sName == null
    override fun getConflictableKey(entry: C_NsEntry) = entry.name

    override fun handleConflict(globalCtx: C_GlobalContext, entry: C_NsEntry, otherEntry: C_NsEntry) {
        val otherType = otherEntry.def.type()
        val otherPos = otherEntry.sName?.pos
        val error = C_Errors.errNameConflict(entry.sName!!, otherType, otherPos)
        globalCtx.error(error)
    }
}

class C_NsEntry(val name: String, val sName: S_Name?, val privateAccess: Boolean, val def: C_NsDef) {
    companion object {
        fun processNameConflicts(
                globalCtx: C_GlobalContext,
                entries: List<C_NsEntry>,
                errorsEntries: MutableSet<C_NsEntry>
        ): List<C_NsEntry> {
            return C_DefConflictsProcessor_NsEntry.processConflicts(globalCtx, entries, errorsEntries)
        }

        fun createNamespace(entries: List<C_NsEntry>): C_Namespace {
            val nsBuilder = C_NamespaceBuilder()
            for (entry in entries) {
                entry.def.addToNamespace(nsBuilder, entry.name)
            }
            return nsBuilder.build()
        }

        fun createModuleDefs(entries: List<C_NsEntry>): C_ModuleDefs {
            val defsBuilder = C_ModuleDefsBuilder()
            for (entry in entries) {
                if (entry.sName != null) {
                    entry.def.addToDefs(defsBuilder)
                }
            }
            return defsBuilder.build()
        }
    }
}

abstract class C_NsDef {
    abstract fun type(): C_DeclarationType
    abstract fun addToNamespace(b: C_NamespaceBuilder, name: String)

    open fun addToDefs(b: C_ModuleDefsBuilder) {
    }
}

private class C_NsDef_Import(private val module: C_Module): C_NsDef() {
    override fun type() = C_DeclarationType.IMPORT

    override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        val nsDef = C_ImportNamespaceDef(module)
        b.addNamespace(name, nsDef)
        b.addValue(name, C_NamespaceValue_Namespace(nsDef))
    }
}

private sealed class C_NsDef_Namespace(private val ns: C_NamespaceDef): C_NsDef() {
    final override fun type() = C_DeclarationType.NAMESPACE

    final override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        val nsValue = C_NamespaceValue_Namespace(ns)
        b.addNamespace(name, ns)
        b.addValue(name, nsValue)
    }
}

private class C_NsDef_SysNamespace(ns: C_NamespaceDef): C_NsDef_Namespace(ns)

private class C_NsDef_UserNamespace(ns: C_NamespaceDef, private val defs: C_ModuleDefs): C_NsDef_Namespace(ns) {
    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.addDefs(defs)
    }
}

private class C_NsDef_Type(private val type: C_TypeDef): C_NsDef() {
    override fun type() = C_DeclarationType.TYPE

    override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        b.addType(name, type)
    }
}

private sealed class C_NsDef_Entity(private val entity: R_Entity): C_NsDef() {
    final override fun type() = C_DeclarationType.ENTITY

    final override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        val typeDef = C_TypeDef(entity.type)
        b.addType(name, typeDef)
        b.addValue(name, C_NamespaceValue_Entity(typeDef))
    }
}

private class C_NsDef_SysEntity(entity: R_Entity): C_NsDef_Entity(entity)

private class C_NsDef_UserEntity(private val entity: C_Entity, private val addToModule: Boolean): C_NsDef_Entity(entity.entity) {
    override fun addToDefs(b: C_ModuleDefsBuilder) {
        if (addToModule) {
            b.entities.add(entity.entity.moduleLevelName, entity.entity)
        }
    }
}

private class C_NsDef_Object(private val obj: R_Object): C_NsDef() {
    override fun type() = C_DeclarationType.OBJECT

    override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        b.addValue(name, C_NamespaceValue_Object(obj))
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.objects.add(obj.moduleLevelName, obj)
    }
}

private class C_NsDef_Struct(private val struct: C_Struct): C_NsDef() {
    override fun type() = C_DeclarationType.STRUCT

    override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        b.addType(name, C_TypeDef(struct.struct.type))
        b.addValue(name, C_NamespaceValue_Struct(struct.struct))
        b.addFunction(name, C_StructGlobalFunction(struct.struct))
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.structs.add(struct.struct.moduleLevelName, struct)
    }
}

private class C_NsDef_Enum(private val e: R_Enum): C_NsDef() {
    override fun type() = C_DeclarationType.ENUM

    override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        b.addType(name, C_TypeDef(e.type))
        b.addValue(name, C_NamespaceValue_Enum(e))
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.enums.add(e.moduleLevelName, e)
    }
}

private class C_NsDef_SysFunction(private val fn: C_GlobalFunction): C_NsDef() {
    override fun type() = C_DeclarationType.FUNCTION

    override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        b.addFunction(name, fn)
    }
}

private class C_NsDef_UserFunction(private val fn: C_UserGlobalFunction): C_NsDef() {
    override fun type() = C_DeclarationType.FUNCTION

    override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        b.addFunction(name, fn)
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        val rFn = fn.rFunction
        b.functions.add(rFn.moduleLevelName, rFn)
    }
}

private class C_NsDef_Operation(private val op: R_Operation): C_NsDef() {
    override fun type() = C_DeclarationType.OPERATION

    override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        // Do nothing.
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.operations.add(op.moduleLevelName, op)
    }
}

private class C_NsDef_Query(private val q: R_Query): C_NsDef() {
    override fun type() = C_DeclarationType.QUERY

    override fun addToNamespace(b: C_NamespaceBuilder, name: String) {
        // Do nothing.
    }

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.queries.add(q.moduleLevelName, q)
    }
}

sealed class C_NsProtoBuilder {
    private val entries = mutableListOf<C_NsEntry>()

    protected var completed = false
        private set(value) {
            check(!field)
            check(value)
            field = value
        }

    protected fun addDef(name: String, sName: S_Name?, def: C_NsDef, privateAccess: Boolean) {
        check(!completed)
        entries.add(C_NsEntry(name, sName, privateAccess, def))
    }

    protected fun build0(): List<C_NsEntry> {
        check(!completed)
        completed = true
        return entries.toImmList()
    }
}

class C_SysNsProto(entries: List<C_NsEntry>) {
    val entries = entries.toImmList()
}

class C_SysNsProtoBuilder: C_NsProtoBuilder() {
    private fun addDef(name: String, def: C_NsDef, privateAccess: Boolean = true) {
        addDef(name, null, def, privateAccess)
    }

    fun addNamespace(name: String, ns: C_NamespaceDef) {
        addDef(name, C_NsDef_SysNamespace(ns))
    }

    fun addType(name: String, type: C_TypeDef) {
        addDef(name, C_NsDef_Type(type))
    }

    fun addEntity(name: String, entity: R_Entity, privateAccess: Boolean) {
        addDef(name, C_NsDef_SysEntity(entity), privateAccess)
    }

    fun addFunction(name: String, fn: C_GlobalFunction) {
        addDef(name, C_NsDef_SysFunction(fn))
    }

    fun build(): C_SysNsProto {
        val entries = build0()
        return C_SysNsProto(entries)
    }
}

class C_UserNsProto(entries: List<C_NsEntry>, namespaces: List<C_UserNsNamespace>) {
    private val entries = entries.toImmList()
    private val namespaces = namespaces.toImmList()

    fun makeEntries(globalCtx: C_GlobalContext): List<C_NsEntry> {
        val resEntries = mutableListOf<C_NsEntry>()
        resEntries.addAll(entries)

        for (subNs in namespaces) {
            val entry = subNs.makeNsEntry(globalCtx)
            resEntries.add(entry)
        }

        return resEntries.toImmList()
    }

    companion object {
        val EMPTY = C_UserNsProto(listOf(), listOf())
    }
}

class C_UserNsNamespace(
        private val name: S_Name,
        private val proto: C_UserNsProto,
        private val setter: Setter<C_Namespace>
) {
    fun makeNsEntry(globalCtx: C_GlobalContext): C_NsEntry {
        val entries = proto.makeEntries(globalCtx)

        val errEntries = mutableSetOf<C_NsEntry>()
        val goodEntries = C_NsEntry.processNameConflicts(globalCtx, entries, errEntries)

        val ns = C_NsEntry.createNamespace(goodEntries)
        setter(ns)

        val modDefs = C_NsEntry.createModuleDefs(goodEntries)

        val def = C_NsDef_UserNamespace(C_RegularNamespaceDef(ns), modDefs)
        return C_NsEntry(name.str, name, false, def)
    }
}

class C_UserNsProtoBuilder: C_NsProtoBuilder() {
    private val namespaces = mutableMapOf<String, C_SubNamespace>()

    private fun addDef(name: S_Name, def: C_NsDef, privateAccess: Boolean = false) {
        addDef(name.str, name, def, privateAccess)
    }

    fun addImport(name: S_Name, module: C_Module) {
        addDef(name, C_NsDef_Import(module), true)
    }

    fun addNamespace(name: S_Name): Pair<C_UserNsProtoBuilder, Getter<C_Namespace>> {
        check(!completed)
        val subNs = namespaces.computeIfAbsent(name.str) { C_SubNamespace(name) }
        return Pair(subNs.builder, subNs.namespace.getter)
    }

    fun addEntity(name: S_Name, entity: R_Entity, addToModule: Boolean = true) {
        addDef(name, C_NsDef_UserEntity(C_Entity(name.pos, entity), addToModule))
    }

    fun addObject(name: S_Name, obj: R_Object) {
        addDef(name, C_NsDef_Object(obj))
    }

    fun addStruct(name: S_Name, struct: R_Struct) {
        addDef(name, C_NsDef_Struct(C_Struct(name, struct)))
    }

    fun addEnum(name: S_Name, e: R_Enum) {
        addDef(name, C_NsDef_Enum(e))
    }

    fun addFunction(name: S_Name, fn: C_UserGlobalFunction) {
        addDef(name, C_NsDef_UserFunction(fn))
    }

    fun addOperation(name: S_Name, operation: R_Operation) {
        addDef(name, C_NsDef_Operation(operation))
    }

    fun addQuery(name: S_Name, query: R_Query) {
        addDef(name, C_NsDef_Query(query))
    }

    fun build(): C_UserNsProto {
        val protoEntries = build0()

        val protoNamespaces = namespaces.values.map { subNs ->
            val subProto = subNs.builder.build()
            C_UserNsNamespace(subNs.name, subProto, subNs.namespace.setter)
        }

        return C_UserNsProto(protoEntries, protoNamespaces)
    }

    private class C_SubNamespace(val name: S_Name) {
        val builder = C_UserNsProtoBuilder()
        val namespace = C_LateInit(C_CompilerPass.NAMESPACES, C_Namespace.EMPTY)
    }
}
