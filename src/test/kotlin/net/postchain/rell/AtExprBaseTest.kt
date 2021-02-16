package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester

abstract class AtExprBaseTest: BaseRellTest() {
    protected abstract fun impKind(): AtExprTestKind
    protected val kind = impKind()

    protected val impDefKw = kind.impDefKw
    protected val impNew = kind.impNew
    protected fun impFrom(name: String) = kind.impFrom(name)
    protected fun impRtErr(code: String) = kind.impRtErr(code)
    protected fun impCreateObjs(t: RellCodeTester, name: String, vararg objs: String) = kind.impCreateObjs(t, name, *objs)
    protected fun impCreateObjs(name: String, vararg objs: String) = impCreateObjs(tst, name, *objs)

    protected abstract class AtExprTestKind {
        abstract val impDefKw: String
        abstract val impNew: String
        abstract fun impFrom(name: String): String
        abstract fun impRtErr(code: String): String
        abstract fun impCreateObjs(t: RellCodeTester, name: String, vararg objs: String)
    }

    protected object AtExprTestKind_Col: AtExprTestKind() {
        override val impDefKw = "struct"
        override val impNew = ""
        override fun impFrom(name: String) = "get_$name()"
        override fun impRtErr(code: String) = "rt_err:$code"

        override fun impCreateObjs(t: RellCodeTester, name: String, vararg objs: String) {
            val values = objs.joinToString(", ") { "$name($it)" }
            t.def("function get_$name(): list<$name> = [$values];")
        }
    }

    protected object AtExprTestKind_Db: AtExprTestKind() {
        override val impDefKw = "entity"
        override val impNew = "create"
        override fun impFrom(name: String) = name
        override fun impRtErr(code: String) = "rt_err:sqlerr:0"

        override fun impCreateObjs(t: RellCodeTester, name: String, vararg objs: String) {
            if (objs.isNotEmpty()) {
                val code = objs.joinToString(" ") { "create $name($it);" }
                t.chkOp(code)
            }
        }
    }
}
