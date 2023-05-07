/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.core

import net.postchain.rell.base.compiler.base.namespace.C_Namespace
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceEntry
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceMemberTag
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.utils.Getter

class C_ScopeBuilder {
    private val scope: C_Scope

    constructor(): this(null, { C_Namespace.EMPTY })

    private constructor(parentScope: C_Scope?, nsGetter: Getter<C_Namespace>) {
        this.scope = C_Scope(parentScope, nsGetter)
    }

    fun nested(nsGetter: Getter<C_Namespace>): C_ScopeBuilder {
        return C_ScopeBuilder(scope, nsGetter)
    }

    fun scope() = scope
}

class C_Scope(
        private val parent: C_Scope?,
        private val nsGetter: Getter<C_Namespace>
) {
    private val rootNs: C_Namespace by lazy {
        nsGetter()
    }

    fun findEntry(name: R_Name, tags: List<C_NamespaceMemberTag>): C_NamespaceEntry? {
        var scope: C_Scope? = this
        var res: C_NamespaceEntry? = null

        while (scope != null) {
            val entry = scope.rootNs.getEntry(name)
            if (entry != null) {
                if (entry.hasTag(tags)) {
                    res = entry
                    break
                } else if (res == null) {
                    res = entry
                }
            }
            scope = scope.parent
        }

        return res
    }
}
