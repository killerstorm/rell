package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value

class C_Namespace(
        val namespaces: Map<String, C_Namespace>,
        val types: Map<String, R_Type>,
        val values: Map<String, C_NamespaceValue>,
        val functions: Map<String, C_GlobalFunction>
)

class C_NamespaceBuilder {
    private val namespaces = mutableMapOf<String, C_Namespace>()
    private val types = mutableMapOf<String, R_Type>()
    private val values = mutableMapOf<String, C_NamespaceValue>()
    private val functions = mutableMapOf<String, C_GlobalFunction>()

    fun addNamespace(name: String, ns: C_Namespace) {
        check(name !in namespaces)
        namespaces[name] = ns
    }

    fun addType(name: String, type: R_Type) {
        check(name !in types)
        types[name] = type
    }

    fun addValue(name: String, value: C_NamespaceValue) {
        check(name !in values)
        values[name] = value
    }

    fun addFunction(name: String, fn: C_GlobalFunction) {
        check(name !in functions)
        functions[name] = fn
    }

    fun build(): C_Namespace {
        return C_Namespace(namespaces.toMap(), types.toMap(), values.toMap(), functions.toMap())
    }
}

abstract class C_NamespaceValue {
    abstract fun get(entCtx: C_EntityContext, name: List<S_Name>): C_Expr
}

abstract class C_NamespaceValue_RExpr: C_NamespaceValue() {
    abstract fun get0(entCtx: C_EntityContext, name: List<S_Name>): R_Expr

    override final fun get(entCtx: C_EntityContext, name: List<S_Name>): C_Expr {
        val rExpr = get0(entCtx, name)
        return C_RValue.makeExpr(name[0].pos, rExpr)
    }
}

class C_NamespaceValue_Value(private val value: Rt_Value): C_NamespaceValue_RExpr() {
    override fun get0(entCtx: C_EntityContext, name: List<S_Name>) = R_ConstantExpr(value)
}

class C_NamespaceValue_SysFunction(
        private val resultType: R_Type,
        private val fn: R_SysFunction
): C_NamespaceValue_RExpr() {
    override fun get0(entCtx: C_EntityContext, name: List<S_Name>) = R_SysCallExpr(resultType, fn, listOf())
}

class C_NamespaceValue_Enum(private val rEnum: R_EnumType): C_NamespaceValue() {
    override fun get(entCtx: C_EntityContext, name: List<S_Name>) = C_EnumExpr(name, rEnum)
}

class C_NamespaceValue_Namespace(private val ns: C_Namespace): C_NamespaceValue() {
    override fun get(entCtx: C_EntityContext, name: List<S_Name>) = C_NamespaceExpr(name, ns)
}

class C_NamespaceValue_Object(private val rObject: R_Object): C_NamespaceValue() {
    override fun get(entCtx: C_EntityContext, name: List<S_Name>): C_Expr {
        if (rObject.entityIndex >= entCtx.entityIndex && entCtx.entityType == C_EntityType.OBJECT) {
            val nameStr = C_Utils.nameStr(name)
            throw C_Error(name[0].pos, "object_fwdref:$nameStr", "Object '$nameStr' must be defined before using")
        }
        return C_ObjectExpr(name, rObject)
    }
}

class C_NamespaceValue_Record(private val record: R_RecordType): C_NamespaceValue() {
    override fun get(entCtx: C_EntityContext, name: List<S_Name>): C_Expr {
        val ns = C_LibFunctions.makeRecordNamespace(record)
        return C_RecordExpr(name, record, ns)
    }
}
