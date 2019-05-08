package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibRequireTest: BaseRellTest(false) {
    @Test fun testRequireBoolean() {
        chkEx("{ require(true); return 0; }", "int[0]")
        chkEx("{ require(true, 'Hello'); return 0; }", "int[0]")
        chkEx("{ require(false); return 0; }", "req_err:null")
        chkEx("{ require(false, 'Hello'); return 0; }", "req_err:[Hello]")
        chkEx("{ val x = require(true); return 0; }", "ct_err:stmt_var_unit:x")

        chkEx("{ require(true, ''+(1/0)); return 0; }", "int[0]")
        chkEx("{ require(false, ''+(1/0)); return 0; }", "rt_err:expr_div_by_zero")
    }

    @Test fun testRequireNullable() {
        chkEx("{ val x: integer = 123; return require(x); }", "ct_err:expr_call_argtypes:require:integer")
        chkEx("{ val x: integer? = _nullable(123); return require(x); }", "int[123]")
        chkEx("{ val x: integer? = null; return require(x); }", "req_err:null")
    }

    @Test fun testRequireAt() {
        tstCtx.useSql = true
        tst.defs = listOf("class user { name: text; }")
        chkOp("create user(name = 'Bob'); create user(name = 'Alice');")

        chkEx("{ return require(user @? { .name == 'Bob' }, 'User not found'); }", "user[1]")
        chkEx("{ return require(user @? { .name == 'Alice' }, 'User not found'); }", "user[2]")
        chkEx("{ return require(user @? { .name == 'Trudy' }, 'User not found'); }", "req_err:[User not found]")
        chkEx("{ return require(user @? {}, 'User not found'); }", "rt_err:at:wrong_count:2")

        chkEx("{ return _typeOf(user @? { .name == 'Bob' }); }", "text[user?]")
        chkEx("{ return _typeOf(require(user @? { .name == 'Bob' })); }", "text[user]")

        chkEx("{ return require(user @ { .name == 'Bob' }); }", "ct_err:expr_call_argtypes:require:user")
        chkEx("{ return require(user @+ { .name == 'Bob' }); }", "ct_err:expr_call_argtypes:require:list<user>")
        chkEx("{ return require(user @* { .name == 'Bob' }); }", "ct_err:expr_call_argtypes:require:list<user>")
    }

    @Test fun testRequireWrongArgs() {
        chkEx("{ require(); return 0; }", "ct_err:expr_call_argtypes:require:")
        chkEx("{ require(null); return 0; }", "ct_err:expr_call_argtypes:require:null")
        chkEx("{ require(123); return 0; }", "ct_err:expr_call_argtypes:require:integer")
        chkEx("{ require('Hello'); return 0; }", "ct_err:expr_call_argtypes:require:text")
        chkEx("{ require([123]); return 0; }", "ct_err:expr_call_argtypes:require:list<integer>")
        chkEx("{ require([123:'Hello']); return 0; }", "ct_err:expr_call_argtypes:require:map<integer,text>")
    }

    @Test fun testRequireNotEmptyNullable() {
        chkEx("{ val x: integer = 123; return requireNotEmpty(x); }", "ct_err:expr_call_argtypes:requireNotEmpty:integer")
        chkEx("{ val x: integer? = _nullable(123); return requireNotEmpty(x); }", "int[123]")
        chkEx("{ val x: integer? = null; return requireNotEmpty(x); }", "req_err:null")
    }

    @Test fun testRequireNotEmptyCollection() {
        chkEx("{ val x = list<integer>(); return requireNotEmpty(x); }", "req_err:null")
        chkEx("{ val x = [123]; return requireNotEmpty(x); }", "list<integer>[int[123]]")
        chkEx("{ val x: list<integer>? = null; return requireNotEmpty(x); }", "req_err:null")
        chkEx("{ val x: list<integer>? = list<integer>(); return requireNotEmpty(x); }", "req_err:null")
        chkEx("{ val x: list<integer>? = [123]; return requireNotEmpty(x); }", "list<integer>[int[123]]")

        chkEx("{ val x = set<integer>(); return requireNotEmpty(x); }", "req_err:null")
        chkEx("{ val x = set([123]); return requireNotEmpty(x); }", "set<integer>[int[123]]")
        chkEx("{ val x: set<integer>? = null; return requireNotEmpty(x); }", "req_err:null")
        chkEx("{ val x: set<integer>? = set<integer>(); return requireNotEmpty(x); }", "req_err:null")
        chkEx("{ val x: set<integer>? = set([123]); return requireNotEmpty(x); }", "set<integer>[int[123]]")

        chkEx("{ val x = map<integer,text>(); return requireNotEmpty(x); }", "req_err:null")
        chkEx("{ val x = map([123:'Hello']); return requireNotEmpty(x); }", "map<integer,text>[int[123]=text[Hello]]")
        chkEx("{ val x: map<integer,text>? = null; return requireNotEmpty(x); }", "req_err:null")
        chkEx("{ val x: map<integer,text>? = map<integer,text>(); return requireNotEmpty(x); }", "req_err:null")
        chkEx("{ val x: map<integer,text>? = [123:'Hello']; return requireNotEmpty(x); }", "map<integer,text>[int[123]=text[Hello]]")

        chkEx("{ val x: list<integer>? = _nullable([123]); return _typeOf(x); }", "text[list<integer>?]")
        chkEx("{ val x: list<integer>? = _nullable([123]); return _typeOf(requireNotEmpty(x)); }", "text[list<integer>]")
    }

    @Test fun testRequireNotEmptyWrongArgs() {
        chkEx("{ requireNotEmpty(); return 0; }", "ct_err:expr_call_argtypes:requireNotEmpty:")
        chkEx("{ requireNotEmpty(null); return 0; }", "ct_err:expr_call_argtypes:requireNotEmpty:null")
        chkEx("{ requireNotEmpty(false); return 0; }", "ct_err:expr_call_argtypes:requireNotEmpty:boolean")
        chkEx("{ requireNotEmpty(true); return 0; }", "ct_err:expr_call_argtypes:requireNotEmpty:boolean")
        chkEx("{ requireNotEmpty(123); return 0; }", "ct_err:expr_call_argtypes:requireNotEmpty:integer")
        chkEx("{ requireNotEmpty('Hello'); return 0; }", "ct_err:expr_call_argtypes:requireNotEmpty:text")
    }
}
