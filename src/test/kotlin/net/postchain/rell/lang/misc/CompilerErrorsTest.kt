/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lang.misc

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class CompilerErrorsTest: BaseRellTest(false) {
    private val badExpr1 = "abs(x'')"
    private val badError1 = "expr_call_argtypes:abs:byte_array"
    private val badExpr2 = "min()"
    private val badError2 = "expr_call_argtypes:min:"

    @Test fun testTypeTuple() {
        val ut = "unknown_def:type"
        chkStmt("val x: (text,BAD1,rowid,BAD2); val z = $badExpr1;", "ct_err:[$ut:BAD1][$ut:BAD2][$badError1]")
        chkStmt("val x: (text,(BAD1,rowid),BAD2); val z = $badExpr1;", "ct_err:[$ut:BAD1][$ut:BAD2][$badError1]")
        chkStmt("val x: (text,(BAD1,(rowid,BAD2))); val z = $badExpr1;", "ct_err:[$ut:BAD1][$ut:BAD2][$badError1]")
    }

    @Test fun testTypeMap() {
        chkStmt("val x: map<BAD1,text>; val z = $badExpr1;", "ct_err:[unknown_def:type:BAD1][$badError1]")
        chkStmt("val x: map<text,BAD2>; val z = $badExpr1;", "ct_err:[unknown_def:type:BAD2][$badError1]")
        chkStmt("val x: map<BAD1,BAD2>; val z = $badExpr1;", "ct_err:[unknown_def:type:BAD1][unknown_def:type:BAD2][$badError1]")
        chkStmt("val x: (text,(BAD1,rowid),BAD2); val z = $badExpr1;", "ct_err:[unknown_def:type:BAD1][unknown_def:type:BAD2][$badError1]")
        chkStmt("val x: (text,(BAD1,(rowid,BAD2))); val z = $badExpr1;", "ct_err:[unknown_def:type:BAD1][unknown_def:type:BAD2][$badError1]")
    }

    @Test fun testDefAttrBadType() {
        chkDefAttrBadType("entity")
        chkDefAttrBadType("struct")
        chkDefAttrBadType("object")
    }

    private fun chkDefAttrBadType(kw: String) {
        chkCompile("$kw test { x: BAD1 = 0; y: BAD2 = 0; z: integer = $badExpr1; }",
                "ct_err:[unknown_def:type:BAD1][unknown_def:type:BAD2][$badError1]")
        chkCompile("$kw test { x: BAD1 = $badExpr2; y: BAD2 = 0; z: integer = $badExpr1; }",
                "ct_err:[unknown_def:type:BAD1][$badError2][unknown_def:type:BAD2][$badError1]")
        chkCompile("$kw test { x: BAD1 = 0; y: BAD2 = $badExpr2; z: integer = $badExpr1; }",
                "ct_err:[unknown_def:type:BAD1][unknown_def:type:BAD2][$badError2][$badError1]")
    }

    @Test fun testDefEntity() {
        val un = "unknown_name"
        val ut = "unknown_def:type"

        chkCompile("entity data { a: T1 = E1; a: T2 = E2; }", "ct_err:[$ut:T1][$un:E1][dup_attr:a][$ut:T2][$un:E2]")
        chkCompile("entity data { a: T1 = E1; key a: T2 = E2; }",
                "ct_err:[$ut:T1][$un:E1][$ut:T2][entity:attr:expr_not_primary:a][$un:E2]")
        chkCompile("entity data { a: T1 = E1; index a: T2 = E2; }",
                "ct_err:[$ut:T1][$un:E1][$ut:T2][entity:attr:expr_not_primary:a][$un:E2]")
        chkCompile("entity data { a: T1 = E1; key mutable a: T2 = E2; }",
                "ct_err:[$ut:T1][$un:E1][entity:attr:mutable_not_primary:a][$ut:T2][entity:attr:expr_not_primary:a][$un:E2]")
        chkCompile("entity data { a: T1 = E1; index mutable a: T2 = E2; }",
                "ct_err:[$ut:T1][$un:E1][entity:attr:mutable_not_primary:a][$ut:T2][entity:attr:expr_not_primary:a][$un:E2]")

        chkCompile("entity data { rowid: T1 = E1; }", "ct_err:[unallowed_attr_name:rowid][$ut:T1][$un:E1]")
        chkCompile("entity data { range = E1; }", "ct_err:[entity_attr_type:range:range][$un:E1]")
        chkCompile("entity data { r: range = E1; }", "ct_err:[entity_attr_type:r:range][$un:E1]")

        chkCompile("@log entity data { transaction: T1 = E1; a: T2 = E2; }",
                "ct_err:[dup_attr:transaction][$ut:T1][$un:E1][$ut:T2][$un:E2]")
    }

    @Test fun testDefFunctionParamBadType() {
        def("function foo(x: integer, y: UNKNOWN_TYPE, z: text) {}")
        chkDefFunctionParamBadType("unknown_def:type:UNKNOWN_TYPE")
    }

    @Test fun testDefFunctionParamNoType() {
        def("function foo(x: integer, y, z: text) {}")
        chkDefFunctionParamBadType("unknown_name_type:y")
    }

    private fun chkDefFunctionParamBadType(typeErr: String) {
        tst.wrapInit = true

        chkCompile("function m() { foo(123, null, 'Hello'); }", "ct_err:$typeErr")
        chkCompile("function m() { foo(123, false, 'Hello'); }", "ct_err:$typeErr")
        chkCompile("function m() { foo(123, 0, 'Hello'); }", "ct_err:$typeErr")

        chkCompile("function m() { foo(); }", "ct_err:[$typeErr][expr:call:missing_args:foo:0:x,1:y,2:z]")
        chkCompile("function m() { foo(123, 'Hello'); }", "ct_err:[$typeErr][expr:call:missing_args:foo:2:z]")
        chkCompile("function m() { foo(123, null, 'Hello', false); }", "ct_err:[$typeErr][expr:call:too_many_args:foo:3:4]")

        chkCompile("function m() { foo('Bye', null, 'Hello'); }", "ct_err:[$typeErr][expr_call_argtype:foo:0:x:integer:text]")
        chkCompile("function m() { foo(123, null, 456); }", "ct_err:[$typeErr][expr_call_argtype:foo:2:z:text:integer]")

        chkCompile("function m() { foo('Hello', null, 123); }", """ct_err:[$typeErr]
            [expr_call_argtype:foo:0:x:integer:text]
            [expr_call_argtype:foo:2:z:text:integer]
        """)
    }

    @Test fun testDefFunctionParamDuplicate() {
        chkCompile("function foo(x: integer, x: text) {}", "ct_err:dup_param_name:x")
        chkCompile("function foo(x: integer, x: text) { val t: integer = x; }", "ct_err:dup_param_name:x")
        chkCompile("function foo(x: integer, x: text) { val t: text = x; }", "ct_err:[dup_param_name:x][stmt_var_type:t:[text]:[integer]]")
    }

    @Test fun testStmt() {
        chkStmt("print(X); print(Y); print(Z);", "ct_err:[unknown_name:X][unknown_name:Y][unknown_name:Z]")

        chkStmt("X = Y;", "ct_err:[unknown_name:X][unknown_name:Y]")
        chkStmt("X += Y;", "ct_err:[unknown_name:X][unknown_name:Y]")

        chkStmt("var x: integer; x += true;", "ct_err:[expr_var_uninit:x][binop_operand_type:+=:[integer]:[boolean]]")
    }

    @Test fun testStmtLoop() {
        chkStmt("while ('not_a_boolean') print(X);", "ct_err:[stmt_while_expr_type:[boolean]:[text]][unknown_name:X]")
        chkStmt("while (BAD) print(X);", "ct_err:[unknown_name:BAD][unknown_name:X]")

        chkStmt("for (i in true) print(X);", "ct_err:[stmt_for_expr_type:[boolean]][unknown_name:X]")
        chkStmt("for (i in BAD) print(X);", "ct_err:[unknown_name:BAD][unknown_name:X]")
    }

    @Test fun testStmtIf() {
        chkStmt("if ('not_a_boolean') print(X); else print(Y);",
                "ct_err:[stmt_if_expr_type:[boolean]:[text]][unknown_name:X][unknown_name:Y]")
        chkStmt("if (BAD) print(X); else print(Y);", "ct_err:[unknown_name:BAD][unknown_name:X][unknown_name:Y]")

        chkStmt("when (BAD) { 0 -> print(X); 'hello' -> print(Y); A -> B; else -> print(Z); }", """ct_err:
            [unknown_name:BAD]
            [unknown_name:X]
            [unknown_name:Y]
            [unknown_name:A]
            [unknown_name:B]
            [unknown_name:Z]
        """)
    }

    @Test fun testStmtReturn() {
        chkCompile("function f(): integer {}", "ct_err:fun_noreturn:f")
        chkCompile("function f(): integer { return BAD; }", "ct_err:unknown_name:BAD")
    }

    @Test fun testStmtVarType() {
        chkStmt("val x: BAD; $badExpr1;", "ct_err:[unknown_def:type:BAD][$badError1]")
        chkStmt("val x: BAD1; val y: BAD2; $badExpr1;", "ct_err:[unknown_def:type:BAD1][unknown_def:type:BAD2][$badError1]")
        chkStmt("val x: BAD = 123; $badExpr1;", "ct_err:[unknown_def:type:BAD][$badError1]")
        chkStmt("val x: BAD = $badExpr2; $badExpr1;", "ct_err:[unknown_def:type:BAD][$badError2][$badError1]")
        chkStmt("val x: BAD; $badExpr1;", "ct_err:[unknown_def:type:BAD][$badError1]")
        chkStmt("val x: BAD = 123; max(x); $badExpr1;", "ct_err:[unknown_def:type:BAD][$badError1]")
        chkStmt("val x: BAD = $badExpr2; max(x); $badExpr1;", "ct_err:[unknown_def:type:BAD][$badError2][$badError1]")
    }

    @Test fun testStmtVarDeclaratorSimple() {
        val err3 = "expr_call_argtypes:max:integer"

        chkStmt("val _; $badExpr2;", "ct_err:[unknown_name_type:_][$badError2]")
        chkStmt("val _: BAD; $badExpr2;", "ct_err:[var_wildcard_type][unknown_def:type:BAD][$badError2]")
        chkStmt("val _ = $badExpr1; $badExpr2;", "ct_err:[$badError1][$badError2]")
        chkStmt("val _: BAD = $badExpr1; $badExpr2;", "ct_err:[var_wildcard_type][unknown_def:type:BAD][$badError1][$badError2]")

        chkStmt("val x = $badExpr1; max(x); $badExpr2;", "ct_err:[$badError1][$badError2]")
        chkStmt("val x; max(x); $badExpr2;", "ct_err:[unknown_name_type:x][$badError2]")
        chkStmt("val x: BAD; max(x); $badExpr2;", "ct_err:[unknown_def:type:BAD][$badError2]")
        chkStmt("val x: BAD = 123; max(x); $badExpr2;", "ct_err:[unknown_def:type:BAD][$badError2]")

        chkStmt("val x = 123; val x; max(x); $badExpr2;", "ct_err:[block:name_conflict:x][unknown_name_type:x][$err3][$badError2]")
        chkStmt("val x = 123; val x: BAD; max(x); $badExpr2;", "ct_err:[block:name_conflict:x][unknown_def:type:BAD][$err3][$badError2]")
        chkStmt("val x = 123; val x = $badExpr1; max(x); $badExpr2;", "ct_err:[block:name_conflict:x][$badError1][$err3][$badError2]")
        chkStmt("val x = 123; val x: BAD = $badExpr1; max(x); $badExpr2;",
                "ct_err:[block:name_conflict:x][unknown_def:type:BAD][$badError1][$err3][$badError2]")

        chkStmt("val x; val x = 123; max(x); $badExpr2;", "ct_err:[unknown_name_type:x][block:name_conflict:x][$badError2]")
        chkStmt("val x; val x: integer; max(x); $badExpr2;", "ct_err:[unknown_name_type:x][block:name_conflict:x][$badError2]")
        chkStmt("val x: BAD; val x = 123; max(x); $badExpr2;", "ct_err:[unknown_def:type:BAD][block:name_conflict:x][$badError2]")
        chkStmt("val x = $badExpr1; val x = 123; max(x); $badExpr2;", "ct_err:[$badError1][block:name_conflict:x][$badError2]")
        chkStmt("val x: BAD = $badExpr1; val x = 123; max(x); $badExpr2;",
                "ct_err:[unknown_def:type:BAD][$badError1][block:name_conflict:x][$badError2]")

        chkStmt("val x = 1; val x = 2; val y = $badExpr1;", "ct_err:[block:name_conflict:x][$badError1]")
    }

    @Test fun testStmtVarDeclaratorTuple() {
        val err3 = "unknown_name:u"

        chkStmt("val (x, y) = ($badExpr1, $badExpr2); max(u);", "ct_err:[$badError1][$badError2][$err3]")
        chkStmt("val (x, y) = ($badExpr1,); max(u);", "ct_err:[var_tuple_wrongsize:2:1:(<error>,)][$badError1][$err3]")
        chkStmt("val (x, y) = ($badExpr1, $badExpr2, 123); max(u);",
                "ct_err:[var_tuple_wrongsize:2:3:(<error>,<error>,integer)][$badError1][$badError2][$err3]")

        chkStmt("val (x, y) = $badExpr1; max(u);", "ct_err:[$badError1][$err3]")
        chkStmt("val (x, (y, z)) = ($badExpr1, ($badExpr2, 123)); max(u);", "ct_err:[$badError1][$badError2][$err3]")
        chkStmt("val (x, (y, z)) = ($badExpr1); max(u);", "ct_err:[$badError1][$err3]")
        chkStmt("val (x, (y, z)) = ($badExpr1, ($badExpr2, 123), 456); max(u);",
                "ct_err:[var_tuple_wrongsize:2:3:(<error>,(<error>,integer),integer)][$badError1][$badError2][$err3]")

        chkStmt("val (x, (y, z)) = ($badExpr1, ($badExpr2)); max(u);", "ct_err:[$badError1][$badError2][$err3]")
        chkStmt("val (x, (y, z)) = ($badExpr1, ($badExpr2, 123, 456)); max(u);",
                "ct_err:[var_tuple_wrongsize:2:3:(<error>,integer,integer)][$badError1][$badError2][$err3]")

        chkStmt("val (x: A, y: B) = $badExpr1;", "ct_err:[unknown_def:type:A][unknown_def:type:B][$badError1]")
        chkStmt("val (x: A, y: B) = (1, 2, 3);",
                "ct_err:[var_tuple_wrongsize:2:3:(integer,integer,integer)][unknown_def:type:A][unknown_def:type:B]")

        chkStmt("val x = 1; val y = 2; val (x: A, y: B) = ($badExpr1, $badExpr2);",
                "ct_err:[block:name_conflict:x][unknown_def:type:A][block:name_conflict:y][unknown_def:type:B][$badError1][$badError2]")
    }

    @Test fun testStmtUpdate() {
        def("entity data { mutable i: integer; mutable j: integer; }")
        val un = "unknown_name"

        chkStmt("update (a: data, a: data) @* { P, Q } ( X, Y );", "ct_err:[block:name_conflict:a][$un:P][$un:Q][$un:X][$un:Y]")
        chkStmt("update data @* {} ( i += true, j += 'hello' );",
                "ct_err:[binop_operand_type:+=:[integer]:[boolean]][binop_operand_type:+=:[integer]:[text]]")
        chkStmt("update data @* { X } ( i = $badExpr1, j = $badExpr2 );", "ct_err:[$un:X][$badError1][$badError2]")
        chkStmt("update data @* { X } ( i += $badExpr1, j += $badExpr2 );", "ct_err:[$un:X][$badError1][$badError2]")

        chkStmt("val a = 1; val b = 2; update (a: data, b: data) @* { X } ( Y );",
                "ct_err:[block:name_conflict:a][block:name_conflict:b][$un:X][$un:Y]")

        chkStmt("update 123 ( $badExpr1 );", "ct_err:[stmt_update_expr_type:integer][$badError1]")
        chkStmt("update ($badExpr1) ( $badExpr2 );", "ct_err:[$badError1][$badError2]")
    }

    @Test fun testStmtUpdateNotMutable() {
        def("@external('foo') @log entity ext_data { i: integer; j: integer; }")
        def("entity data { i: integer; j: integer; }")
        val suc = "stmt_update_cant"
        val uanm = "update_attr_not_mutable"

        chkStmt("update data @* { $badExpr1 } ( i = $badExpr2 );", "ct_err:[$badError1][$uanm:i][$badError2]")
        chkStmt("update data @* {} ( i = $badExpr1, j = $badExpr2 );", "ct_err:[$uanm:i][$badError1][$uanm:j][$badError2]")
        chkStmt("update data @* { $badExpr1, $badExpr2 } ( i = 1 );", "ct_err:[$badError1][$badError2][$uanm:i]")

        chkStmt("update ext_data @* { $badExpr1 } ( i = $badExpr2 );", "ct_err:[$suc:ext_data][$badError1][$badError2]")
        chkStmt("update ext_data @* { $badExpr1, $badExpr2 } ();", "ct_err:[$suc:ext_data][$badError1][$badError2]")
        chkStmt("update ext_data @* {} ( i = $badExpr1, j = $badExpr2 );", "ct_err:[$suc:ext_data][$badError1][$badError2]")
    }

    @Test fun testExprUnaryOp() {
        def("struct data { x: integer; }")

        chkExprUnaryOp("+", "+x", "text")
        chkExprUnaryOp("-", "-x", "text")
        chkExprUnaryOp("not", "not x", "text")
        chkExprUnaryOp("!!", "x!!", "integer")
        chkExprUnaryOp("??", "x??", "integer")

        chkUnaryOpIncDec("++", "x++")
        chkUnaryOpIncDec("++", "++x")
        chkUnaryOpIncDec("--", "x--")
        chkUnaryOpIncDec("--", "--x")
    }

    private fun chkExprUnaryOp(op: String, expr: String, wrongType: String) {
        chkCompile("function f(x: $wrongType) = $expr;", "ct_err:unop_operand_type:$op:[$wrongType]")
        chkCompile("function f(x: $wrongType) = ($expr) + $badExpr1;", "ct_err:[unop_operand_type:$op:[$wrongType]][$badError1]")

        chk(expr.replace("x", badExpr1), "ct_err:$badError1")
    }

    private fun chkUnaryOpIncDec(op: String, expr: String) {
        chkEx("{ var x = 'hi'; return ($expr) + $badExpr1; }", "ct_err:[expr_incdec_type:$op:text][$badError1]")
    }

    @Test fun testExprBinaryOp() {
        def("struct data { x: integer; }")

        chk("1 + false + ($badExpr1) + ($badExpr2)", "ct_err:[binop_operand_type:+:[integer]:[boolean]][$badError1][$badError2]")
        chk("false + ($badExpr1) + ($badExpr2)", "ct_err:[$badError1][$badError2]")
        chk("($badExpr1) + ($badExpr2)", "ct_err:[$badError1][$badError2]")

        chk("($badExpr1) ?: ($badExpr2)", "ct_err:[expr_call_argtypes:abs:byte_array][expr_call_argtypes:min:]")
        chk("($badExpr1) ?: 123", "ct_err:expr_call_argtypes:abs:byte_array")
        chk("123 ?: ($badExpr2)", "ct_err:expr_call_argtypes:min:")

        chk("($badExpr1)?.foo", "ct_err:expr_call_argtypes:abs:byte_array")

        chkCompile("function f(d: data) = d?.x;", "ct_err:expr_safemem_type:[data]")
        chkCompile("function f(d: data) = d?.q;", "ct_err:[expr_safemem_type:[data]][unknown_member:[data]:q]")
    }

    @Test fun testExprAt() {
        def("entity user { address: text; }")
        val un = "unknown_name"

        chk("X @* { Y } ( Z )", "ct_err:[$un:X][$un:Y][$un:Z]")
        chk("(X1, X2) @* { Y1, Y2 } ( Z1, Z2 )", "ct_err:[$un:X1][$un:X2][$un:Y1][$un:Y2][$un:Z1][$un:Z2]")

        chk("user @* { 123, Y } ( Z )", "ct_err:[at_where_type:0:integer][$un:Y][$un:Z]")

        chkCompile("query q(address: integer) = user @* { address, X } ( Y );",
                "ct_err:[at_where:var_noattrs:0:address:integer][$un:X][$un:Y]")
    }

    @Test fun testExprAtFrom() {
        def("entity user { address: text; }")
        val un = "unknown_name"

        chk("false @* { Y } ( Z )", "ct_err:[at:from:bad_type:boolean][$un:Y][$un:Z]")

        chk("(false, true) @* { Y } ( Z )",
                "ct_err:[at:from:bad_type:boolean][at:from:bad_type:boolean][at:from:many_iterables:2][$un:Y][$un:Z]")

        chk("([123], user) @* { Y } ( Z )", "ct_err:[at:from:mix_entity_iterable][$un:Y][$un:Z]")

        chk("(a = X1, b = X2) @* { Y } ( Z )",
                "ct_err:[expr:at:from:tuple_name_eq_expr:a][$un:X1][expr:at:from:tuple_name_eq_expr:b][$un:X2][$un:Y][$un:Z]")
    }

    @Test fun testExprAtFromAlias() {
        def("entity user { address: text; }")
        val un = "unknown_name"

        chk("(user: user, user) @* { Y } ( Z )", "ct_err:[$un:Y][$un:Z]")
        chk("(a: user, a: user) @* { Y } ( Z )", "ct_err:[block:name_conflict:a][$un:Y][$un:Z]")

        chkEx("{ val u = 123; return (u: user) @ { u.address == 'Street' }; }",
                "ct_err:[block:name_conflict:u][unknown_member:[integer]:address]")
        chkEx("{ val a = 1; val b = 2; return (a: user, b: user) @* { $badExpr1 } ( $badExpr2 ); }",
                "ct_err:[block:name_conflict:a][block:name_conflict:b][expr_call_argtypes:abs:byte_array][expr_call_argtypes:min:]")

        chkCompile("query q() { val a = 123; val b = 456; return (a: user, b: user) @* { X } ( Y ); }",
                "ct_err:[block:name_conflict:a][block:name_conflict:b][$un:X][$un:Y]")
        chkCompile("query q() { val a = 123; val b = 456; return (a: $badExpr1, b: $badExpr2) @* { X } ( Y ); }",
                "ct_err:[block:name_conflict:a][$badError1][$badError2][$un:X][$un:Y]")
    }

    @Test fun testExprTuple() {
        chk("(a = $badExpr1, b = $badExpr2)", "ct_err:[$badError1][$badError2]")
        chk("(a: $badExpr1, b: $badExpr2)", "ct_err:[tuple_name_colon_expr:a][$badError1][tuple_name_colon_expr:b][$badError2]")
        chk("(a = $badExpr1, a = $badExpr2)", "ct_err:[$badError1][expr_tuple_dupname:a][$badError2]")
    }

    @Test fun testExprCall() {
        def("function f(x: integer, y: text) = x + y;")
        def("function g(x: integer) = x != 0;")

        chk("(f)(123,'Bob')", "text[123Bob]")
        chk("f($badExpr1, $badExpr2)", "ct_err:[$badError1][$badError2]")
        chk("Q($badExpr1, $badExpr2)", "ct_err:[unknown_name:Q][$badError1][$badError2]")
        chk("($badExpr1)($badExpr2)", "ct_err:[$badError1][$badError2]")

        chk("g($badExpr1)", "ct_err:$badError1")
        chk("g($badExpr1, $badExpr2)", "ct_err:[expr:call:too_many_args:g:1:2][$badError1][$badError2]")
        chk("g($badExpr1) + 123", "ct_err:[$badError1][binop_operand_type:+:[boolean]:[integer]]")

        chk("'hello'.sub($badExpr1, $badExpr2)", "ct_err:[$badError1][$badError2]")
        chk("'hello'.Q($badExpr1, $badExpr2)", "ct_err:[unknown_member:[text]:Q][$badError1][$badError2]")
        chk("($badExpr1).q($badExpr2)", "ct_err:[$badError1][$badError2]")
    }

    @Test fun testExprWhen() {
        def("enum color { red, green, blue }")

        chk("when($badExpr1) { $badExpr2 -> 0 }", "ct_err:[when_no_else][$badError1][$badError2]")
        chk("when($badExpr1) { 0 -> $badExpr2 }", "ct_err:[when_no_else][$badError1][$badError2]")
        chk("when($badExpr1) { else -> $badExpr2 }", "ct_err:[$badError1][$badError2]")

        val q = "query q(c: color)"
        chkCompile("$q = when (c) { red -> $badExpr1; green -> $badExpr2 };", "ct_err:[when_no_else][$badError1][$badError2]")
        chkCompile("$q = when (c) { $badExpr1 -> $badExpr2 };", "ct_err:[when_no_else][$badError1][$badError2]")
        chkCompile("$q = when (c) { $badExpr1 -> 0; $badExpr2 -> 1 };", "ct_err:[when_no_else][$badError1][$badError2]")
        chkCompile("$q = when (c) { $badExpr1 -> 0; red -> $badExpr2 };", "ct_err:[when_no_else][$badError1][$badError2]")
        chkCompile("$q = when (c) { $badExpr1 -> 0; else -> $badExpr2 };", "ct_err:[$badError1][$badError2]")

        chkCompile("query q(x: boolean) = when (x) { true -> 0; false -> $badExpr1; else -> $badExpr2 };",
                "ct_err:[$badError1][when_else_allvalues:boolean][$badError2]")
    }

    @Test fun testExprMember() {
        chk("$badExpr1.foo", "ct_err:$badError1")
        chk("$badExpr1.foo()", "ct_err:$badError1")
        chk("$badExpr1.foo.bar", "ct_err:$badError1")
        chk("$badExpr1.foo.bar()", "ct_err:$badError1")
        chk("$badExpr1.foo($badExpr2)", "ct_err:[$badError1][$badError2]")
    }

    private fun chkStmt(code: String, expected: String) = chkCompile("function f() { $code }", expected)
}
