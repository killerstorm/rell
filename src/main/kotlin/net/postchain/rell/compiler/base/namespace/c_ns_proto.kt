/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.namespace

import net.postchain.rell.compiler.base.core.C_Entity
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.core.C_NameHandle
import net.postchain.rell.compiler.base.core.C_QualifiedNameHandle
import net.postchain.rell.compiler.base.def.*
import net.postchain.rell.compiler.base.module.C_ModuleDefsBuilder
import net.postchain.rell.compiler.base.module.C_ModuleKey
import net.postchain.rell.model.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.toImmList

class C_NsEntry(val name: R_Name, val def: C_NsDef) {
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

sealed class C_NsDef(val ideInfo: IdeSymbolInfo) {
    abstract fun type(): C_DeclarationType
    abstract fun toNamespaceElement(): C_NamespaceElement

    open fun addToDefs(b: C_ModuleDefsBuilder) {
    }

    companion object {
        fun toNamespaceElement(struct: R_Struct, ideInfo: IdeSymbolInfo) = C_NamespaceElement.create(
                type = C_DefProxy.create(struct.type, ideInfo),
                value = C_NamespaceValue_Struct(ideInfo, struct),
                function = C_StructGlobalFunction(struct, ideInfo)
        )
    }
}

sealed class C_NsDef_Namespace(private val ns: C_DefProxy<C_Namespace>): C_NsDef(ns.ideInfo) {
    final override fun type() = C_DeclarationType.NAMESPACE

    final override fun toNamespaceElement() = C_NamespaceElement.create(
            namespace = ns,
            value = C_NamespaceValue_Namespace(ns)
    )
}

private class C_NsDef_SysNamespace(ns: C_DefProxy<C_Namespace>): C_NsDef_Namespace(ns)

class C_NsDef_UserNamespace(ns: C_DefProxy<C_Namespace>): C_NsDef_Namespace(ns)

private class C_NsDef_Type(private val type: C_DefProxy<R_Type>): C_NsDef(type.ideInfo) {
    override fun type() = C_DeclarationType.TYPE
    override fun toNamespaceElement() = C_NamespaceElement(type = type)
}

private sealed class C_NsDef_Entity(
        ideInfo: IdeSymbolInfo,
        private val entity: R_EntityDefinition
): C_NsDef(ideInfo) {
    final override fun type() = C_DeclarationType.ENTITY

    final override fun toNamespaceElement(): C_NamespaceElement {
        val ideInfo = IdeSymbolInfo(IdeSymbolKind.DEF_ENTITY)
        val type = entity.type
        val typeProxy = C_DefProxy.create(type, ideInfo)
        return C_NamespaceElement.create(type = typeProxy, value = C_NamespaceValue_Entity(ideInfo, type))
    }
}

private class C_NsDef_SysEntity(
        ideInfo: IdeSymbolInfo,
        entity: R_EntityDefinition
): C_NsDef_Entity(ideInfo, entity)

private class C_NsDef_UserEntity(
        ideInfo: IdeSymbolInfo,
        private val entity: C_Entity,
        private val addToModule: Boolean
): C_NsDef_Entity(ideInfo, entity.entity) {
    override fun addToDefs(b: C_ModuleDefsBuilder) {
        if (addToModule) {
            b.entities.add(entity.entity.moduleLevelName, entity.entity)
        }
    }
}

private class C_NsDef_Object(
        ideInfo: IdeSymbolInfo,
        private val obj: R_ObjectDefinition
): C_NsDef(ideInfo) {
    override fun type() = C_DeclarationType.OBJECT
    override fun toNamespaceElement() = C_NamespaceElement.create(value = C_NamespaceValue_Object(ideInfo, obj))

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.objects.add(obj.moduleLevelName, obj)
    }
}

private sealed class C_NsDef_Struct(
        ideInfo: IdeSymbolInfo,
        private val rStruct: R_Struct
): C_NsDef(ideInfo) {
    final override fun type() = C_DeclarationType.STRUCT
    final override fun toNamespaceElement() = toNamespaceElement(rStruct, ideInfo)
}

