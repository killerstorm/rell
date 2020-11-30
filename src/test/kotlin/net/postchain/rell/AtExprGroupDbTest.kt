package net.postchain.rell

import net.postchain.rell.test.RellCodeTester
import org.junit.Test

class AtExprGroupDbTest: AtExprGroupBaseTest() {
    override val impDefKw = "entity"
    override fun impFrom(name: String) = name
    override fun impRtErr(code: String) = "rt_err:sqlerr:0"

    override fun impCreateObjs(t: RellCodeTester, name: String, vararg objs: String) {
        if (objs.isNotEmpty()) {
            val code = objs.joinToString(" ") { "create $name($it);" }
            t.chkOp(code)
        }
    }

    override fun testSumOverflowInteger() {
        super.testSumOverflowInteger()
        chkTypeSum("integer", "9223372036854775807 1 -1", "int[9223372036854775807]")
        chkTypeSum("integer", "-9223372036854775807-1 -1 1", "int[-9223372036854775808]")
    }

    override fun testSumOverflowDecimal() {
        super.testSumOverflowDecimal()
        val dv = DecimalTest.DecVals()
        chkTypeSum("decimal", "decimal('${dv.lim1}') 1.0 -1.0", "dec[${dv.lim1}]")
    }

    @Test fun testDefaultSorting() {
        initDataCountries()
        val from = impFrom("data")

        chk("$from @* {} ( .name )", "[Germany, Austria, United Kingdom, USA, Mexico, China]")
        chk("$from @* {} ( @group .name )", "[Austria, China, Germany, Mexico, USA, United Kingdom]")
        chk("$from @* {} ( @group .region )", "[AMER, APAC, EMEA]")
        chk("$from @* {} ( @group .language )", "[Chinese, English, German, Spanish]")

        chk("$from @* {} ( @group _=.region, @group _=.language )",
                "[(AMER,English), (AMER,Spanish), (APAC,Chinese), (EMEA,English), (EMEA,German)]")
        chk("$from @* {} ( @group _=.language, @group _=.region )",
                "[(Chinese,APAC), (English,AMER), (English,EMEA), (German,EMEA), (Spanish,AMER)]")
    }

    override fun testTypeMinMax() {
        super.testTypeMinMax()
        chkTypeMinMaxErr("boolean")
        chkTypeMinMaxErr("byte_array")
    }
}
