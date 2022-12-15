/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.model.R_IntegerType
import net.postchain.rell.model.R_RowidType
import net.postchain.rell.model.expr.Db_SysFunction
import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.runtime.Rt_RowidValue
import net.postchain.rell.runtime.utils.Rt_Utils

object C_Lib_Type_Rowid: C_Lib_Type("rowid", R_RowidType) {
    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        b.add(typeName.str, R_RowidType, listOf(R_IntegerType), RowidFns.Constructor_Integer)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        b.add("to_integer", R_IntegerType, listOf(), RowidFns.ToInteger)
    }
}

private object RowidFns {
    val Constructor_Integer = C_SysFunction.simple1(pure = true) { a ->
        val v = a.asInteger()
        Rt_Utils.check(v >= 0) { "rowid(integer):negative:$v" toCodeMsg "Negative value: $v" }
        Rt_RowidValue(v)
    }

    val ToInteger = C_SysFunction.simple1(pure = true,
        dbFn = Db_SysFunction.template("rowid.to_integer", 1, "#0"),
    ) { a ->
        val v = a.asRowid()
        Rt_IntValue(v)
    }
}
