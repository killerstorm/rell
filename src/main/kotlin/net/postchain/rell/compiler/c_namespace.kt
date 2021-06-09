/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.V_ConstantExpr
import net.postchain.rell.compiler.vexpr.V_RExpr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.LateGetter
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import org.apache.commons.lang3.StringUtils
import java.util.function.Supplier

class C_Deprecated(
        private val useInstead: String,
        val error: Boolean = false
) {
    fun detailsCode(): String {
        return ":$useInstead"
    }

    fun detailsMessage(): String {
        return ", use '$useInstead' instead"
    }
}

enum class C_DeclarationType(val msg: String) {
    MODULE("module"),
    NAMESPACE("namespace"),
    TYPE("type"),
    ENTITY("entity"),
    STRUCT("struct"),
    ENUM("enum"),
    OBJECT("object"),
    FUNCTION("function"),
    OPERATION("operation"),
    QUERY("query"),
    IMPORT("import")
    ;

    val capitalizedMsg = StringUtils.capitalize(msg)
}

class C_DefProxyDeprecation(val type: C_DeclarationType, val deprecated: C_Deprecated)

class C_DefProxy<T> private constructor(
        private val supplier: Supplier<T>,
        private val ambiguous: Boolean = false,
        private val deprecation: C_DefProxyDeprecation? = null
) {
    fun getDef(msgCtx: C_MessageContext, name: List<S_Name>): T {
        val res = supplier.get()

        if (ambiguous) {
            val lastName = name.last()
            val qName = C_Utils.nameStr(name)
            msgCtx.error(lastName.pos, "name:ambig:$qName", "Name '$qName' is ambiguous")
        }

        if (deprecation != null) {
            val simpleName = name.last()
            deprecatedMessage(msgCtx, simpleName.pos, simpleName.str, deprecation)
        }

        return res
    }

    fun getDefQuiet(): T = supplier.get()

    fun update(ambiguous: Boolean? = null, deprecation: C_DefProxyDeprecation? = null): C_DefProxy<T> {
        val ambiguous2 = ambiguous ?: this.ambiguous
        return if (ambiguous2 == this.ambiguous && deprecation === this.deprecation) this else
            C_DefProxy(supplier, ambiguous2, deprecation)
    }

    companion object {
        fun <T> create(def: T): C_DefProxy<T> = C_DefProxy(Supplier { def })
        fun <T> createGetter(getter: LateGetter<T>): C_DefProxy<T> = C_DefProxy(getter)

        fun create(type: R_Type, deprecated: C_Deprecated? = null): C_DefProxy<R_Type> {
            return create(type, C_DeclarationType.TYPE, deprecated)
        }

        fun create(namespace: C_Namespace, deprecated: C_Deprecated? = null): C_DefProxy<C_Namespace> {
            return create(namespace, C_DeclarationType.NAMESPACE, deprecated)
        }

        fun <T> create(def: T, type: C_DeclarationType, deprecated: C_Deprecated?): C_DefProxy<T> {
            val supplier = Supplier { def }
            val deprecation = if (deprecated == null) null else C_DefProxyDeprecation(type, deprecated)
            return C_DefProxy(supplier, deprecation = deprecation)
        }

        fun <T> create(def: T, deprecation: C_DefProxyDeprecation?): C_DefProxy<T> {
            return C_DefProxy(Supplier { def }, deprecation = deprecation)
        }

        fun <T> create(supplier: Supplier<T>, deprecation: C_DefProxyDeprecation?): C_DefProxy<T> {
            return C_DefProxy(supplier, deprecation = deprecation)
        }

        fun deprecatedMessage(
                msgCtx: C_MessageContext,
                pos: S_Pos,
                nameMsg: String,
                deprecation: C_DefProxyDeprecation
        ) {
            val type = deprecation.type
            val deprecated = deprecation.deprecated

            val typeStr = StringUtils.capitalize(type.msg)
            val depCode = deprecated.detailsCode()
            val depStr = deprecated.detailsMessage()
            val code = "deprecated:$type:$nameMsg$depCode"
            val msg = "$typeStr '$nameMsg' is deprecated$depStr"

            val error = deprecated.error || msgCtx.globalCtx.compilerOptions.deprecatedError
            val msgType = if (error) C_MessageType.ERROR else C_MessageType.WARNING

            msgCtx.message(msgType, pos, code, msg)
        }
    }
}