private class C_NsDef_SysStruct(
        ideInfo: IdeSymbolInfo,
        struct: R_Struct
): C_NsDef_Struct(ideInfo, struct)

private class C_NsDef_UserStruct(
        ideInfo: IdeSymbolInfo,
        private val struct: C_Struct
): C_NsDef_Struct(ideInfo, struct.structDef.struct) {
    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.structs.add(struct.structDef.moduleLevelName, struct)
    }
}

private class C_NsDef_Enum(
        ideInfo: IdeSymbolInfo,
        private val e: R_EnumDefinition
): C_NsDef(ideInfo) {
    override fun type() = C_DeclarationType.ENUM

    override fun toNamespaceElement() = C_NamespaceElement.create(
            type = C_DefProxy.create(e.type, IdeSymbolInfo(IdeSymbolKind.DEF_ENUM)),
            value = C_NamespaceValue_Enum(ideInfo, e)
    )

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        b.enums.add(e.moduleLevelName, e)
    }
}

private sealed class C_NsDef_Function(private val fn: C_GlobalFunction): C_NsDef(fn.ideInfo) {
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

private class C_NsDef_Operation(
        ideInfo: IdeSymbolInfo,
        private val cOp: C_OperationGlobalFunction
): C_NsDef(ideInfo) {
    override fun type() = C_DeclarationType.OPERATION
    override fun toNamespaceElement() = C_NamespaceElement.create(function = cOp)

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        val op = cOp.rOp
        b.operations.add(op.moduleLevelName, op)
    }
}

private class C_NsDef_Query(
        ideInfo: IdeSymbolInfo,
        private val cQuery: C_QueryGlobalFunction
): C_NsDef(ideInfo) {
    override fun type() = C_DeclarationType.QUERY
    override fun toNamespaceElement() = C_NamespaceElement.create(function = cQuery)

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        val q = cQuery.rQuery
        b.queries.add(q.moduleLevelName, q)
    }
}

private class C_NsDef_GlobalConstant(
        ideInfo: IdeSymbolInfo,
        private val cDef: C_GlobalConstantDefinition
): C_NsDef(ideInfo) {
    override fun type() = C_DeclarationType.CONSTANT
    override fun toNamespaceElement() = C_NamespaceElement.create(value = C_NamespaceValue_GlobalConstant(ideInfo, cDef))

    override fun addToDefs(b: C_ModuleDefsBuilder) {
        val rDef = cDef.rDef
        b.constants.add(rDef.moduleLevelName, rDef)
    }
}

private class C_NsDef_Property(private val value: C_NamespaceValue): C_NsDef(value.ideInfo) {
    override fun type() = C_DeclarationType.PROPERTY
    override fun toNamespaceElement() = C_NamespaceElement.create(value = value)
}

class C_SysNsProto(entries: List<C_NsEntry>, entities: List<C_NsEntry>) {
    val entries = entries.toImmList()
    val entities = entities.toImmList()

    fun toNamespace(): C_Namespace {
        return C_NsEntry.createNamespace(entries)
    }
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

    fun addAll(nsProto: C_SysNsProto) {
        check(!completed)
        entries.addAll(nsProto.entries)
        entities.addAll(nsProto.entities)
    }

    private fun addDef(name: R_Name, def: C_NsDef): C_NsEntry {
        check(!completed)
        val entry = C_NsEntry(name, def)
        entries.add(entry)
        return entry
    }

    fun addNamespace(name: R_Name, ns: C_DefProxy<C_Namespace>) {
        addDef(name, C_NsDef_SysNamespace(ns))
    }

    fun addNamespace(name: String, ns: C_Namespace) {
        val rName = R_Name.of(name)
        addNamespace(rName, C_DefProxy.create(ns))
    }

