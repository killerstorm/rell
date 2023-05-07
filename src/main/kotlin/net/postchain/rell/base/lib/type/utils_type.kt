/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.def.C_GlobalFunction
import net.postchain.rell.base.compiler.base.def.C_TypeDef_Normal
import net.postchain.rell.base.compiler.base.expr.C_TypeValueMember
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.namespace.C_Namespace
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.base.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.base.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.base.compiler.base.utils.C_LibUtils
import net.postchain.rell.base.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.ide.IdeSymbolInfo
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.immSetOf
import net.postchain.rell.base.utils.toImmList

abstract class C_Lib_Type(
    nameStr: String,
    val type: R_Type,
    private val defaultMemberFns: Boolean = true,
) {
    protected val typeName = R_Name.of(nameStr)

    val constructorFn: C_GlobalFunction? by lazy {
        val b = C_GlobalFuncBuilder(typeNames = immSetOf(typeName))
        bindConstructors(b)
        val m = b.build().toMap()
        if (m.isEmpty()) null else {
            checkEquals(m.size, 1)
            m.values.first().fn
        }
    }

    val staticNs: C_Namespace = let {
        val staticValues = bindConstants()

        val b = C_LibUtils.typeGlobalFuncBuilder(type)
        bindStaticFunctions(b)
        val staticFns = b.build()

        C_LibUtils.makeNs(type.defName.toPath(), staticFns, *staticValues.toTypedArray())
    }

    val valueMembers: List<C_TypeValueMember> by lazy {
        val vb = mutableListOf<C_TypeValueMember>()
        bindMemberValues(vb)

        val fb = C_LibUtils.typeMemFuncBuilder(type, default = defaultMemberFns)
        bindMemberFunctions(fb)

        C_LibUtils.makeValueMembers(type, fb.build(), vb.toImmList())
    }

    protected open fun bindConstructors(b: C_GlobalFuncBuilder) {
    }

    protected open fun bindConstants(): List<Pair<String, C_NamespaceProperty>> {
        return immListOf()
    }

    protected open fun bindStaticFunctions(b: C_GlobalFuncBuilder) {
    }

    protected open fun bindMemberValues(b: MutableList<C_TypeValueMember>) {
    }

    protected open fun bindMemberFunctions(b: C_MemberFuncBuilder) {
    }

    protected open fun bindAliases(b: C_SysNsProtoBuilder) {
    }

    protected fun bindAlias(b: C_SysNsProtoBuilder, name: String, deprecated: C_Deprecated? = null) {
        val rName = R_Name.of(name)
        val typeDef = C_TypeDef_Normal(type)
        b.addType(rName, typeDef, IdeSymbolInfo.DEF_TYPE, deprecated)
    }

    fun bind(b: C_SysNsProtoBuilder) {
        val typeDef = C_TypeDef_Normal(type)
        b.addType(typeName, typeDef, IdeSymbolInfo.DEF_TYPE)
        bindAliases(b)
    }
}