class C_DefRef<T>(
        val msgCtx: C_MessageContext,
        name: List<S_Name>,
        val proxy: C_DefProxy<T>
) {
    val name = name.toImmList()

    init {
        check(this.name.isNotEmpty())
    }

    fun getDef(): T {
        val res = proxy.getDef(msgCtx, name)
        return res
    }

    fun <R> sub(subName: S_Name, subProxy: C_DefProxy<R>) = C_DefRef(msgCtx, name + subName, subProxy)
}

class C_NamespaceRef(
        private val msgCtx: C_MessageContext,
        private val path: List<S_Name>,
        private val ns: C_Namespace
) {
    fun namespace(name: S_Name) = get(name, C_Namespace::namespace)
    fun type(name: S_Name) = get(name, C_Namespace::type)
    fun value(name: S_Name) = get(name, C_Namespace::value)
    fun function(name: S_Name) = get(name, C_Namespace::function)

    private fun <T> get(name: S_Name, getter: (C_Namespace, String) -> C_DefProxy<T>?): C_DefRef<T>? {
        val proxy = getter(ns, name.str)
        return wrap(name, proxy)
    }

    private fun <T> wrap(name: S_Name, proxy: C_DefProxy<T>?): C_DefRef<T>? {
        return if (proxy == null) null else C_DefRef(msgCtx, path + name, proxy)
    }

    companion object {
        fun create(msgCtx: C_MessageContext, path: List<S_Name>, proxy: C_DefProxy<C_Namespace>): C_NamespaceRef {
            return C_NamespaceRef(msgCtx, path, proxy.getDef(msgCtx, path))
        }

        fun create(ref: C_DefRef<C_Namespace>): C_NamespaceRef {
            val ns = ref.getDef()
            return C_NamespaceRef(ref.msgCtx, ref.name, ns)
        }
    }
}

class C_Namespace(
        namespaces: Map<String, C_DefProxy<C_Namespace>>,
        types: Map<String, C_DefProxy<R_Type>>,
        values: Map<String, C_DefProxy<C_NamespaceValue>>,
        functions: Map<String, C_DefProxy<C_GlobalFunction>>
) {
    private val namespaces = namespaces.toImmMap()
    private val types = types.toImmMap()
    private val values = values.toImmMap()
    private val functions = functions.toImmMap()

    fun namespace(name: String) = namespaces[name]
    fun type(name: String) = types[name]
    fun value(name: String) = values[name]
    fun function(name: String) = functions[name]

    fun addTo(b: C_NamespaceBuilder) {
        namespaces.forEach { b.addNamespace(it.key, it.value) }
        types.forEach { b.addType(it.key, it.value) }
        values.forEach { b.addValue(it.key, it.value) }
        functions.forEach { b.addFunction(it.key, it.value) }
    }

    companion object {
        val EMPTY = C_Namespace(namespaces = mapOf(), types = mapOf(), values = mapOf(), functions = mapOf())
    }
}

class C_NamespaceElement(
        val namespace: C_DefProxy<C_Namespace>? = null,
        val type: C_DefProxy<R_Type>? = null,
        val value: C_DefProxy<C_NamespaceValue>? = null,
        val function: C_DefProxy<C_GlobalFunction>? = null
) {
    companion object {
        fun create(
                namespace: C_DefProxy<C_Namespace>? = null,
                type: C_DefProxy<R_Type>? = null,
                value: C_NamespaceValue? = null,
                function: C_GlobalFunction? = null
        ): C_NamespaceElement {
            return C_NamespaceElement(
                    namespace = namespace,
                    type = type,
                    value = if (value == null) null else C_DefProxy.create(value),
                    function = if (function == null) null else C_DefProxy.create(function)
            )
        }
    }
}

