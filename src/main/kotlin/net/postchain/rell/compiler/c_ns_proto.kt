package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.model.*
import net.postchain.rell.toImmList

class C_NsEntry(val name: String, val def: C_NsDef) {
    fun addToNamespace(nsBuilder: C_NamespaceBuilder) {
        val elem = def.toNamespaceElement()
        nsBuilder.add(name, elem)
    }

    companion object {
        fun createNamespace(entries: List<C_NsEntry>): C_Namespace {
            val nsBuilder = C_NamespaceBuilder()
            for (entry in entries) {
                entry.addToNamespace(nsBuilder)
            }
            return nsBuilder.build()
        }
    }
}

sealed class C_NsDef {
    abstract fun type(): C_DeclarationType
    abstract fun toNamespaceElement(): C_NamespaceElement

    open fun addToDefs(b: C_ModuleDefsBuilder) {
    }
}

sealed class C_NsDef_Namespace(private val ns: C_DefProxy<C_Namespace>): C_NsDef() {
    final override fun type() = C_DeclarationType.NAMESPACE

    final override fun toNamespaceElement() = C_NamespaceElement.create(
            namespace = ns,
            value = C_NamespaceValue_Namespace(ns)
    )
}

private class C_NsDef_SysNamespace(ns: C_DefProxy<C_Namespace>): C_NsDef_Namespace(ns)

class C_NsDef_UserNamespace(ns: C_DefProxy<C_Namespace>): C_NsDef_Namespace(ns)

private class C_NsDef_Type(private val type: C_DefProxy<R_Type>): C_NsDef() {
    override fun type() = C_DeclarationType.TYPE
    override fun toNamespaceElement() = C_NamespaceElement(type = type)
}

private sealed class C_NsDef_Entity(private val entity: R_Entity): C_NsDef() {
    final override fun type() = C_DeclarationType.ENTITY

    final override fun toNamespaceElement(): C_NamespaceElement {
        val typeRef = C_DefProxy.create(entity.type)
        return C_NamespaceElement.create(type = typeRef, value = C_NamespaceValue_Entity(typeRef))
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
    override fun toNamespaceElement() = C_NamespaceElement.create(value = C_NamespaceValue_Object(obj))

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.objects.add(obj.moduleLevelName, obj)
    }
}

private class C_NsDef_Struct(private val struct: C_Struct): C_NsDef() {
    override fun type() = C_DeclarationType.STRUCT

    override fun toNamespaceElement() = C_NamespaceElement.create(
            type = C_DefProxy.create(struct.struct.type),
            value = C_NamespaceValue_Struct(struct.struct),
            function = C_StructGlobalFunction(struct.struct)
    )

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.structs.add(struct.struct.moduleLevelName, struct)
    }
}

private class C_NsDef_Enum(private val e: R_Enum): C_NsDef() {
    override fun type() = C_DeclarationType.ENUM

    override fun toNamespaceElement() = C_NamespaceElement.create(
            type = C_DefProxy.create(e.type),
            value = C_NamespaceValue_Enum(e)
    )

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.enums.add(e.moduleLevelName, e)
    }
}

private sealed class C_NsDef_Function(private val fn: C_GlobalFunction): C_NsDef() {
    final override fun type() = C_DeclarationType.FUNCTION
    final override fun toNamespaceElement() = C_NamespaceElement.create(function = fn)
}

private class C_NsDef_SysFunction(fn: C_GlobalFunction): C_NsDef_Function(fn)

private class C_NsDef_UserFunction(private val userFn: C_UserGlobalFunction): C_NsDef_Function(userFn) {
    override fun addToDefs(b: C_ModuleDefsBuilder) {
        val rFn = userFn.rFunction
        b.functions.add(rFn.moduleLevelName, rFn)
    }
}

private class C_NsDef_Operation(private val op: R_Operation): C_NsDef() {
    override fun type() = C_DeclarationType.OPERATION
    override fun toNamespaceElement() = C_NamespaceElement()

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.operations.add(op.moduleLevelName, op)
    }
}

private class C_NsDef_Query(private val q: R_Query): C_NsDef() {
    override fun type() = C_DeclarationType.QUERY
    override fun toNamespaceElement() = C_NamespaceElement()

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.queries.add(q.moduleLevelName, q)
    }
}

class C_SysNsProto(entries: List<C_NsEntry>, entities: List<C_NsEntry>) {
    val entries = entries.toImmList()
    val entities = entities.toImmList()
}

class C_SysNsProtoBuilder {
    private val entries = mutableListOf<C_NsEntry>()
    private val entities = mutableListOf<C_NsEntry>()

    private var completed = false
        private set(value) {
            check(!field)
            check(value)
            field = value
        }

    private fun addDef(name: String, def: C_NsDef): C_NsEntry {
        check(!completed)
        val entry = C_NsEntry(name, def)
        entries.add(entry)
        return entry
    }

    fun addNamespace(name: String, ns: C_DefProxy<C_Namespace>) {
        addDef(name, C_NsDef_SysNamespace(ns))
    }

    fun addType(name: String, type: C_DefProxy<R_Type>) {
        addDef(name, C_NsDef_Type(type))
    }

    fun addEntity(name: String, entity: R_Entity) {
        val entry = addDef(name, C_NsDef_SysEntity(entity))
        entities.add(entry)
    }

    fun addFunction(name: String, fn: C_GlobalFunction) {
        addDef(name, C_NsDef_SysFunction(fn))
    }

    fun build(): C_SysNsProto {
        check(!completed)
        completed = true
        return C_SysNsProto(entries, entities)
    }
}

class C_UserNsProtoBuilder(private val assembler: C_NsAsm_ComponentAssembler) {
    fun futureNs() = assembler.futureNs()

    private fun addDef(name: S_Name, def: C_NsDef) {
        assembler.addDef(name, def)
    }

    fun addNamespace(name: S_Name, expandable: Boolean): C_UserNsProtoBuilder {
        val subAssembler = assembler.addNamespace(name, expandable)
        return C_UserNsProtoBuilder(subAssembler)
    }

    fun addModuleImport(alias: S_Name, module: C_ModuleKey) {
        assembler.addModuleImport(alias, module)
    }

    fun addExactImport(alias: S_Name, module: C_ModuleKey, path: List<S_Name>, name: S_Name) {
        assembler.addExactImport(alias, module, path, name)
    }

    fun addWildcardImport(module: C_ModuleKey, path: List<S_Name>) {
        assembler.addWildcardImport(module, path)
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
}
