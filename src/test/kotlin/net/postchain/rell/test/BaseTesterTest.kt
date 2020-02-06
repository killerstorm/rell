package net.postchain.rell.test

abstract class BaseTesterTest(useSql: Boolean): BaseContextTest(useSql) {
    protected abstract val tst: RellBaseTester

    fun file(path: String, text: String) = tst.file(path, text)
    fun mainModule(vararg modules: String) = tst.mainModule(*modules)
    fun def(def: String) = tst.def(def)
    fun insert(table: String, columns: String, values: String) = tst.insert(table, columns, values)
    fun insert(insert: String) = tst.insert(listOf(insert))
    fun insert(inserts: List<String>) = tst.insert(inserts)

    abstract fun chkEx(code: String, expected: String)

    fun chk(code: String, expected: String) = chkEx("= $code;", expected)

    fun chkCompile(code: String, expected: String) = tst.chkCompile(code, expected)

    fun chkOut(vararg expected: String) = tst.chkOut(*expected)
    fun chkLog(vararg expected: String) = tst.chkLog(*expected)
}
