/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.namespace.*
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.compiler.base.utils.C_MemberFuncTable
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_Type
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.immSetOf

private fun typeRef(type: R_Type, deprecated: C_Deprecated? = null): C_DefProxy<R_Type> {
    return C_DefProxy.create(type, IdeSymbolInfo.DEF_TYPE, deprecated)
}

abstract class C_Lib_Type(
    nameStr: String,
    val type: R_Type,
    private val bindType: Boolean = true,
    private val defaultMemberFns: Boolean = true,
) {
    protected val typeName = R_Name.of(nameStr)

    val memberFns: C_MemberFuncTable = let {
        val b = C_LibUtils.typeMemFuncBuilder(type, default = defaultMemberFns)
        bindMemberFunctions(b)
        b.build()
    }

    private val namespace: C_Namespace = let {
        val constants = bindConstants()

        val b = C_LibUtils.typeGlobalFuncBuilder(type)
        bindStaticFunctions(b)
        val staticFns = b.build()

        C_LibUtils.makeNs(staticFns, *constants.toTypedArray())
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
        bindNamespace: Boolean = false,
        deprecated: C_Deprecated? = null
    ) {
        val rName = R_Name.of(name)
        b.addType(rName, typeRef(type, deprecated = deprecated))

        if (bindNamespace) {
            val nsProxy = C_DefProxy.create(namespace, ideInfo = IdeSymbolInfo.DEF_TYPE, deprecated = deprecated)
            b.addNamespace(rName, nsProxy)
        }
    }

    fun bind(b: C_SysNsProtoBuilder) {
        if (bindType) {
            b.addType(typeName, typeRef(type))
        }

        val cb = C_GlobalFuncBuilder(null, typeNames = immSetOf(typeName))
        bindConstructors(cb)

        for (fn in cb.build().toMap().values) {
            b.addFunction(typeName, fn)
        }

        b.addNamespace(typeName, C_DefProxy.create(namespace, ideInfo = IdeSymbolInfo.DEF_TYPE))

        bindAliases(b)
    }
}
