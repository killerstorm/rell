/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.testutils.BaseRellTest
import org.junit.Test

class LibRequireTest: BaseRellTest(false) {
    @Test fun testRequireBoolean() {
        chkEx("{ require(true); return 0; }", "int[0]")
        chkEx("{ require(true, 'Hello'); return 0; }", "int[0]")
        chkEx("{ require(false); return 0; }", "req_err:null")
        chkEx("{ require(false, 'Hello'); return 0; }", "req_err:[Hello]")
        chkEx("{ val x = require(true); return 0; }", "ct_err:stmt_var_unit:x")

        chkEx("{ require(true, ''+(1/0)); return 0; }", "int[0]")
        chkEx("{ require(false, ''+(1/0)); return 0; }", "rt_err:expr:/:div0:1")
    }

    @Test fun testRequireNullable() {
        chkEx("{ val x: integer = 123; return require(x); }", "ct_err:expr_call_argtypes:[require]:integer")
        chkEx("{ val x: integer? = _nullable(123); return require(x); }", "int[123]")
        chkEx("{ val x: integer? = null; return require(x); }", "req_err:null")
    }

    @Test fun testRequireAt() {
        tstCtx.useSql = true
        def("entity user { name: text; }")
        chkOp("create user(name = 'Bob'); create user(name = 'Alice');")

        chkEx("{ return require(user @? { .name == 'Bob' }, 'User not found'); }", "user[1]")
        chkEx("{ return require(user @? { .name == 'Alice' }, 'User not found'); }", "user[2]")
        chkEx("{ return require(user @? { .name == 'Trudy' }, 'User not found'); }", "req_err:[User not found]")
        chkEx("{ return require(user @? {}, 'User not found'); }", "rt_err:at:wrong_count:2")

        chkEx("{ return _type_of(user @? { .name == 'Bob' }); }", "text[user?]")
        chkEx("{ return _type_of(require(user @? { .name == 'Bob' })); }", "text[user]")

        chkEx("{ return require(user @ { .name == 'Bob' }); }", "ct_err:expr_call_argtypes:[require]:user")
        chkEx("{ return require(user @+ { .name == 'Bob' }); }", "ct_err:expr_call_argtypes:[require]:list<user>")
        chkEx("{ return require(user @* { .name == 'Bob' }); }", "ct_err:expr_call_argtypes:[require]:list<user>")
    }

    @Test fun testRequireWrongArgs() {
        chkEx("{ require(); return 0; }", "ct_err:expr_call_argtypes:[require]:")
        chkEx("{ require(null); return 0; }", "ct_err:expr_call_argtypes:[require]:null")
        chkEx("{ require(123); return 0; }", "ct_err:expr_call_argtypes:[require]:integer")
        chkEx("{ require('Hello'); return 0; }", "ct_err:expr_call_argtypes:[require]:text")
        chkEx("{ require([123]); return 0; }", "ct_err:expr_call_argtypes:[require]:list<integer>")
        chkEx("{ require([123:'Hello']); return 0; }", "ct_err:expr_call_argtypes:[require]:map<integer,text>")
    }

    @Test fun testRequireNotEmptyNullable() {
        chkEx("{ val x: integer = 123; return require_not_empty(x); }", "ct_err:expr_call_argtypes:[require_not_empty]:integer")
        chkEx("{ val x: integer? = _nullable(123); return require_not_empty(x); }", "int[123]")
        chkEx("{ val x: integer? = null; return require_not_empty(x); }", "req_err:null")
    }

    @Test fun testRequireNotEmptyCollection() {
        chkEx("{ val x = list<integer>(); return require_not_empty(x); }", "req_err:null")
        chkEx("{ val x = [123]; return require_not_empty(x); }", "list<integer>[int[123]]")
        chkEx("{ val x: list<integer>? = null; return require_not_empty(x); }", "req_err:null")
        chkEx("{ val x: list<integer>? = list<integer>(); return require_not_empty(x); }", "req_err:null")
        chkEx("{ val x: list<integer>? = [123]; return require_not_empty(x); }", "list<integer>[int[123]]")

        chkEx("{ val x = set<integer>(); return require_not_empty(x); }", "req_err:null")
        chkEx("{ val x = set([123]); return require_not_empty(x); }", "set<integer>[int[123]]")
        chkEx("{ val x: set<integer>? = null; return require_not_empty(x); }", "req_err:null")
        chkEx("{ val x: set<integer>? = set<integer>(); return require_not_empty(x); }", "req_err:null")
        chkEx("{ val x: set<integer>? = set([123]); return require_not_empty(x); }", "set<integer>[int[123]]")

        chkEx("{ val x: list<integer>? = _nullable([123]); return require_not_empty(x); }", "list<integer>[int[123]]")
        chkEx("{ val x: list<integer>? = _nullable(list<integer>()); return require_not_empty(x); }", "req_err:null")
        chkEx("{ val x: set<integer>? = _nullable(set([123])); return require_not_empty(x); }", "set<integer>[int[123]]")
        chkEx("{ val x: set<integer>? = _nullable(set<integer>()); return require_not_empty(x); }", "req_err:null")

        chkEx("{ val x: list<integer>? = _nullable([123]); return _type_of(x); }", "text[list<integer>?]")
        chkEx("{ val x: list<integer>? = _nullable([123]); return _type_of(require_not_empty(x)); }", "text[list<integer>]")
    }

    @Test fun testRequireNotEmptyMap() {
        val type = "map<integer,text>"

        chkEx("{ val x = map<integer,text>(); return require_not_empty(x); }", "req_err:null")
        chkEx("{ val x = map([123:'Hello']); return require_not_empty(x); }", "map<integer,text>[int[123]=text[Hello]]")
        chkEx("{ val x: $type? = null; return require_not_empty(x); }", "req_err:null")
        chkEx("{ val x: $type? = map<integer,text>(); return require_not_empty(x); }", "req_err:null")
        chkEx("{ val x: $type? = [123:'Hello']; return require_not_empty(x); }", "map<integer,text>[int[123]=text[Hello]]")

        chkEx("{ val x: $type? = _nullable([123:'A']); return require_not_empty(x); }", "map<integer,text>[int[123]=text[A]]")
        chkEx("{ val x: $type? = _nullable(map<integer,text>()); return require_not_empty(x); }", "req_err:null")

        chkEx("{ val x: $type? = _nullable([123:'A']); return _type_of(x); }", "text[map<integer,text>?]")
        chkEx("{ val x: $type? = _nullable([123:'A']); return _type_of(require_not_empty(x)); }", "text[map<integer,text>]")
    }

    @Test fun testRequireNotEmptyWrongArgs() {
        chkEx("{ require_not_empty(); return 0; }", "ct_err:expr_call_argtypes:[require_not_empty]:")
        chkEx("{ require_not_empty(null); return 0; }", "ct_err:fn:sys:unresolved_type_params:require_not_empty:T")
        chkEx("{ require_not_empty(false); return 0; }", "ct_err:expr_call_argtypes:[require_not_empty]:boolean")
        chkEx("{ require_not_empty(true); return 0; }", "ct_err:expr_call_argtypes:[require_not_empty]:boolean")
        chkEx("{ require_not_empty(123); return 0; }", "ct_err:expr_call_argtypes:[require_not_empty]:integer")
        chkEx("{ require_not_empty('Hello'); return 0; }", "ct_err:expr_call_argtypes:[require_not_empty]:text")
    }
}
