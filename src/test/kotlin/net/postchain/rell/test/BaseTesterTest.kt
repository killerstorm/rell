package net.postchain.rell.test

abstract class BaseTesterTest(useSql: Boolean): BaseContextTest(useSql) {
    protected abstract val tst: RellBaseTester

    fun file(path: String, text: String) = tst.file(path, text)
    fun def(def: String) = tst.def(def)
    fun insert(table: String, columns: String, values: String) = tst.insert(table, columns, values)
    fun insert(inserts: List<String>) = tst.insert(inserts)

    abstract fun chkEx(code: String, expected: String)

    fun chk(code: String, expected: String) = chkEx("= $code;", expected)

    fun chkCompile(code: String, expected: String) = tst.chkCompile(code, expected)

    fun chkStdout(vararg expected: String) = tst.chkStdout(*expected)
    fun chkLog(vararg expected: String) = tst.chkLog(*expected)
}
