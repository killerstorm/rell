/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.utils.doc

sealed class DocExpr {
    abstract fun genCode(b: DocCode.Builder)

    companion object {
        val UNKNOWN: DocExpr = DocExpr_Unknown

        fun value(value: DocValue): DocExpr = DocExpr_Value(value)
    }
}

private object DocExpr_Unknown: DocExpr() {
    override fun genCode(b: DocCode.Builder) {
        b.raw("<...>")
    }
}

private class DocExpr_Value(private val value: DocValue): DocExpr() {
    override fun genCode(b: DocCode.Builder) {
        value.genCode(b)
    }
}
