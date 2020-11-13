package net.postchain.rell

import net.postchain.rell.test.RellCodeTester
import org.junit.Test

class AtExprGroupDbTest: AtExprGroupBaseTest() {
    override fun impDefKw() = "entity"

    override fun impCreateObjs(t: RellCodeTester, name: String, vararg objs: String) {
        if (objs.isNotEmpty()) {
            val code = objs.joinToString(" ") { "create $name($it);" }
            t.chkOp(code)
        }
    }

    override fun impFrom(name: String) = name
    override fun impPlaceholder() = ""

    @Test fun testTypeGroupDb() {
        def("entity user { name; }")
        tst.insert("c0.user", "name", "303,'Bob'")
        chkTypeGroup("user", "user @ { 'Bob' }", "user[303]")
    }

    @Test fun testTypeGroupFormalDb() {
        def("entity user { name; }")
        chkTypeGroupFormal("user", "text[user]")
    }

    @Test fun testTypeMinMaxDb() {
        initDataAllTypes()
        val from = impFrom("data")
        chk("$from @ {} ( @min $ph.b )", "ct_err:at:what:aggr:bad_type:MIN:boolean")
        chk("$from @ {} ( @max $ph.b )", "ct_err:at:what:aggr:bad_type:MAX:boolean")
        chk("$from @ {} ( @min $ph.ba )", "ct_err:at:what:aggr:bad_type:MIN:byte_array")
        chk("$from @ {} ( @max $ph.ba )", "ct_err:at:what:aggr:bad_type:MAX:byte_array")
    }
}