class C_NamespaceBuilder {
    private val namespaces = mutableMapOf<String, C_DefProxy<C_Namespace>>()
    private val types = mutableMapOf<String, C_DefProxy<R_Type>>()
    private val values = mutableMapOf<String, C_DefProxy<C_NamespaceValue>>()
    private val functions = mutableMapOf<String, C_DefProxy<C_GlobalFunction>>()

    fun add(name: String, elem: C_NamespaceElement) {
        if (elem.namespace != null) addDef(namespaces, name, elem.namespace)
        if (elem.type != null) addDef(types, name, elem.type)
        if (elem.value != null) addDef(values, name, elem.value)
        if (elem.function != null) addDef(functions, name, elem.function)
    }

    fun addNamespace(name: String, ns: C_DefProxy<C_Namespace>) = addDef(namespaces, name, ns)
    fun addType(name: String, type: C_DefProxy<R_Type>) = addDef(types, name, type)
    fun addValue(name: String, value: C_DefProxy<C_NamespaceValue>) = addDef(values, name, value)
    fun addFunction(name: String, function: C_DefProxy<C_GlobalFunction>) = addDef(functions, name, function)

    private fun <T> addDef(map: MutableMap<String, C_DefProxy<T>>, name: String, def: C_DefProxy<T>) {
        check(name !in map) { "$name ${map.keys.sorted()}" }
        map[name] = def
    }

    fun build(): C_Namespace {
        return C_Namespace(namespaces.toMap(), types.toMap(), values.toMap(), functions.toMap())
    }
}

class C_NamespaceValueContext(val exprCtx: C_ExprContext) {
    val defCtx = exprCtx.defCtx
    val globalCtx = defCtx.globalCtx
    val msgCtx = defCtx.msgCtx
    val modCtx = defCtx.modCtx
}

abstract class C_NamespaceValue {
    abstract fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr
}

abstract class C_NamespaceValue_RExpr: C_NamespaceValue() {
    abstract fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr

    override final fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        val rExpr = toExpr0(ctx, name)
        return V_RExpr.makeExpr(ctx.exprCtx, name[0].pos, rExpr)
    }
}

class C_NamespaceValue_Value(private val value: Rt_Value): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        val vExpr = V_ConstantExpr(ctx.exprCtx, name[0].pos, value)
        return C_VExpr(vExpr)
    }
}

class C_NamespaceValue_SysFunction(
        private val resultType: R_Type,
        private val fn: R_SysFunction
): C_NamespaceValue_RExpr() {
    override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr {
        return C_Utils.createSysCallExpr(resultType, fn, listOf(), name)
    }
}

class C_NamespaceValue_Entity(private val typeProxy: C_DefProxy<R_Type>): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        val typeRef = C_DefRef(ctx.msgCtx, name, typeProxy)
        return C_TypeNameExpr(name.last().pos, typeRef)
    }
}

class C_NamespaceValue_Enum(private val rEnum: R_EnumDefinition): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>) = C_EnumExpr(ctx.msgCtx, name, rEnum)
}

class C_NamespaceValue_Namespace(private val nsProxy: C_DefProxy<C_Namespace>): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        val nsRef = C_NamespaceRef.create(ctx.msgCtx, name, nsProxy)
        return C_NamespaceExpr(name, nsRef)
    }
}

class C_NamespaceValue_Object(val rObject: R_ObjectDefinition): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        return C_ObjectExpr(ctx.exprCtx, name, rObject)
    }
}

class C_NamespaceValue_Struct(private val struct: R_Struct): C_NamespaceValue() {
    override fun toExpr(ctx: C_NamespaceValueContext, name: List<S_Name>): C_Expr {
        val ns = C_LibFunctions.makeStructNamespace(struct)
        val nsProxy = C_DefProxy.create(ns)
        val nsRef = C_NamespaceRef.create(ctx.msgCtx, name, nsProxy)
        return C_NamespaceStructExpr(name, struct, nsRef)
    }
}