    fun addType(name: String, type: R_Type) {
        val rName = R_Name.of(name)
        val proxy = C_DefProxy.create(type, IdeSymbolInfo.DEF_TYPE)
        addType(rName, proxy)
    }

    fun addType(name: R_Name, type: C_DefProxy<R_Type>) {
        addDef(name, C_NsDef_Type(type))
    }

    fun addEntity(name: R_Name, entity: R_EntityDefinition, ideInfo: IdeSymbolInfo) {
        val entry = addDef(name, C_NsDef_SysEntity(ideInfo, entity))
        entities.add(entry)
    }

    fun addStruct(name: String, struct: R_Struct, ideInfo: IdeSymbolInfo) {
        val rName = R_Name.of(name)
        addDef(rName, C_NsDef_SysStruct(ideInfo, struct))
    }

    fun addFunction(name: R_Name, fn: C_GlobalFunction) {
        addDef(name, C_NsDef_SysFunction(fn))
    }

    fun addProperty(name: String, value: C_NamespaceValue) {
        val rName = R_Name.of(name)
        addDef(rName, C_NsDef_Property(value))
    }

    fun build(): C_SysNsProto {
        check(!completed)
        completed = true
        return C_SysNsProto(entries, entities)
    }
}

class C_UserNsProtoBuilder(private val assembler: C_NsAsm_ComponentAssembler) {
    fun futureNs() = assembler.futureNs()

    private fun addDef(name: C_Name, def: C_NsDef) {
        assembler.addDef(name, def)
    }

    fun addNamespace(name: C_Name, expandable: Boolean): C_UserNsProtoBuilder {
        val subAssembler = assembler.addNamespace(name, expandable)
        return C_UserNsProtoBuilder(subAssembler)
    }

    fun addModuleImport(alias: C_Name, module: C_ModuleKey, ideInfo: IdeSymbolInfo) {
        assembler.addModuleImport(alias, module, ideInfo)
    }

    fun addExactImport(alias: C_Name, module: C_ModuleKey, qNameHand: C_QualifiedNameHandle, aliasHand: C_NameHandle?) {
        assembler.addExactImport(alias, module, qNameHand, aliasHand)
    }

    fun addWildcardImport(module: C_ModuleKey, path: List<C_NameHandle>) {
        assembler.addWildcardImport(module, path)
    }

    fun addEntity(name: C_Name, entity: R_EntityDefinition, ideInfo: IdeSymbolInfo, addToModule: Boolean = true) {
        val cEntity = C_Entity(name.pos, entity)
        addDef(name, C_NsDef_UserEntity(ideInfo, cEntity, addToModule))
    }

    fun addObject(name: C_Name, obj: R_ObjectDefinition, ideInfo: IdeSymbolInfo) {
        addDef(name, C_NsDef_Object(ideInfo, obj))
    }

    fun addStruct(cStruct: C_Struct, ideInfo: IdeSymbolInfo) {
        addDef(cStruct.name, C_NsDef_UserStruct(ideInfo, cStruct))
    }

    fun addEnum(name: C_Name, e: R_EnumDefinition, ideInfo: IdeSymbolInfo) {
        addDef(name, C_NsDef_Enum(ideInfo, e))
    }

    fun addFunction(name: C_Name, fn: C_UserGlobalFunction) {
        addDef(name, C_NsDef_UserFunction(fn))
    }

    fun addOperation(name: C_Name, operation: C_OperationGlobalFunction, ideInfo: IdeSymbolInfo) {
        addDef(name, C_NsDef_Operation(ideInfo, operation))
    }

    fun addQuery(name: C_Name, query: C_QueryGlobalFunction, ideInfo: IdeSymbolInfo) {
        addDef(name, C_NsDef_Query(ideInfo, query))
    }

    fun addConstant(name: C_Name, c: C_GlobalConstantDefinition, ideInfo: IdeSymbolInfo) {
        addDef(name, C_NsDef_GlobalConstant(ideInfo, c))
    }
}
