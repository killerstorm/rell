/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibRangeTest: BaseRellTest(false) {
    @Test fun testConstructor() {
        chk("range(10)", "range[0,10,1]")
        chk("range(5,10)", "range[5,10,1]")
        chk("range(5,10,3)", "range[5,10,3]")
        chk("range(0)", "range[0,0,1]")
        chk("range(-1)", "rt_err:fn_range_args:0:-1:1")
        chk("range(10,10)", "range[10,10,1]")
        chk("range(11,10)", "rt_err:fn_range_args:11:10:1")
        chk("range(1,0)", "rt_err:fn_range_args:1:0:1")

        chk("range(0,10,0)", "rt_err:fn_range_args:0:10:0")
        chk("range(0,10,-1)", "rt_err:fn_range_args:0:10:-1")
        chk("range(0,0,-1)", "range[0,0,-1]")
        chk("range(1,0,-1)", "range[1,0,-1]")
        chk("range(10,0,-1)", "range[10,0,-1]")

        chk("range()", "ct_err:expr_call_argtypes:range:")
        chk("range(1,2,3,4)", "ct_err:expr_call_argtypes:range:integer,integer,integer,integer")
    }

    @Test fun testIn() {
        chk("-1 in range(5)", "boolean[false]")
        chk("0 in range(5)", "boolean[true]")
        chk("1 in range(5)", "boolean[true]")
        chk("2 in range(5)", "boolean[true]")
        chk("3 in range(5)", "boolean[true]")
        chk("4 in range(5)", "boolean[true]")
        chk("5 in range(5)", "boolean[false]")
        chk("(-9223372036854775807-1) in range(5)", "boolean[false]")
        chk("9223372036854775807 in range(5)", "boolean[false]")

        chk("true in range(5)", "ct_err:binop_operand_type:in:[boolean]:[range]")
        chk("'Hello' in range(5)", "ct_err:binop_operand_type:in:[text]:[range]")
        chk("range(5) in range(5)", "ct_err:binop_operand_type:in:[range]:[range]")

        chk("4 in range(5,7)", "boolean[false]")
        chk("5 in range(5,7)", "boolean[true]")
        chk("6 in range(5,7)", "boolean[true]")
        chk("7 in range(5,7)", "boolean[false]")

        chk("1 in range(2, 8, 3)", "boolean[false]")
        chk("2 in range(2, 8, 3)", "boolean[true]")
        chk("3 in range(2, 8, 3)", "boolean[false]")
        chk("4 in range(2, 8, 3)", "boolean[false]")
        chk("5 in range(2, 8, 3)", "boolean[true]")
        chk("6 in range(2, 8, 3)", "boolean[false]")
        chk("7 in range(2, 8, 3)", "boolean[false]")
        chk("8 in range(2, 8, 3)", "boolean[false]")
    }

    @Test fun testInNegativeStep() {
        chk("0 in range(4, 0, -1)", "boolean[false]")
        chk("1 in range(4, 0, -1)", "boolean[true]")
        chk("2 in range(4, 0, -1)", "boolean[true]")
        chk("3 in range(4, 0, -1)", "boolean[true]")
        chk("4 in range(4, 0, -1)", "boolean[true]")
        chk("5 in range(4, 0, -1)", "boolean[false]")

        chk("2 in range(8, 2, -3)", "boolean[false]")
        chk("3 in range(8, 2, -3)", "boolean[false]")
        chk("4 in range(8, 2, -3)", "boolean[false]")
        chk("5 in range(8, 2, -3)", "boolean[true]")
        chk("6 in range(8, 2, -3)", "boolean[false]")
        chk("7 in range(8, 2, -3)", "boolean[false]")
        chk("8 in range(8, 2, -3)", "boolean[true]")
        chk("9 in range(8, 2, -3)", "boolean[false]")
    }

    @Test fun testInBoundaryCases() {
        val M = "9223372036854775807" // 2^63-1

        var R = "range(-$M-1,$M)"
        chk("(-$M-1) in $R", "boolean[true]")
        chk("0 in $R", "boolean[true]")
        chk("($M-1) in $R", "boolean[true]")
        chk("$M in $R", "boolean[false]")

        R = "range(-$M,$M)"
        chk("(-$M-1) in $R", "boolean[false]")
        chk("-$M in $R", "boolean[true]")
        chk("0 in $R", "boolean[true]")
        chk("($M-1) in $R", "boolean[true]")
        chk("$M in $R", "boolean[false]")

        R = "range($M,-$M-1,-1)"
        chk("(-$M-1) in $R", "boolean[false]")
        chk("-$M in $R", "boolean[true]")
        chk("0 in $R", "boolean[true]")
        chk("$M in $R", "boolean[true]")

        R = "range($M-1,-$M-1,-1)"
        chk("(-$M-1) in $R", "boolean[false]")
        chk("-$M in $R", "boolean[true]")
        chk("0 in $R", "boolean[true]")
        chk("($M-1) in $R", "boolean[true]")
        chk("$M in $R", "boolean[false]")

        R = "range(-$M-1,$M,$M)"
        chk("(-$M-1) in $R", "boolean[true]")
        chk("-$M in $R", "boolean[false]")
        chk("-2 in $R", "boolean[false]")
        chk("-1 in $R", "boolean[true]")
        chk("0 in $R", "boolean[false]")
        chk("1 in $R", "boolean[false]")
        chk("($M-2) in $R", "boolean[false]")
        chk("($M-1) in $R", "boolean[true]")
        chk("$M in $R", "boolean[false]")

        R = "range(-$M,$M,$M-1)"
        chk("(-$M-1) in $R", "boolean[false]")
        chk("-$M in $R", "boolean[true]")
        chk("-2 in $R", "boolean[false]")
        chk("-1 in $R", "boolean[true]")
        chk("0 in $R", "boolean[false]")
        chk("1 in $R", "boolean[false]")
        chk("($M-3) in $R", "boolean[false]")
        chk("($M-2) in $R", "boolean[true]")
        chk("($M-1) in $R", "boolean[false]")
        chk("$M in $R", "boolean[false]")

        R = "range($M,-$M-1,-$M-1)"
        chk("(-$M-1) in $R", "boolean[false]")
        chk("-$M in $R", "boolean[false]")
        chk("-2 in $R", "boolean[false]")
        chk("-1 in $R", "boolean[true]")
        chk("0 in $R", "boolean[false]")
        chk("1 in $R", "boolean[false]")
        chk("($M-1) in $R", "boolean[false]")
        chk("$M in $R", "boolean[true]")

        R = "range($M,-$M-1,-$M)"
        chk("(-$M-1) in $R", "boolean[false]")
        chk("-$M in $R", "boolean[true]")
        chk("(-$M+1) in $R", "boolean[false]")
        chk("-1 in $R", "boolean[false]")
        chk("0 in $R", "boolean[true]")
        chk("1 in $R", "boolean[false]")
        chk("($M-2) in $R", "boolean[false]")
        chk("($M-1) in $R", "boolean[false]")
        chk("$M in $R", "boolean[true]")
    }
}
