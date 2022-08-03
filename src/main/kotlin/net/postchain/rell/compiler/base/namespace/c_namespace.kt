/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.namespace

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.def.*
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.utils.C_MessageType
import net.postchain.rell.compiler.vexpr.V_ConstantValueExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.lib.type.C_Lib_Type_Struct
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.LateGetter
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immMapOf
import net.postchain.rell.utils.toImmMap

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
    IMPORT("import"),
    CONSTANT("constant"),
    PROPERTY("property"),
    ;

    val capitalizedMsg = msg.capitalize()
}

class C_DefProxyDeprecation(val type: C_DeclarationType, val deprecated: C_Deprecated)

class C_DefProxy<T> private constructor(
        private val def: T,
        val ideInfo: IdeSymbolInfo,
        private val ambiguous: Boolean = false,
        private val deprecation: C_DefProxyDeprecation? = null
) {
    fun getDef(msgCtx: C_MessageContext, name: C_QualifiedName): T {
        access(msgCtx, name)
        return def
    }

    fun getDefQuiet(): T = def

    fun update(
            ideInfo: IdeSymbolInfo = this.ideInfo,
            ambiguous: Boolean = this.ambiguous,
            deprecation: C_DefProxyDeprecation? = this.deprecation
    ): C_DefProxy<T> {
        return if (ideInfo === this.ideInfo && ambiguous == this.ambiguous && deprecation === this.deprecation) this else
            C_DefProxy(def, ideInfo, ambiguous, deprecation)
    }

    fun access(msgCtx: C_MessageContext, name: C_QualifiedName) {
        if (ambiguous) {
            val lastName = name.last
            val qName = name.str()
            msgCtx.error(lastName.pos, "name:ambig:$qName", "Name '$qName' is ambiguous")
        }

        if (deprecation != null) {
            val simpleName = name.last
            deprecatedMessage(msgCtx, simpleName.pos, simpleName.str, deprecation)
        }
    }

    companion object {
        fun create(type: R_Type, ideInfo: IdeSymbolInfo, deprecated: C_Deprecated? = null): C_DefProxy<C_TypeDef> {
            val typeDef = C_TypeDef_Normal(type)
            return createGeneric(typeDef, ideInfo, C_DeclarationType.TYPE, deprecated)
        }

        fun create(type: C_GenericType, ideInfo: IdeSymbolInfo, deprecated: C_Deprecated? = null): C_DefProxy<C_TypeDef> {
            val typeDef = C_TypeDef_Generic(type)
            return createGeneric(typeDef, ideInfo, C_DeclarationType.TYPE, deprecated)
        }

        fun create(
                namespace: C_Namespace,
                ideInfo: IdeSymbolInfo = IdeSymbolInfo.DEF_NAMESPACE,
                deprecated: C_Deprecated? = null
        ): C_DefProxy<C_Namespace> {
            return createGeneric(namespace, ideInfo, C_DeclarationType.NAMESPACE, deprecated)
        }

        fun create(fn: C_GlobalFunction, deprecated: C_Deprecated? = null): C_DefProxy<C_GlobalFunction> {
            return createGeneric(fn, fn.ideInfo, C_DeclarationType.FUNCTION, deprecated)
        }

        fun create(value: C_NamespaceValue): C_DefProxy<C_NamespaceValue> {
            return createGeneric(value, value.ideInfo)
        }

        private fun <T> createGeneric(def: T, ideInfo: IdeSymbolInfo): C_DefProxy<T> {
            return C_DefProxy(def, ideInfo)
        }

        private fun <T> createGeneric(
                def: T,
                ideInfo: IdeSymbolInfo,
                type: C_DeclarationType,
                deprecated: C_Deprecated?
        ): C_DefProxy<T> {
            val deprecation = if (deprecated == null) null else C_DefProxyDeprecation(type, deprecated)
            return C_DefProxy(def, ideInfo, deprecation = deprecation)
        }

        fun deprecatedMessage(
                msgCtx: C_MessageContext,
                pos: S_Pos,
                nameMsg: String,
                deprecation: C_DefProxyDeprecation
        ) {
            val type = deprecation.type
            val deprecated = deprecation.deprecated

            val typeStr = type.msg.capitalize()
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

sealed class C_DefTransformer<T, R> {
    abstract fun transform(def: T): R?
}

class C_DefTransformer_None<T>: C_DefTransformer<T, T>() {
    override fun transform(def: T) = def
}

object C_DefTransformer_Entity: C_DefTransformer<C_TypeDef, R_EntityDefinition>() {
    override fun transform(def: C_TypeDef): R_EntityDefinition? {
        return when (def) {
            is C_TypeDef_Normal -> (def.type as? R_EntityType)?.rEntity
            is C_TypeDef_Generic -> null
        }
    }
}

object C_DefTransformer_Object: C_DefTransformer<C_NamespaceValue, R_ObjectDefinition>() {
    override fun transform(def: C_NamespaceValue) = (def as? C_NamespaceValue_Object)?.rObject
}

object C_DefTransformer_Operation: C_DefTransformer<C_GlobalFunction, R_OperationDefinition>() {
    override fun transform(def: C_GlobalFunction) = (def as? C_OperationGlobalFunction)?.rOp
}

class C_DefRef<T>(
        val msgCtx: C_MessageContext,
        val qName: C_QualifiedName,
        private val proxy: C_DefProxy<T>
) {
    fun getDef(): T {
        val res = proxy.getDef(msgCtx, qName)
        return res
    }

    fun ideSymbolInfo(): IdeSymbolInfo = proxy.ideInfo

    fun <R> sub(subName: C_Name, subProxy: C_DefProxy<R>) = C_DefRef(msgCtx, qName.add(subName), subProxy)

    fun <R> toResolution(
            refNameHand: C_QualifiedNameHandle,
            ideInfos: List<IdeSymbolInfo>,
            transformer: C_DefTransformer<T, R>
    ): C_DefResolution<R>? {
        checkEquals(ideInfos.size, refNameHand.parts.size)
        val def = proxy.getDefQuiet()
        val resDef = transformer.transform(def)
        return if (resDef == null) null else C_DefResolution(resDef, this, refNameHand, ideInfos)
    }
}

class C_NamespaceRef(
        private val msgCtx: C_MessageContext,
        private val path: List<C_Name>,
        private val ns: C_Namespace
) {
    fun namespace(name: C_Name) = get(name, C_Namespace::namespace)
    fun type(name: C_Name) = get(name, C_Namespace::type)
    fun value(name: C_Name) = get(name, C_Namespace::value)
    fun function(name: C_Name) = get(name, C_Namespace::function)

    private fun <T> get(name: C_Name, getter: (C_Namespace, R_Name) -> C_DefProxy<T>?): C_DefRef<T>? {
        val proxy = getter(ns, name.rName)
        return wrap(name, proxy)
    }

    private fun <T> wrap(name: C_Name, proxy: C_DefProxy<T>?): C_DefRef<T>? {
        return if (proxy == null) null else C_DefRef(msgCtx, C_QualifiedName(path + name), proxy)
    }

    companion object {
        fun create(msgCtx: C_MessageContext, name: C_QualifiedName, ns: C_Namespace): C_NamespaceRef {
            return C_NamespaceRef(msgCtx, name.parts, ns)
        }

        fun create(ref: C_DefRef<C_Namespace>): C_NamespaceRef {
            val ns = ref.getDef()
            return C_NamespaceRef(ref.msgCtx, ref.qName.parts, ns)
        }
    }
}

sealed class C_Namespace {
    abstract fun namespace(name: R_Name): C_DefProxy<C_Namespace>?
    abstract fun type(name: R_Name): C_DefProxy<C_TypeDef>?
    abstract fun value(name: R_Name): C_DefProxy<C_NamespaceValue>?
    abstract fun function(name: R_Name): C_DefProxy<C_GlobalFunction>?

    abstract fun addTo(b: C_NamespaceBuilder)

    companion object {
        val EMPTY: C_Namespace = C_BasicNamespace(
                namespaces = immMapOf(),
                types = immMapOf(),
                values = immMapOf(),
                functions = immMapOf()
        )

        fun makeLate(getter: LateGetter<C_Namespace>): C_Namespace {
            return C_LateNamespace(getter)
        }
    }
}

private class C_BasicNamespace(
        namespaces: Map<R_Name, C_DefProxy<C_Namespace>>,
        types: Map<R_Name, C_DefProxy<C_TypeDef>>,
        values: Map<R_Name, C_DefProxy<C_NamespaceValue>>,
        functions: Map<R_Name, C_DefProxy<C_GlobalFunction>>
): C_Namespace() {
    private val namespaces = namespaces.toImmMap()
    private val types = types.toImmMap()
    private val values = values.toImmMap()
    private val functions = functions.toImmMap()

    override fun namespace(name: R_Name) = namespaces[name]
    override fun type(name: R_Name) = types[name]
    override fun value(name: R_Name) = values[name]
    override fun function(name: R_Name) = functions[name]

    override fun addTo(b: C_NamespaceBuilder) {
        namespaces.forEach { b.addNamespace(it.key, it.value) }
        types.forEach { b.addType(it.key, it.value) }
        values.forEach { b.addValue(it.key, it.value) }
        functions.forEach { b.addFunction(it.key, it.value) }
    }
}

private class C_LateNamespace(private val getter: LateGetter<C_Namespace>): C_Namespace() {
    override fun namespace(name: R_Name) = getter.get().namespace(name)
    override fun type(name: R_Name) = getter.get().type(name)
    override fun value(name: R_Name) = getter.get().value(name)
    override fun function(name: R_Name) = getter.get().function(name)
    override fun addTo(b: C_NamespaceBuilder) = getter.get().addTo(b)
}

class C_NamespaceElement(
        val namespace: C_DefProxy<C_Namespace>? = null,
        val type: C_DefProxy<C_TypeDef>? = null,
        val value: C_DefProxy<C_NamespaceValue>? = null,
        val function: C_DefProxy<C_GlobalFunction>? = null
) {
    init {
        check(namespace != null || type != null || value != null || function != null)
    }

    fun ideInfo(): IdeSymbolInfo {
        return when {
            type != null -> type.ideInfo
            value != null -> value.ideInfo
            function != null -> function.ideInfo
            namespace != null -> namespace.ideInfo
            else -> throw IllegalStateException()
        }
    }

    companion object {
        fun create(
                namespace: C_DefProxy<C_Namespace>? = null,
                type: C_DefProxy<C_TypeDef>? = null,
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
    private val namespaces = mutableMapOf<R_Name, C_DefProxy<C_Namespace>>()
    private val types = mutableMapOf<R_Name, C_DefProxy<C_TypeDef>>()
    private val values = mutableMapOf<R_Name, C_DefProxy<C_NamespaceValue>>()
    private val functions = mutableMapOf<R_Name, C_DefProxy<C_GlobalFunction>>()

    fun add(name: R_Name, elem: C_NamespaceElement) {
        if (elem.namespace != null) addDef(namespaces, name, elem.namespace)
        if (elem.type != null) addDef(types, name, elem.type)
        if (elem.value != null) addDef(values, name, elem.value)
        if (elem.function != null) addDef(functions, name, elem.function)
    }

    fun addNamespace(name: R_Name, ns: C_DefProxy<C_Namespace>) = addDef(namespaces, name, ns)
    fun addType(name: R_Name, type: C_DefProxy<C_TypeDef>) = addDef(types, name, type)
    fun addValue(name: R_Name, value: C_DefProxy<C_NamespaceValue>) = addDef(values, name, value)
    fun addFunction(name: R_Name, function: C_DefProxy<C_GlobalFunction>) = addDef(functions, name, function)

    private fun <T> addDef(map: MutableMap<R_Name, C_DefProxy<T>>, name: R_Name, def: C_DefProxy<T>) {
        check(name !in map) { "$name ${map.keys.sorted()}" }
        map[name] = def
    }

    fun build(): C_Namespace {
        return C_BasicNamespace(namespaces.toMap(), types.toMap(), values.toMap(), functions.toMap())
    }
}

class C_NamespaceValueContext(val exprCtx: C_ExprContext) {
    val defCtx = exprCtx.defCtx
    val globalCtx = defCtx.globalCtx
    val msgCtx = defCtx.msgCtx
    val modCtx = defCtx.modCtx
}

abstract class C_NamespaceValue(val ideInfo: IdeSymbolInfo) {
    abstract fun toExpr(ctx: C_NamespaceValueContext, name: C_QualifiedName, implicitAttrMatchName: R_Name?): C_Expr
}

abstract class C_NamespaceValue_VExpr(ideInfo: IdeSymbolInfo): C_NamespaceValue(ideInfo) {
    protected abstract fun toExpr0(ctx: C_NamespaceValueContext, name: C_QualifiedName): V_Expr

    final override fun toExpr(ctx: C_NamespaceValueContext, name: C_QualifiedName, implicitAttrMatchName: R_Name?): C_Expr {
        val vExpr = toExpr0(ctx, name)
        return C_VExpr(vExpr, implicitAttrMatchName = implicitAttrMatchName)
    }
}

class C_NamespaceValue_RtValue(
        ideInfo: IdeSymbolInfo,
        private val value: Rt_Value
): C_NamespaceValue_VExpr(ideInfo) {
    override fun toExpr0(ctx: C_NamespaceValueContext, name: C_QualifiedName): V_Expr {
        return V_ConstantValueExpr(ctx.exprCtx, name.pos, value)
    }
}

class C_NamespaceValue_SysFunction(
        ideInfo: IdeSymbolInfo,
        private val resultType: R_Type,
        private val fn: R_SysFunction,
        private val pure: Boolean
): C_NamespaceValue_VExpr(ideInfo) {
    override fun toExpr0(ctx: C_NamespaceValueContext, name: C_QualifiedName): V_Expr {
        return C_ExprUtils.createSysGlobalPropExpr(ctx.exprCtx, resultType, fn, name, pure = pure)
    }
}

class C_NamespaceValue_Entity(
        ideInfo: IdeSymbolInfo,
        private val type: R_Type
): C_NamespaceValue(ideInfo) {
    override fun toExpr(ctx: C_NamespaceValueContext, name: C_QualifiedName, implicitAttrMatchName: R_Name?): C_Expr {
        return C_SpecificTypeExpr(name.last.pos, type)
    }
}

class C_NamespaceValue_Type(
    private val defProxy: C_DefProxy<C_TypeDef>
): C_NamespaceValue(defProxy.ideInfo) {
    override fun toExpr(ctx: C_NamespaceValueContext, name: C_QualifiedName, implicitAttrMatchName: R_Name?): C_Expr {
        val typeDef = defProxy.getDef(ctx.msgCtx, name)
        return typeDef.toExpr(name.pos)
    }
}

class C_NamespaceValue_Namespace(
        private val nsProxy: C_DefProxy<C_Namespace>
): C_NamespaceValue(nsProxy.ideInfo) {
    override fun toExpr(ctx: C_NamespaceValueContext, name: C_QualifiedName, implicitAttrMatchName: R_Name?): C_Expr {
        val ns = nsProxy.getDef(ctx.msgCtx, name)
        val nsRef = C_NamespaceRef.create(ctx.msgCtx, name, ns)
        return C_NamespaceExpr(name, nsRef)
    }
}

class C_NamespaceValue_Object(
        ideInfo: IdeSymbolInfo,
        val rObject: R_ObjectDefinition
): C_NamespaceValue(ideInfo) {
    override fun toExpr(ctx: C_NamespaceValueContext, name: C_QualifiedName, implicitAttrMatchName: R_Name?): C_Expr {
        return C_ObjectExpr(ctx.exprCtx, name, rObject)
    }
}

class C_NamespaceValue_Struct(
        ideInfo: IdeSymbolInfo,
        private val struct: R_Struct
): C_NamespaceValue(ideInfo) {
    override fun toExpr(ctx: C_NamespaceValueContext, name: C_QualifiedName, implicitAttrMatchName: R_Name?): C_Expr {
        val ns = C_Lib_Type_Struct.getNamespace(struct)
        val nsRef = C_NamespaceRef.create(ctx.msgCtx, name, ns)
        return C_NamespaceStructExpr(name, struct, nsRef)
    }
}

class C_NamespaceValue_GlobalConstant(
        ideInfo: IdeSymbolInfo,
        private val cDef: C_GlobalConstantDefinition
): C_NamespaceValue_VExpr(ideInfo) {
    override fun toExpr0(ctx: C_NamespaceValueContext, name: C_QualifiedName): V_Expr {
        return cDef.compileRead(ctx.exprCtx, name.last)
    }
}
