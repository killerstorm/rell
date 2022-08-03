/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.def.C_TypeDef
import net.postchain.rell.compiler.base.namespace.C_DefProxy
import net.postchain.rell.compiler.base.namespace.C_Deprecated
import net.postchain.rell.compiler.base.namespace.C_NamespaceValue
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_Type
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.immSetOf
import net.postchain.rell.utils.toImmMap

private fun typeRef(type: R_Type, deprecated: C_Deprecated? = null): C_DefProxy<C_TypeDef> {
    return C_DefProxy.create(type, IdeSymbolInfo.DEF_TYPE, deprecated)
}

abstract class C_Lib_Type(
    nameStr: String,
    val type: R_Type,
    private val defaultMemberFns: Boolean = true,
) {
    protected val typeName = R_Name.of(nameStr)

    val constructorFn: C_GlobalFunction? by lazy {
        val b = C_GlobalFuncBuilder(null, typeNames = immSetOf(typeName))
        bindConstructors(b)
        val m = b.build().toMap()
        if (m.isEmpty()) null else {
            checkEquals(m.size, 1)
            m.values.first()
        }
    }

    val memberFns: C_MemberFuncTable = let {
        val b = C_LibUtils.typeMemFuncBuilder(type, default = defaultMemberFns)
        bindMemberFunctions(b)
        b.build()
    }

    val staticValues: Map<R_Name, C_NamespaceValue> = let {
        val constants = bindConstants()
        constants.toMap().mapKeys { R_Name.of(it.key) }.toImmMap()
    }

    val staticFns: C_GlobalFuncTable = let {
        val b = C_LibUtils.typeGlobalFuncBuilder(type)
        bindStaticFunctions(b)
        b.build()
    }

    protected open fun bindConstructors(b: C_GlobalFuncBuilder) {
    }

    protected open fun bindConstants(): List<Pair<String, C_NamespaceValue>> {
        return immListOf()
    }

    protected open fun bindStaticFunctions(b: C_GlobalFuncBuilder) {
    }

    protected open fun bindMemberFunctions(b: C_MemberFuncBuilder) {
    }

    protected open fun bindAliases(b: C_SysNsProtoBuilder) {
    }

    protected fun bindAlias(
        b: C_SysNsProtoBuilder,
        name: String,
        deprecated: C_Deprecated? = null
    ) {
        val rName = R_Name.of(name)
        b.addType(rName, typeRef(type, deprecated = deprecated))
    }

    fun bind(b: C_SysNsProtoBuilder) {
        b.addType(typeName, typeRef(type))
        bindAliases(b)
    }
}
