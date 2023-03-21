/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.ide

import org.junit.Test

class IdeSymbolLinkTest: BaseIdeSymbolTest() {
    @Test fun testAtAliasColExplicit() {
        val vDef = "v:LOC_AT_ALIAS;-;-"
        val vRef = "v:LOC_AT_ALIAS;-;local[v:0]"
        chkKdls("query q() = (v: [123]) @* { v.abs() > 0 };", "q:*", vDef, vRef, "abs:*")
        chkKdls("query q() = (v: [123]) @* {} ( v.abs() > 0 );", "q:*", vDef, vRef, "abs:*")
    }

    @Test fun testAtAliasColDollar() {
        file("def.rell", "function data() = [123];")
        chkKdls("query q() = data() @* { $.abs() > 0 };", "q:*", "data:*", "$:LOC_AT_ALIAS;-;local[data:0]", "abs:*")
        chkKdls("query q() = data() @* {} ( $.abs() );", "q:*", "data:*", "$:LOC_AT_ALIAS;-;local[data:0]", "abs:*")
    }

    @Test fun testAtAliasDbImplicitSelect() {
        file("lib.rell", "module; entity user { name; } entity company { name; }")
        chkAtAliasDbImplicit1("user @* { user.name != '' }")
        chkAtAliasDbImplicit1("user @* {} ( user.name )")
        chkAtAliasDbImplicit2("(user, company) @* { user.name != '', company.name != '' }")
        chkAtAliasDbImplicit2("(user, company) @* {} ( user.name + company.name)")
    }

    @Test fun testAtAliasDbImplicitUpdate() {
        file("lib.rell", "module; entity user { mutable name; } entity company { name; }")
        chkAtAliasDbImplicit1("update user @* { user.name != '' } ()")
        chkAtAliasDbImplicit1("update user @* {} ( user.name )")
        chkAtAliasDbImplicit2("update (user, company) @* { user.name != '', company.name != '' } ()")
        chkAtAliasDbImplicit2("update (user, company) @* {} ( user.name + company.name )")
    }

    @Test fun testAtAliasDbImplicitDelete() {
        file("lib.rell", "module; entity user { mutable name; } entity company { name; }")
        chkAtAliasDbImplicit1("delete user @* { user.name != '' }")
        chkAtAliasDbImplicit2("delete (user, company) @* { user.name != '', company.name != '' }")
    }

    private fun chkAtAliasDbImplicit1(stmt: String) {
        val userRef = "user:DEF_ENTITY;-;lib.rell/entity[user]"
        val itemRef = "user:LOC_AT_ALIAS;-;local[user:0]"
        chkKdls("import lib.*; function f() { $stmt; }", "lib:*", "f:*", userRef, itemRef, "name:*;*;*")
    }

    private fun chkAtAliasDbImplicit2(stmt: String) {
        chkKdls("import lib.*; function f() { $stmt; }",
            "lib:*", "f:*",
            "user:DEF_ENTITY;-;lib.rell/entity[user]", "company:DEF_ENTITY;-;lib.rell/entity[company]",
            "user:LOC_AT_ALIAS;-;local[user:0]", "name:*;-;*",
            "company:LOC_AT_ALIAS;-;local[company:0]", "name:*;-;*",
        )
    }

    @Test fun testAtAliasDbExplicitSelect() {
        file("lib.rell", "module; entity user { name; } entity company { name; }")
        chkAtAliasDbExplicit1("(u: user) @* { u.name != '' }")
        chkAtAliasDbExplicit1("(u: user) @* {} ( u.name )")
        chkAtAliasDbExplicit2("(u: user, c: company) @* { u.name != '', c.name != '' }")
        chkAtAliasDbExplicit2("(u: user, c: company) @* {} ( u.name + c.name )")
    }

    @Test fun testAtAliasDbExplicitUpdate() {
        file("lib.rell", "module; entity user { mutable name; } entity company { name; }")
        chkAtAliasDbExplicit1("update (u: user) @* { u.name != '' } ()")
        chkAtAliasDbExplicit1("update (u: user) @* {} ( u.name )")
        chkAtAliasDbExplicit2("update (u: user, c: company) @* { u.name != '', c.name != '' } ()")
        chkAtAliasDbExplicit2("update (u: user, c: company) @* {} ( u.name + c.name )")
    }

    @Test fun testAtAliasDbExplicitDelete() {
        file("lib.rell", "module; entity user { mutable name; } entity company { name; }")
        chkAtAliasDbExplicit1("delete (u: user) @* { u.name != '' }")
        chkAtAliasDbExplicit2("delete (u: user, c: company) @* { u.name != '', c.name != '' }")
    }

    private fun chkAtAliasDbExplicit1(stmt: String) {
        val userRef = "user:DEF_ENTITY;-;lib.rell/entity[user]"
        val uDef = "u:LOC_AT_ALIAS;-;-"
        val uRef = "u:LOC_AT_ALIAS;-;local[u:0]"
        chkKdls("import lib.*; function f() { $stmt; }", "lib:*", "f:*", uDef, userRef, uRef, "name:*;*;*")
    }

    private fun chkAtAliasDbExplicit2(stmt: String) {
        chkKdls("import lib.*; function f() { $stmt; }",
            "lib:*", "f:*",
            "u:LOC_AT_ALIAS;-;-", "user:DEF_ENTITY;-;lib.rell/entity[user]",
            "c:LOC_AT_ALIAS;-;-", "company:DEF_ENTITY;-;lib.rell/entity[company]",
            "u:LOC_AT_ALIAS;-;local[u:0]", "name:*;-;*",
            "c:LOC_AT_ALIAS;-;local[c:0]", "name:*;-;*",
        )
    }

    @Test fun testAtAliasDbDollarSelect() {
        file("lib.rell", "module; entity user { name; } entity company { name; }")
        chkAtAliasDbDollar1("user @* { $.name != '' }")
        chkAtAliasDbDollar1("user @* {} ( $.name )")
        chkAtAliasDbDollar2("(user, company) @* { $.name != '' }")
        chkAtAliasDbDollar2("(user, company) @* {} ( $.name )")
    }

    @Test fun testAtAliasDbDollarUpdate() {
        file("lib.rell", "module; entity user { mutable name; } entity company { name; }")
        chkAtAliasDbDollar1("update user @* { $.name != '' } ()")
        chkAtAliasDbDollar1("update user @* {} ( $.name )")
        chkAtAliasDbDollar2("update (user, company) @* { $.name != '' } ()")
        chkAtAliasDbDollar2("update (user, company) @* {} ( $.name )")
    }

    @Test fun testAtAliasDbDollarDelete() {
        file("lib.rell", "module; entity user { mutable name; } entity company { name; }")
        chkAtAliasDbDollar1("delete user @* { $.name != '' }")
        chkAtAliasDbDollar2("delete (user, company) @* { $.name != '' }")
    }

    private fun chkAtAliasDbDollar1(stmt: String) {
        val userRef = "user:DEF_ENTITY;-;lib.rell/entity[user]"
        val itemRef = "$:LOC_AT_ALIAS;-;local[user:0]"
        chkKdls("import lib.*; function f() { $stmt; }", "lib:*", "f:*", userRef, itemRef, "name:???*;*;???*")
    }

    private fun chkAtAliasDbDollar2(stmt: String) {
        chkKdlsErr("import lib.*; function f() { $stmt; }", "name:ambiguous:$",
            "lib:*", "f:*",
            "user:DEF_ENTITY;-;lib.rell/entity[user]",
            "company:DEF_ENTITY;-;lib.rell/entity[company]",
            "$:LOC_AT_ALIAS;-;local[user:0]",
            "name:*;*;*"
        )
    }

    @Test fun testAtWhatSimpleCol() {
        file("data.rell", "struct user { name; company; } struct company { name; }")
        file("fn.rell", "function users(): list<user> = [];")

        chkKdls("query q() = users() @*{}.name;",
            "q:*;*;-",
            "users:DEF_FUNCTION;-;fn.rell/function[users]",
            "name:MEM_STRUCT_ATTR;-;data.rell/struct[user].attr[name]"
        )

        chkKdls("query q() = users() @*{}.company.name;",
            "q:*;*;-",
            "users:DEF_FUNCTION;-;fn.rell/function[users]",
            "company:MEM_STRUCT_ATTR;-;data.rell/struct[user].attr[company]",
            "name:MEM_STRUCT_ATTR;-;data.rell/struct[company].attr[name]"
        )
    }

    @Test fun testAtWhatSimpleDb() {
        file("data.rell", "entity user { name; company; } entity company { name; }")

        chkKdls("query q() = user @*{}.name;",
            "q:*;*;-",
            "user:DEF_ENTITY;-;data.rell/entity[user]",
            "name:MEM_ENTITY_ATTR_NORMAL;-;data.rell/entity[user].attr[name]"
        )

        chkKdls("query q() = user @*{}.company.name;",
            "q:*;*;-",
            "user:DEF_ENTITY;-;data.rell/entity[user]",
            "company:MEM_ENTITY_ATTR_NORMAL;-;data.rell/entity[user].attr[company]",
            "name:MEM_ENTITY_ATTR_NORMAL;-;data.rell/entity[company].attr[name]"
        )
    }

    @Test fun testDefInNamespace() {
        file("lib.rell", "module; namespace foo.bar { enum color {} }")
        chkFileKdls("lib.rell", "foo:*;*;-", "bar:*;*;-", "color:DEF_ENUM;enum[foo.bar.color];-")

        val fooRef = "foo:DEF_NAMESPACE;-;lib.rell/namespace[foo]"
        val barRef = "bar:DEF_NAMESPACE;-;lib.rell/namespace[foo.bar]"
        val colorRef = "color:DEF_ENUM;-;lib.rell/enum[foo.bar.color]"

        chkKdls("import lib; val k: lib.foo.bar.color? = null;", "lib:*;*;lib.rell", "k:*;*;-", "lib:*;-;*", fooRef, barRef, colorRef)
        chkKdls("import lib.*; val k: foo.bar.color? = null;", "lib:*;*;lib.rell", "k:*;*;-", fooRef, barRef, colorRef)
        chkKdls("import lib.{foo}; val k: foo.bar.color? = null;", "lib:*;*;lib.rell", fooRef, "k:*;*;-", fooRef, barRef, colorRef)
        chkKdls("import lib.{foo.bar}; val k: bar.color? = null;", "lib:*", fooRef, barRef, "k:*;*;-", barRef, colorRef)
        chkKdls("import lib.{foo.bar.color}; val k: color? = null;", "lib:*", fooRef, barRef, colorRef, "k:*;*;-", colorRef)
        chkKdls("import lib.{foo.bar.*}; val k: color? = null;", "lib:*;-;*", fooRef, barRef, "k:*;*;-", colorRef)
    }

    @Test fun testEntity() {
        file("lib.rell", "module; entity user {} struct data { u: user; }")

        val entityRef = "user:DEF_ENTITY;-;lib.rell/entity[user]"
        chkFileKdls("lib.rell", "user:DEF_ENTITY;entity[user];-", "data:*;*;-", "u:*;*-", entityRef)

        chkKdls("import lib.*; struct s { u: user; }", "lib:*;-;*", "s:*;*;-", "u:*;*;-", entityRef)
        chkKdls("import lib.*; query q(): user? = null;", "lib:*", "q:*;*;-", entityRef)
        chkKdls("import lib.*; query q(): struct<user>? = null;", "lib:*", "q:*;*;-", entityRef)
        chkKdls("import lib.*; query q() = user @* {};", "lib:*", "q:*;*;-", entityRef)
        chkKdls("import lib.*; function f() { create user(); }", "lib:*", "f:*;*;-", entityRef)
        chkKdls("import lib.*; function f() { delete user @* {}; }", "lib:*", "f:*;*;-", entityRef)
        chkKdls("import lib.*; function f() { update user @* {} (); }", "lib:*", "f:*;*;-", entityRef)
        chkKdls("import lib.{user}; struct s { u: user; }", "lib:*", entityRef, "s:*;*;-", "u:*;*;-", entityRef)
        chkKdls("import lib; struct s { u: lib.user; }", "lib:*", "s:*;*;-", "u:*;*;-", "lib:*", entityRef)
    }

    @Test fun testEntityAttrNamed() {
        file("lib.rell", "module; entity data { mutable x: integer; }")
        chkFileKdls("lib.rell", "data:*", "x:MEM_ENTITY_ATTR_NORMAL_VAR;entity[data].attr[x];-", "integer:*;-;*")

        val attrRef = "x:MEM_ENTITY_ATTR_NORMAL_VAR;*;lib.rell/entity[data].attr[x]"
        chkKdls("import lib.*; function f(d: data) = d.x;", "lib:*", "f:*", "d:*", "data:*", "d:*", attrRef)
        chkKdls("import lib.*; function f(d: data) { d.x = 0; }", "lib:*", "f:*", "d:*", "data:*", "d:*", attrRef)

        chkKdls("import lib.*; function f() = data @* { .x == 0 };", "lib:*", "f:*", "data:*", attrRef)
        chkKdls("import lib.*; function f() = data @* {} (.x);", "lib:*", "f:*", "data:*", attrRef)
        chkKdls("import lib.*; function f() = data @* {} .x;", "lib:*", "f:*", "data:*", attrRef)
        chkKdls("import lib.*; function f() = data @* {} ($.x);", "lib:*", "f:*", "data:*", "$:*", attrRef)
        chkKdls("import lib.*; function f() = data @* {} (data.x);", "lib:*", "f:*", "data:*", "data:*", attrRef)

        chkKdls("import lib.*; function f() = create data(x = 0);", "lib:*", "f:*", "data:*", attrRef)
        chkKdls("import lib.*; function f() { update data @* {.x == 0} (); }", "lib:*", "f:*", "data:*", attrRef)
        chkKdls("import lib.*; function f() { update data @* {} ( .x = 0 ); }", "lib:*", "f:*", "data:*", attrRef)
        chkKdls("import lib.*; function f() { update data @* {} ( x = 0 ); }", "lib:*", "f:*", "data:*", attrRef)
        chkKdls("import lib.*; function f() { delete data @* {.x == 0}; }", "lib:*", "f:*", "data:*", attrRef)
    }

    @Test fun testEntityAttrAnon() {
        file("lib/a.rell", "enum color { red }")
        file("lib/b.rell", "entity data { mutable color; }")
        val attrRef = "color:MEM_ENTITY_ATTR_NORMAL_VAR;*;lib/b.rell/entity[data].attr[color]"
        chkFileKdls("lib/b.rell", "data:*", "color:DEF_ENUM;entity[data].attr[color];lib/a.rell/enum[color]")
        chkKdls("import lib.*; function f(d: data) = d.color;", "lib:*", "f:*", "d:*", "data:*", "d:*", attrRef)
        chkKdls("import lib.*; function f() = data @* {} (.color);", "lib:*", "f:*", "data:*", attrRef)
    }

    @Test fun testEntityAttrRowid() {
        file("lib.rell", "module; entity data { mutable x: integer; }")
        chkFileKdls("lib.rell", "data:*", "x:MEM_ENTITY_ATTR_NORMAL_VAR;entity[data].attr[x];-", "integer:*")
        chkKdls("import lib.*; function f(d: data) = d.rowid;", "lib:*", "f:*", "d:*", "data:*", "d:*", "rowid:MEM_ENTITY_ATTR_ROWID;-;-")
        chkKdls("import lib.*; function f() = data @* {} (.rowid);", "lib:*", "f:*", "data:*", "rowid:MEM_ENTITY_ATTR_ROWID;*;-")
    }

    @Test fun testEntityAttrKeyIndex() {
        chkEntityAttrKeyIndex("entity data { x: integer; {KW} x; }",
            "data:*;*;-",
            "x:MEM_ENTITY_ATTR_{KW};entity[data].attr[x];-",
            "integer:DEF_TYPE;-;-",
            "x:MEM_ENTITY_ATTR_{KW};-;main.rell/entity[data].attr[x]",
        )

        chkEntityAttrKeyIndex("entity data { {KW} x; x: integer; }",
            "data:*;*;-",
            "x:MEM_ENTITY_ATTR_{KW};-;main.rell/entity[data].attr[x]",
            "x:MEM_ENTITY_ATTR_{KW};entity[data].attr[x];-",
            "integer:DEF_TYPE;-;-",
        )

        chkEntityAttrKeyIndex("entity data { {KW} x: integer; }",
            "data:*;*;-",
            "x:MEM_ENTITY_ATTR_{KW};entity[data].attr[x];-",
            "integer:DEF_TYPE;-;-",
        )

        chkKdls("entity data { x: integer; key x; index x; }",
            "data:*;*;-",
            "x:MEM_ENTITY_ATTR_KEY;entity[data].attr[x];-",
            "integer:DEF_TYPE;-;-",
            "x:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[x]",
            "x:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[x]",
        )

        chkKdls("entity data { key x; index x; x: integer; }",
            "data:*;*;-",
            "x:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[x]",
            "x:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[x]",
            "x:MEM_ENTITY_ATTR_KEY;entity[data].attr[x];-",
            "integer:DEF_TYPE;-;-",
        )
    }

    private fun chkEntityAttrKeyIndex(code: String, vararg expected: String) {
        chkKdls(code.replace("{KW}", "key"), *expected.map { it.replace("{KW}", "KEY") }.toTypedArray())
        chkKdls(code.replace("{KW}", "index"), *expected.map { it.replace("{KW}", "INDEX") }.toTypedArray())
    }

    @Test fun testEntityAttrKeyIndexMulti() {
        chkKdls("entity data { key part: integer, id: text; value: integer; index id, value; }",
            "data:DEF_ENTITY;entity[data];-",
            "part:MEM_ENTITY_ATTR_KEY;entity[data].attr[part];-",
            "integer:*",
            "id:MEM_ENTITY_ATTR_KEY;entity[data].attr[id];-",
            "text:*",
            "value:MEM_ENTITY_ATTR_INDEX;entity[data].attr[value];-",
            "integer:*",
            "id:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[id]",
            "value:MEM_ENTITY_ATTR_INDEX;-;main.rell/entity[data].attr[value]",
        )
    }

    @Test fun testEntityBlockTransaction() {
        file("lib.rell", "@external module; namespace ns { entity block; entity transaction; }")
        chkFileKdls("lib.rell", "external:*", "ns:*", "block:DEF_ENTITY;entity[ns.block];-", "transaction:DEF_ENTITY;entity[ns.transaction];-")

        val blockRef = "block:DEF_ENTITY;-;lib.rell/entity[ns.block]"
        val txRef = "transaction:DEF_ENTITY;-;lib.rell/entity[ns.transaction]"
        chkKdls("import lib; struct s { b: lib.ns.block; }", "lib:*", "s:*", "b:*", "lib:*", "ns:*", blockRef)
        chkKdls("import lib; struct s { t: lib.ns.transaction; }", "lib:*", "s:*", "t:*", "lib:*", "ns:*", txRef)
        chkKdls("import lib.*; struct s { b: ns.block; }", "lib:*", "s:*", "b:*", "ns:*", blockRef)
        chkKdls("import lib.{ns}; struct s { b: ns.block; }", "lib:*", "ns:*", "s:*", "b:*", "ns:*", blockRef)
    }

    @Test fun testEnum() {
        file("lib.rell", "module; enum color { red, green, blue }")
        chkFileKdls("lib.rell", "color:DEF_ENUM;enum[color];-", "red:*", "green:*", "blue:*")

        val colorRef = "color:DEF_ENUM;-;lib.rell/enum[color]"
        chkKdls("import lib.*; val k: color? = null;", "lib:*", "k:*", colorRef)
        chkKdls("import lib; val k: lib.color? = null;", "lib:*", "k:*", "lib:*", colorRef)
        chkKdls("import lib.{color}; val k: color? = null;", "lib:*", colorRef, "k:*", colorRef)
    }

    @Test fun testEnumValue() {
        file("lib.rell", "module; enum color { red, green, blue }")

        chkFileKdls("lib.rell",
            "color:*",
            "red:MEM_ENUM_VALUE;enum[color].value[red];-",
            "green:MEM_ENUM_VALUE;enum[color].value[green];-",
            "blue:MEM_ENUM_VALUE;enum[color].value[blue];-"
        )

        chkKdls("import lib.*; query q() = color.red;", "lib:*", "q:*", "color:*", "red:MEM_ENUM_VALUE;-;lib.rell/enum[color].value[red]")

        chkKdls("import lib.*; query q(c: color) = when (c) { red -> 1; green -> 2; else -> 0 };",
            "lib:*", "q:*", "c:*", "color:*", "c:*",
            "red:MEM_ENUM_VALUE;-;lib.rell/enum[color].value[red]",
            "green:MEM_ENUM_VALUE;-;lib.rell/enum[color].value[green]"
        )
    }

    @Test fun testFunction() {
        file("lib.rell", "module; function f() = 0;")
        chkFileKdls("lib.rell", "f:DEF_FUNCTION;function[f];-")
        val fnRef = "f:DEF_FUNCTION;-;lib.rell/function[f]"
        chkKdls("import lib.*; query q() = f();", "lib:*", "q:*", fnRef)
        chkKdls("import lib; query q() = lib.f();", "lib:*", "q:*", "lib:*", fnRef)
        chkKdls("import lib.{f}; query q() = f();", "lib:*", fnRef, "q:*", fnRef)
    }

    @Test fun testFunctionAbstractOverride() {
        file("lib.rell", "abstract module; namespace ns { abstract function f(): integer = 123; }")
        chkFileKdls("lib.rell", "ns:*", "f:DEF_FUNCTION_ABSTRACT;function[ns.f];-", "integer:*;-;*")

        val nsRef = "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]"
        val fnRef = "f:DEF_FUNCTION_ABSTRACT;-;lib.rell/function[ns.f]"
        chkKdls("import lib.{ns.*}; query q() = f();", "lib:*", "ns:*", "q:*", fnRef)
        chkKdls("import lib.{ns.*}; override function f() = 456;", "lib:*", nsRef, fnRef)
        chkKdls("import lib; override function lib.ns.f() = 456;", "lib:*", "lib:*", nsRef, fnRef)
        chkKdls("import lib.*; override function ns.f() = 456;", "lib:*", nsRef, fnRef)
        chkKdls("import lib.{ns}; override function ns.f() = 456;", "lib:*", nsRef, nsRef, fnRef)
    }

    @Test fun testFunctionExtendable() {
        file("lib.rell", "module; namespace ns { @extendable function f(){} }")
        chkFileKdls("lib.rell", "ns:*", "extendable:*", "f:DEF_FUNCTION_EXTENDABLE;function[ns.f];-")

        val fnRef = "f:DEF_FUNCTION_EXTENDABLE;-;lib.rell/function[ns.f]"
        chkKdls("import lib.{ns.*}; function g() { f(); }", "lib:*", "ns:*", "g:*", fnRef)
        chkKdls("import lib.{ns.*}; @extend(f) function g() {}", "lib:*", "ns:*", "extend:*", fnRef, "g:*")
        chkKdls("import lib; @extend(lib.ns.f) function g() {}", "lib:*", "extend:*", "lib:*", "ns:*", fnRef, "g:*")
        chkKdls("import lib.{ns.*}; @extend(f) function() {}", "lib:*", "ns:*", "extend:*", fnRef)
        chkKdls("import lib; @extend(lib.ns.f) function() {}", "lib:*", "extend:*", "lib:*", "ns:*", fnRef)
        chkKdls("import lib.*; @extend(ns.f) function() {}", "lib:*", "extend:*", "ns:*", fnRef)
        chkKdls("import lib.{ns}; @extend(ns.f) function() {}", "lib:*", "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]", "extend:*", "ns:*", fnRef)
    }

    @Test fun testFunctionParamNamed() {
        file("lib.rell", "module; namespace ns { function f(x: integer) { return x*2; } }")
        val xId = "function[ns.f].param[x]"
        chkFileKdls("lib.rell", "ns:*;*;-", "f:*;*;-", "x:LOC_PARAMETER;$xId;-", "integer:*;-;*", "x:LOC_PARAMETER;-;lib.rell/$xId")
        chkKdls("import lib.{ns.*}; query q() = f(x = 123);", "lib:*", "ns:*", "q:*", "f:*", "x:EXPR_CALL_ARG;-;lib.rell/$xId")
    }

    @Test fun testFunctionParamAnonSimple() {
        file("lib/a.rell", "enum color { red }")
        file("lib/b.rell", "function f(color) { return color; }")
        chkFileKdls("lib/b.rell",
            "f:*;*;-",
            "color:DEF_ENUM;function[f].param[color];lib/a.rell/enum[color]",
            "color:LOC_PARAMETER;-;lib/b.rell/function[f].param[color]"
        )
        chkKdls("import lib.*; query q() = f(color = color.red);",
            "lib:*", "q:*", "f:*",
            "color:EXPR_CALL_ARG;-;lib/b.rell/function[f].param[color]",
            "color:DEF_ENUM;-;lib/a.rell/enum[color]", "red:*"
        )
    }

    @Test fun testFunctionParamAnonComplex() {
        file("lib/a.rell", "namespace ns { enum color { red } }")
        file("lib/b.rell", "function f(ns.color) { return color; }")
        chkFileKdls("lib/b.rell",
            "f:*;*-", "ns:*;-;*",
            "color:DEF_ENUM;function[f].param[color];lib/a.rell/enum[ns.color]",
            "color:LOC_PARAMETER;-;lib/b.rell/function[f].param[color]"
        )
        chkKdls("import lib.*; query q() = f(color = ns.color.red);",
            "lib:*", "q:*", "f:*",
            "color:EXPR_CALL_ARG;-;lib/b.rell/function[f].param[color]",
            "ns:*",
            "color:DEF_ENUM;-;lib/a.rell/enum[ns.color]", "red:*"
        )
    }

    @Test fun testGlobalConstant() {
        file("lib.rell", "module; val magic = 123;")
        chkFileKdls("lib.rell", "magic:DEF_CONSTANT;constant[magic];-")
        val magicRef = "magic:DEF_CONSTANT;-;lib.rell/constant[magic]"
        chkKdls("import lib.*; val k = magic;", "lib:*", "k:DEF_CONSTANT;constant[k];-", magicRef)
        chkKdls("import lib; val k = lib.magic;", "lib:*", "k:DEF_CONSTANT;constant[k];-", "lib:*", magicRef)
        chkKdls("import lib.{magic}; val k = magic;", "lib:*", magicRef, "k:DEF_CONSTANT;constant[k];-", magicRef)
    }

    @Test fun testMirrorStructEntity() {
        file("lib.rell", "module; entity data { value: integer; }")
        chkFileKdls("lib.rell", "data:DEF_ENTITY;entity[data];-", "value:MEM_ENTITY_ATTR_NORMAL;entity[data].attr[value];-", "integer:*")
        chkMirrorStruct("DEF_ENTITY", "entity", "attr")
    }

    @Test fun testMirrorStructObject() {
        file("lib.rell", "module; object data { mutable value: integer = 123; }")
        chkFileKdls("lib.rell", "data:DEF_OBJECT;object[data];-", "value:MEM_ENTITY_ATTR_NORMAL_VAR;object[data].attr[value];-", "integer:*")
        chkMirrorStruct("DEF_OBJECT", "object", "attr")
    }

    @Test fun testMirrorStructOperation() {
        file("lib.rell", "module; operation data(value: integer) {}")
        chkFileKdls("lib.rell", "data:DEF_OPERATION;operation[data];-", "value:LOC_PARAMETER;operation[data].param[value];-", "integer:*")
        chkMirrorStruct("DEF_OPERATION", "operation", "param")
    }

    private fun chkMirrorStruct(kind: String, defCat: String, attrCat: String) {
        val data = "data:$kind;-;lib.rell/$defCat[data]"
        val attrVal = "value:MEM_STRUCT_ATTR;-;lib.rell/$defCat[data].$attrCat[value]"
        val attrVar = "value:MEM_STRUCT_ATTR_VAR;-;lib.rell/$defCat[data].$attrCat[value]"
        chkKdls("import lib.*; query q() = struct<data>(value = 123);", "lib:*", "q:*", data, attrVal)
        chkKdls("import lib.*; query q(s: struct<data>) = s.value;", "lib:*", "q:*", "s:*", data, "s:*", attrVal)
        chkKdls("import lib.*; query q() = struct<mutable data>(value = 123);", "lib:*", "q:*", data, attrVar)
        chkKdls("import lib.*; query q(s: struct<mutable data>) = s.value;", "lib:*", "q:*", "s:*", data, "s:*", attrVar)
    }

    @Test fun testModuleArgs() {
        file("args.rell", "struct module_args { x: integer; }")

        chkFileKdls("args.rell",
            "module_args:DEF_STRUCT;struct[module_args];-",
            "x:MEM_STRUCT_ATTR;struct[module_args].attr[x];-",
            "integer:*;-;*"
        )

        chkKdls("query q() = chain_context.args;", "q:*;*;-", "chain_context:*;-;*", "args:DEF_CONSTANT;-;args.rell/struct[module_args]")

        chkKdls("query q() = chain_context.args.x;",
            "q:*;*;-",
            "chain_context:*;-;*",
            "args:*;-;*",
            "x:MEM_STRUCT_ATTR;-;args.rell/struct[module_args].attr[x]"
        )
    }

    @Test fun testNamespace() {
        file("lib.rell", "module; namespace ns { val k = 123; }")
        chkFileKdls("lib.rell", "ns:DEF_NAMESPACE;namespace[ns];-", "k:DEF_CONSTANT;constant[ns.k];-")

        val kRef = "k:DEF_CONSTANT;-;lib.rell/constant[ns.k]"
        chkKdls("import lib.*; query q() = ns.k;", "lib:*;-;*", "q:*;*-", "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]", kRef)
        chkKdls("import lib.{ns}; query q() = ns.k;", "lib:*;-;*", "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]", "q:*;*;-", "ns:*;-;*", kRef)
        chkKdls("import lib; query q() = lib.ns.k;", "lib:*;*;*", "q:*;*;-", "lib:*;-;*", "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]", kRef)
    }

    @Test fun testNamespaceQualified() {
        file("lib.rell", "module; namespace a.b.c { val k = 123; }")
        chkFileKdls("lib.rell",
            "a:DEF_NAMESPACE;namespace[a];-",
            "b:DEF_NAMESPACE;namespace[a.b];-",
            "c:DEF_NAMESPACE;namespace[a.b.c];-",
            "k:DEF_CONSTANT;constant[a.b.c.k];-"
        )
        chkKdls("import lib.*; query q() = a.b.c.k;", "lib:*", "q:*;*;-",
            "a:DEF_NAMESPACE;-;lib.rell/namespace[a]",
            "b:DEF_NAMESPACE;-;lib.rell/namespace[a.b]",
            "c:DEF_NAMESPACE;-;lib.rell/namespace[a.b.c]",
            "k:DEF_CONSTANT;-;lib.rell/constant[a.b.c.k]"
        )
    }

    @Test fun testNamespaceNested() {
        file("lib.rell", "module; namespace a { namespace b { namespace c { val k = 123; } } }")
        chkFileKdls("lib.rell",
            "a:DEF_NAMESPACE;namespace[a];-",
            "b:DEF_NAMESPACE;namespace[a.b];-",
            "c:DEF_NAMESPACE;namespace[a.b.c];-",
            "k:DEF_CONSTANT;constant[a.b.c.k];-"
        )
        chkKdls("import lib.*; query q() = a.b.c.k;", "lib:*", "q:*;*;-",
            "a:DEF_NAMESPACE;-;lib.rell/namespace[a]",
            "b:DEF_NAMESPACE;-;lib.rell/namespace[a.b]",
            "c:DEF_NAMESPACE;-;lib.rell/namespace[a.b.c]",
            "k:DEF_CONSTANT;-;lib.rell/constant[a.b.c.k]"
        )
    }

    @Test fun testNamespaceMultiOneFile() {
        file("lib.rell", "module; namespace a { val x = 123; } namespace a { val y = 456; }")

        chkFileKdls("lib.rell",
            "a:DEF_NAMESPACE;namespace[a];lib.rell/namespace[a:1]",
            "x:DEF_CONSTANT;constant[a.x];-",
            "a:DEF_NAMESPACE;namespace[a:1];lib.rell/namespace[a]",
            "y:DEF_CONSTANT;constant[a.y];-"
        )

        val aRef = "a:DEF_NAMESPACE;-;lib.rell/namespace[a]"
        chkKdls("import lib.*; query q() = a.x;", "lib:*", "q:*;*;-", aRef, "x:DEF_CONSTANT;-;lib.rell/constant[a.x]")
        chkKdls("import lib.*; query q() = a.y;", "lib:*", "q:*;*;-", aRef, "y:DEF_CONSTANT;-;lib.rell/constant[a.y]")
    }

    @Test fun testNamespaceMultiManyFiles() {
        file("lib/f1.rell", "namespace a { val x = 123; }")
        file("lib/f2.rell", "namespace a { val y = 456; }")
        chkFileKdls("lib/f1.rell", "a:DEF_NAMESPACE;namespace[a];lib/f2.rell/namespace[a]", "x:DEF_CONSTANT;constant[a.x];-")
        chkFileKdls("lib/f2.rell", "a:DEF_NAMESPACE;namespace[a];lib/f1.rell/namespace[a]", "y:DEF_CONSTANT;constant[a.y];-")

        val aRef = "a:DEF_NAMESPACE;-;lib/f1.rell/namespace[a]"
        chkKdls("import lib.*; query q() = a.x;", "lib:*", "q:*;*;-", aRef, "x:DEF_CONSTANT;-;lib/f1.rell/constant[a.x]")
        chkKdls("import lib.*; query q() = a.y;", "lib:*", "q:*;*;-", aRef, "y:DEF_CONSTANT;-;lib/f2.rell/constant[a.y]")
    }

    @Test fun testNamespaceMultiLocalLink() {
        file("lib/f1.rell", "namespace a { val x = 123; } val p = a.y;")
        file("lib/f2.rell", "namespace a { val y = 456; } val q = a.x;")

        chkFileKdls("lib/f1.rell",
            "a:DEF_NAMESPACE;namespace[a];lib/f2.rell/namespace[a]",
            "x:DEF_CONSTANT;constant[a.x];-",
            "p:DEF_CONSTANT;constant[p];-",
            "a:DEF_NAMESPACE;-;lib/f1.rell/namespace[a]",
            "y:DEF_CONSTANT;-;lib/f2.rell/constant[a.y]"
        )

        chkFileKdls("lib/f2.rell",
            "a:DEF_NAMESPACE;namespace[a];lib/f1.rell/namespace[a]",
            "y:DEF_CONSTANT;constant[a.y];-",
            "q:*;*;-",
            "a:DEF_NAMESPACE;-;lib/f1.rell/namespace[a]",
            "x:DEF_CONSTANT;-;lib/f1.rell/constant[a.x]"
        )
    }

    @Test fun testNamespaceMultiNextLinkOneFile() {
        chkKdls("namespace a {}", "a:DEF_NAMESPACE;namespace[a];-")
        chkKdls("namespace a {} namespace a {}",
            "a:DEF_NAMESPACE;namespace[a];main.rell/namespace[a:1]",
            "a:DEF_NAMESPACE;namespace[a:1];main.rell/namespace[a]"
        )
        chkKdls("namespace a {} namespace a {} namespace a {}",
            "a:DEF_NAMESPACE;namespace[a];main.rell/namespace[a:1]",
            "a:DEF_NAMESPACE;namespace[a:1];main.rell/namespace[a:2]",
            "a:DEF_NAMESPACE;namespace[a:2];main.rell/namespace[a]"
        )
    }

    @Test fun testNamespaceMultiNextLinkManyFiles() {
        file("lib/f1.rell", "namespace a {}")
        file("lib/f2.rell", "namespace a {}")
        file("lib/f3.rell", "namespace a {}")
        chkFileKdls("lib/f1.rell", "a:DEF_NAMESPACE;namespace[a];lib/f2.rell/namespace[a]")
        chkFileKdls("lib/f2.rell", "a:DEF_NAMESPACE;namespace[a];lib/f3.rell/namespace[a]")
        chkFileKdls("lib/f3.rell", "a:DEF_NAMESPACE;namespace[a];lib/f1.rell/namespace[a]")
    }

    @Test fun testNamespaceMultiNextLinkManyFilesQualified() {
        file("lib/f1.rell", "namespace a {}")
        file("lib/f2.rell", "namespace a { namespace b {} }")
        file("lib/f3.rell", "namespace a.b {}")
        file("lib/f4.rell", "namespace a {}")
        file("lib/f5.rell", "namespace a { namespace b {} }")

        val aId = "namespace[a]"
        chkFileKdls("lib/f1.rell", "a:DEF_NAMESPACE;$aId;lib/f2.rell/$aId")
        chkFileKdls("lib/f2.rell", "a:DEF_NAMESPACE;$aId;lib/f3.rell/$aId", "b:DEF_NAMESPACE;namespace[a.b];lib/f3.rell/namespace[a.b]")
        chkFileKdls("lib/f3.rell", "a:DEF_NAMESPACE;$aId;lib/f4.rell/$aId", "b:DEF_NAMESPACE;namespace[a.b];lib/f5.rell/namespace[a.b]")
        chkFileKdls("lib/f4.rell", "a:DEF_NAMESPACE;$aId;lib/f5.rell/$aId")
        chkFileKdls("lib/f5.rell", "a:DEF_NAMESPACE;$aId;lib/f1.rell/$aId", "b:DEF_NAMESPACE;namespace[a.b];lib/f2.rell/namespace[a.b]")
    }

    @Test fun testObject() {
        file("lib.rell", "module; object state { mutable x: integer = 123; }")
        chkFileKdls("lib.rell", "state:DEF_OBJECT;object[state];-", "x:*;*;-", "integer:*;-;*")

        val stateRef = "state:DEF_OBJECT;-;lib.rell/object[state]"
        chkKdls("import lib.*; query q() = state.x;", "lib:*", "q:*;*;-", stateRef, "x:*;-;*")
        chkKdls("import lib.*; query q(): struct<state>? = null;", "lib:*", "q:*;*;-", stateRef)
        chkKdls("import lib.{state}; query q(): struct<state>? = null;", "lib:*", stateRef, "q:*;*;-", stateRef)
        chkKdls("import lib; query q(): struct<lib.state>? = null;", "lib:*", "q:*;*;-", "lib:*", stateRef)
    }

    @Test fun testObjectAttr() {
        file("lib.rell", "module; object state { mutable x: integer = 123; }")
        chkFileKdls("lib.rell", "state:*", "x:MEM_ENTITY_ATTR_NORMAL_VAR;object[state].attr[x];-", "integer:*;-;*")
        chkKdls("import lib.*; query q() = state.x;",
            "lib:*",
            "q:*;*;-",
            "state:DEF_OBJECT;-;lib.rell/object[state]",
            "x:MEM_ENTITY_ATTR_NORMAL_VAR;-;lib.rell/object[state].attr[x]"
        )
    }

    @Test fun testOperation() {
        tst.testLib = true
        file("lib.rell", "module; operation op(x: integer) {}")
        chkFileKdls("lib.rell", "op:DEF_OPERATION;operation[op];-", "x:*", "integer:*;-;*")

        val opRef = "op:DEF_OPERATION;-;lib.rell/operation[op]"
        chkKdls("import lib.*; query q() = op(123);", "lib:*", "q:*;*;-", opRef)
        chkKdls("import lib.*; query q(): struct<op>? = null;", "lib:*", "q:*;*;-", opRef)
        chkKdls("import lib.{op}; query q(): struct<op>? = null;", "lib:*", opRef, "q:*;*;-", opRef)
        chkKdls("import lib; query q(): struct<lib.op>? = null;", "lib:*", "q:*;*;-", "lib:*", opRef)
    }

    @Test fun testOperationParams() {
        tst.testLib = true
        file("def.rell", "operation op(x: integer) { if (x>0) {} }")
        chkFileKdls("def.rell",
            "op:DEF_OPERATION;operation[op];-",
            "x:LOC_PARAMETER;operation[op].param[x];-",
            "integer:*;-;*",
            "x:LOC_PARAMETER;-;def.rell/operation[op].param[x]"
        )
        chkKdls("query q() = op(x = 123);",
            "q:*;*;-",
            "op:DEF_OPERATION;-;def.rell/operation[op]",
            "x:EXPR_CALL_ARG;-;def.rell/operation[op].param[x]"
        )
    }

    @Test fun testQuery() {
        file("lib.rell", "module; query ask() = 0;")
        chkFileKdls("lib.rell", "ask:DEF_QUERY;query[ask];-")

        val askRef = "ask:DEF_QUERY;-;lib.rell/query[ask]"
        chkKdls("import lib.*; query q() = ask();", "lib:*", "q:*;*;-", askRef)
        chkKdls("import lib.{ask}; query q() = ask();", "lib:*", askRef, "q:*;*;-", askRef)
        chkKdls("import lib; query q() = lib.ask();", "lib:*", "q:*;*;-", "lib:*", askRef)
    }

    @Test fun testQueryParams() {
        file("def.rell", "query ask(x: integer) = x + 1;")
        chkFileKdls("def.rell",
            "ask:DEF_QUERY;query[ask];-",
            "x:LOC_PARAMETER;query[ask].param[x];-",
            "integer:*;-;*",
            "x:LOC_PARAMETER;-;def.rell/query[ask].param[x]"
        )
        chkKdls("query q() = ask(x = 123);",
            "q:*;*;-",
            "ask:DEF_QUERY;-;def.rell/query[ask]",
            "x:EXPR_CALL_ARG;-;def.rell/query[ask].param[x]"
        )
    }

    @Test fun testStruct() {
        file("lib.rell", "module; struct rec {}")
        chkFileKdls("lib.rell", "rec:DEF_STRUCT;struct[rec];-")

        val recRef = "rec:DEF_STRUCT;-;lib.rell/struct[rec]"
        chkKdls("import lib.*; query q() = rec();", "lib:*", "q:*;*;-", recRef)
        chkKdls("import lib.*; query q(r: rec) = 0;", "lib:*", "q:*;*;-", "r:*;*;-", recRef)
        chkKdls("import lib.{rec}; query q() = rec();", "lib:*", "rec:DEF_STRUCT;-;lib.rell/struct[rec]", "q:*;*;-", recRef)
        chkKdls("import lib; query q() = lib.rec();", "lib:*", "q:*;*;-", "lib:*", recRef)
    }

    @Test fun testStructAttrNamed() {
        file("lib.rell", "module; struct rec { mutable x: integer; }")
        chkFileKdls("lib.rell", "rec:*", "x:MEM_STRUCT_ATTR_VAR;struct[rec].attr[x];-", "integer:*;-;*")
        val recRef = "rec:DEF_STRUCT;-;lib.rell/struct[rec]"
        val attrRef = "x:MEM_STRUCT_ATTR_VAR;-;lib.rell/struct[rec].attr[x]"
        chkKdls("import lib.*; function f() = rec(x = 123);", "lib:*", "f:*;*;-", recRef, attrRef)
        chkKdls("import lib.*; function f(r: rec) = r.x;", "lib:*", "f:*;*;-", "r:*;*;-", recRef, "r:*", attrRef)
        chkKdls("import lib.*; function f(r: rec) { r.x = 0; }", "lib:*", "f:*;*;-", "r:*;*;-", recRef, "r:*", attrRef)
    }

    @Test fun testStructAttrAnonSimple() {
        file("lib/a.rell", "enum color { red }")
        file("lib/b.rell", "struct rec { mutable color; }")
        chkFileKdls("lib/b.rell", "rec:*", "color:DEF_ENUM;struct[rec].attr[color];lib/a.rell/enum[color]")

        val structRef = "rec:DEF_STRUCT;-;lib/b.rell/struct[rec]"
        val enumRef = "color:DEF_ENUM;-;lib/a.rell/enum[color]"
        val attrRef = "color:MEM_STRUCT_ATTR_VAR;-;lib/b.rell/struct[rec].attr[color]"
        chkKdls("import lib.*; function f() = rec(color = color.red);", "lib:*", "f:*;*;-", "rec:*", attrRef, enumRef, "red:*")
        chkKdls("import lib.*; function f(r: rec) = r.color;", "lib:*", "f:*;*;-", "r:*;*;-", structRef, "r:*", attrRef)
        chkKdls("import lib.*; function f(r: rec) { r.color = color.red; }",
            "lib:*", "f:*;*;-", "r:*;*;-", structRef, "r:*", attrRef, enumRef, "red:*")
    }

    @Test fun testStructAttrAnonComplex() {
        file("lib/a.rell", "namespace ns { enum color { red } }")
        file("lib/b.rell", "struct rec { mutable ns.color; }")
        chkFileKdls("lib/b.rell", "rec:*", "ns:*;-;*", "color:DEF_ENUM;struct[rec].attr[color];lib/a.rell/enum[ns.color]")

        val structRef = "rec:DEF_STRUCT;-;lib/b.rell/struct[rec]"
        val enumRef = "color:DEF_ENUM;-;lib/a.rell/enum[ns.color]"
        val attrRef = "color:MEM_STRUCT_ATTR_VAR;-;lib/b.rell/struct[rec].attr[color]"
        chkKdls("import lib.*; function f() = rec(color = ns.color.red);", "lib:*", "f:*;*;-", "rec:*", attrRef, "ns:*", enumRef, "red:*")
        chkKdls("import lib.*; function f(r: rec) = r.color;", "lib:*", "f:*;*;-", "r:*;*;-", structRef, "r:*", attrRef)
        chkKdls("import lib.*; function f(r: rec) { r.color = ns.color.red; }",
            "lib:*", "f:*;*;-", "r:*;*;-", structRef, "r:*", attrRef, "ns:*", enumRef, "red:*")
    }

    @Test fun testStructEntityPath() {
        file("lib.rell", """
            module;
            struct s1 { s: s2; }
            struct s2 { u: user; }
            entity user { c: company; }
            entity company { name; }
        """)
        file("mid.rell", "module; import lib.*; function f(): s1 = g()!!; function g(): s1? = null;")

        chkFileKdls("lib.rell",
            "s1:DEF_STRUCT;struct[s1];-", "s:MEM_STRUCT_ATTR;struct[s1].attr[s];-", "s2:DEF_STRUCT;-;lib.rell/struct[s2]",
            "s2:DEF_STRUCT;struct[s2];-", "u:MEM_STRUCT_ATTR;struct[s2].attr[u];-", "user:DEF_ENTITY;-;lib.rell/entity[user]",
            "user:DEF_ENTITY;entity[user];-", "c:MEM_ENTITY_ATTR_NORMAL;entity[user].attr[c];-", "company:DEF_ENTITY;-;lib.rell/entity[company]",
            "company:DEF_ENTITY;entity[company];-", "name:DEF_TYPE;entity[company].attr[name];-",
        )

        chkKdls("import mid.*; query q() = f().s.u.c.name;",
            "mid:*", "q:*",
            "f:DEF_FUNCTION;-;mid.rell/function[f]",
            "s:MEM_STRUCT_ATTR;-;lib.rell/struct[s1].attr[s]",
            "u:MEM_STRUCT_ATTR;-;lib.rell/struct[s2].attr[u]",
            "c:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/entity[user].attr[c]",
            "name:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/entity[company].attr[name]",
        )
    }

    @Test fun testTupleAttrAtWhatExplicitCol() {
        file("rec.rell", "struct rec { x: integer; y: integer; } function data() = list<rec>();")
        file("def.rell", "function f() { val r = data() @{} (a = .x, b = .y, @omit c = .x); return r; }")

        chkFileKdls("def.rell",
            "f:*;*;-",
            "r:*;*;-",
            "data:DEF_FUNCTION;-;rec.rell/function[data]",
            "a:MEM_TUPLE_ATTR;function[f].tuple[_0].attr[a];-",
            "x:MEM_STRUCT_ATTR;-;rec.rell/struct[rec].attr[x]",
            "b:MEM_TUPLE_ATTR;function[f].tuple[_0].attr[b];-",
            "y:MEM_STRUCT_ATTR;-;rec.rell/struct[rec].attr[y]",
            "omit:MOD_ANNOTATION;-;-",
            "c:MEM_TUPLE_ATTR;-;-",
            "x:MEM_STRUCT_ATTR;-;rec.rell/struct[rec].attr[x]",
            "r:*;-;*"
        )

        chkKdls("query q() = f().a;", "q:*;*;-", "f:*;-;*", "a:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_0].attr[a]")
        chkKdls("query q() = f().b;", "q:*;*;-", "f:*;-;*", "b:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_0].attr[b]")
    }

    @Test fun testTupleAttrAtWhatExplicitDb() {
        file("rec.rell", "entity data { x: integer; y: integer; }")
        file("def.rell", "function f() { val r = data @{} (a = .x, b = .y, @omit c = .x); return r; }")

        chkFileKdls("def.rell",
            "f:*;*;-",
            "r:*;*;-",
            "data:DEF_ENTITY;-;rec.rell/entity[data]",
            "a:MEM_TUPLE_ATTR;function[f].tuple[_0].attr[a];-",
            "x:MEM_ENTITY_ATTR_NORMAL;-;rec.rell/entity[data].attr[x]",
            "b:MEM_TUPLE_ATTR;function[f].tuple[_0].attr[b];-",
            "y:MEM_ENTITY_ATTR_NORMAL;-;rec.rell/entity[data].attr[y]",
            "omit:MOD_ANNOTATION;-;-",
            "c:MEM_TUPLE_ATTR;-;-",
            "x:MEM_ENTITY_ATTR_NORMAL;-;rec.rell/entity[data].attr[x]",
            "r:*;-;*"
        )

        chkKdls("query q() = f().a;", "q:*;*;-", "f:*;-;*", "a:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_0].attr[a]")
        chkKdls("query q() = f().b;", "q:*;*;-", "f:*;-;*", "b:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_0].attr[b]")
    }

    @Test fun testTupleAttrAtWhatImplicitCol() {
        file("rec.rell", "struct rec { x: integer; y: integer; } function data() = list<rec>();")
        file("def.rell", "function f() { val r = data() @{} (.x, $.y, @omit .x); return r; }")

        chkFileKdls("def.rell",
            "f:*;*;-",
            "r:*;*;-",
            "data:DEF_FUNCTION;-;rec.rell/function[data]",
            "x:MEM_STRUCT_ATTR;function[f].tuple[_0].attr[x];rec.rell/struct[rec].attr[x]",
            "$:LOC_AT_ALIAS;-;local[data:0]",
            "y:MEM_STRUCT_ATTR;function[f].tuple[_0].attr[y];rec.rell/struct[rec].attr[y]",
            "omit:MOD_ANNOTATION;-;-",
            "x:MEM_STRUCT_ATTR;-;rec.rell/struct[rec].attr[x]",
            "r:*;-;*"
        )

        chkKdls("query q() = f().x;", "q:*;*;-", "f:*;-;*", "x:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_0].attr[x]")
        chkKdls("query q() = f().y;", "q:*;*;-", "f:*;-;*", "y:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_0].attr[y]")
    }

    @Test fun testTupleAttrAtWhatImplicitDb() {
        file("rec.rell", "entity data { x: integer; y: integer; }")
        file("def.rell", "function f() { val r = data @{} (.x, $.y, @omit .x); return r; }")

        chkFileKdls("def.rell",
            "f:*;*;-",
            "r:*;*;-",
            "data:DEF_ENTITY;-;rec.rell/entity[data]",
            "x:MEM_ENTITY_ATTR_NORMAL;function[f].tuple[_0].attr[x];rec.rell/entity[data].attr[x]",
            "$:LOC_AT_ALIAS;-;local[data:0]",
            "y:MEM_ENTITY_ATTR_NORMAL;function[f].tuple[_0].attr[y];rec.rell/entity[data].attr[y]",
            "omit:MOD_ANNOTATION;-;-",
            "x:MEM_ENTITY_ATTR_NORMAL;-;rec.rell/entity[data].attr[x]",
            "r:*;-;*"
        )

        chkKdls("query q() = f().x;", "q:*;*;-", "f:*;-;*", "x:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_0].attr[x]")
        chkKdls("query q() = f().y;", "q:*;*;-", "f:*;-;*", "y:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_0].attr[y]")
    }

    @Test fun testTupleAttrExpr() {
        val attrs = arrayOf(
            "x:MEM_TUPLE_ATTR;query[q].tuple[_0].attr[x];-",
            "y:MEM_TUPLE_ATTR;query[q].tuple[_1].attr[y];-",
            "z:MEM_TUPLE_ATTR;query[q].tuple[_1].attr[z];-"
        )

        chkKdls("query q() = (x=123, (y=456, z=789)).x;", "q:*;*;-", *attrs, "x:MEM_TUPLE_ATTR;-;main.rell/query[q].tuple[_0].attr[x]")
        chkKdls("query q() = (x=123, (y=456, z=789))[1].y;", "q:*;*;-", *attrs, "y:MEM_TUPLE_ATTR;-;main.rell/query[q].tuple[_1].attr[y]")
        chkKdls("query q() = (x=123, (y=456, z=789))[1].z;", "q:*;*;-", *attrs, "z:MEM_TUPLE_ATTR;-;main.rell/query[q].tuple[_1].attr[z]")
    }

    @Test fun testTupleAttrFunctionResultValueShort() {
        file("def.rell", "function f() = (x = 123, (y = 456, z = 789));")
        chkFileKdls("def.rell",
            "f:*;*;-",
            "x:MEM_TUPLE_ATTR;function[f].tuple[_0].attr[x];-",
            "y:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[y];-",
            "z:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[z];-"
        )
        chkTupleAttrFunction()
    }

    @Test fun testTupleAttrFunctionResultValueFull() {
        file("def.rell", "function f() { return (x = 123, (y = 456, z = 789)); }")
        chkFileKdls("def.rell",
            "f:*;*;-",
            "x:MEM_TUPLE_ATTR;function[f].tuple[_0].attr[x];-",
            "y:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[y];-",
            "z:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[z];-"
        )
        chkTupleAttrFunction()
    }

    @Test fun testTupleAttrFunctionResultType() {
        file("def.rell", "function f(): (x: integer, (y: integer, z: integer)) { return (x = 123, (y = 456, z = 789)); }")
        chkFileKdls("def.rell",
            "f:*;*;-",
            "x:MEM_TUPLE_ATTR;function[f].tuple[_0].attr[x];-", "integer:*;-;*",
            "y:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[y];-", "integer:*;-;*",
            "z:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[z];-", "integer:*;-;*",
            "x:MEM_TUPLE_ATTR;function[f].tuple[_2].attr[x];-",
            "y:MEM_TUPLE_ATTR;function[f].tuple[_3].attr[y];-",
            "z:MEM_TUPLE_ATTR;function[f].tuple[_3].attr[z];-"
        )
        chkTupleAttrFunction()
    }

    @Test fun testTupleAttrFunctionVal() {
        file("def.rell", "function f() { val r = (x = 123, (y = 456, z = 789)); return r; }")
        chkFileKdls("def.rell",
            "f:*;*;-",
            "r:*;*;-",
            "x:MEM_TUPLE_ATTR;function[f].tuple[_0].attr[x];-",
            "y:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[y];-",
            "z:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[z];-",
            "r:*;-;*"
        )
        chkTupleAttrFunction()
    }

    @Test fun testTupleAttrFunctionValAssign() {
        file("def.rell", "function f() { val r: (x: integer, (y: integer, z: integer)); r = (x = 123, (y = 456, z = 789)); return r; }")
        chkFileKdls("def.rell",
            "f:*;*;-",
            "r:*;*;-",
            "x:MEM_TUPLE_ATTR;function[f].tuple[_0].attr[x];-", "integer:*;-;*",
            "y:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[y];-", "integer:*;-;*",
            "z:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[z];-", "integer:*;-;*",
            "r:*;-;*",
            "x:MEM_TUPLE_ATTR;function[f].tuple[_2].attr[x];-",
            "y:MEM_TUPLE_ATTR;function[f].tuple[_3].attr[y];-",
            "z:MEM_TUPLE_ATTR;function[f].tuple[_3].attr[z];-",
            "r:*;-;*"
        )
        chkTupleAttrFunction()
    }

    @Test fun testTupleAttrFunctionParam() {
        file("def.rell", "function f(a: (x: integer, (y: integer, z: integer))? = null) = a!!;")
        chkFileKdls("def.rell",
            "f:*;*;-",
            "a:*;*;-",
            "x:MEM_TUPLE_ATTR;function[f].tuple[_0].attr[x];-", "integer:*;-;*",
            "y:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[y];-", "integer:*;-;*",
            "z:MEM_TUPLE_ATTR;function[f].tuple[_1].attr[z];-", "integer:*;-;*",
            "a:*;-;*"
        )
        chkTupleAttrFunction()
    }

    private fun chkTupleAttrFunction() {
        chkKdls("query q() = f().x;", "q:*;*;-", "f:*;-;*", "x:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_0].attr[x]")
        chkKdls("query q() = f()[1].y;", "q:*;*;-", "f:*;-;*", "y:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_1].attr[y]")
        chkKdls("query q() = f()[1].z;", "q:*;*;-", "f:*;-;*", "z:MEM_TUPLE_ATTR;-;def.rell/function[f].tuple[_1].attr[z]")
    }

    @Test fun testTupleAttrStructAttr() {
        file("def.rell", "struct s { a: (x: integer, (y: integer, z: integer))? = null; }")

        chkFileKdls("def.rell",
            "s:DEF_STRUCT;struct[s];-",
            "a:MEM_STRUCT_ATTR;struct[s].attr[a];-",
            "x:MEM_TUPLE_ATTR;struct[s].tuple[_0].attr[x];-", "integer:*;-;*",
            "y:MEM_TUPLE_ATTR;struct[s].tuple[_1].attr[y];-", "integer:*;-;*",
            "z:MEM_TUPLE_ATTR;struct[s].tuple[_1].attr[z];-", "integer:*;-;*"
        )

        val sRef = "s:DEF_STRUCT;-;def.rell/struct[s]"
        val aRef = "a:MEM_STRUCT_ATTR;-;def.rell/struct[s].attr[a]"
        chkKdls("query q() = s().a!!.x;", "q:*;*;-", sRef, aRef, "x:MEM_TUPLE_ATTR;-;def.rell/struct[s].tuple[_0].attr[x]")
        chkKdls("query q() = s().a!![1].y;", "q:*;*;-", sRef, aRef, "y:MEM_TUPLE_ATTR;-;def.rell/struct[s].tuple[_1].attr[y]")
        chkKdls("query q() = s().a!![1].z;", "q:*;*;-", sRef, aRef, "z:MEM_TUPLE_ATTR;-;def.rell/struct[s].tuple[_1].attr[z]")
    }

    @Test fun testTupleAttrStructAttrValue() {
        file("def.rell", "struct s { a: (x: integer, (y: integer, z: integer)) = (x = 123, (y = 456, z = 789)); }")

        chkFileKdls("def.rell",
            "s:DEF_STRUCT;struct[s];-",
            "a:MEM_STRUCT_ATTR;struct[s].attr[a];-",
            "x:MEM_TUPLE_ATTR;struct[s].tuple[_0].attr[x];-", "integer:*;-;*",
            "y:MEM_TUPLE_ATTR;struct[s].tuple[_1].attr[y];-", "integer:*;-;*",
            "z:MEM_TUPLE_ATTR;struct[s].tuple[_1].attr[z];-", "integer:*;-;*",
            "x:MEM_TUPLE_ATTR;struct[s].tuple[_2].attr[x];-",
            "y:MEM_TUPLE_ATTR;struct[s].tuple[_3].attr[y];-",
            "z:MEM_TUPLE_ATTR;struct[s].tuple[_3].attr[z];-"
        )

        val sRef = "s:DEF_STRUCT;-;def.rell/struct[s]"
        val aRef = "a:MEM_STRUCT_ATTR;-;def.rell/struct[s].attr[a]"
        chkKdls("query q() = s().a.x;", "q:*;*;-", sRef, aRef, "x:MEM_TUPLE_ATTR;-;def.rell/struct[s].tuple[_0].attr[x]")
        chkKdls("query q() = s().a[1].y;", "q:*;*;-", sRef, aRef, "y:MEM_TUPLE_ATTR;-;def.rell/struct[s].tuple[_1].attr[y]")
        chkKdls("query q() = s().a[1].z;", "q:*;*;-", sRef, aRef, "z:MEM_TUPLE_ATTR;-;def.rell/struct[s].tuple[_1].attr[z]")
    }

    @Test fun testTupleAttrVal() {
        val attrs = arrayOf(
            "x:MEM_TUPLE_ATTR;query[q].tuple[_0].attr[x];-",
            "y:MEM_TUPLE_ATTR;query[q].tuple[_1].attr[y];-",
            "z:MEM_TUPLE_ATTR;query[q].tuple[_1].attr[z];-"
        )

        chkKdls("query q() = (x=123, (y=456, z=789)).x;", "q:*;*;-", *attrs, "x:MEM_TUPLE_ATTR;-;main.rell/query[q].tuple[_0].attr[x]")
        chkKdls("query q() = (x=123, (y=456, z=789))[1].y;", "q:*;*;-", *attrs, "y:MEM_TUPLE_ATTR;-;main.rell/query[q].tuple[_1].attr[y]")
        chkKdls("query q() = (x=123, (y=456, z=789))[1].z;", "q:*;*;-", *attrs, "z:MEM_TUPLE_ATTR;-;main.rell/query[q].tuple[_1].attr[z]")
    }

    @Test fun testUpdateAttr() {
        file("def.rell", "entity user { mutable name; mutable value: integer; }")
        chkFileKdls("def.rell",
            "user:DEF_ENTITY;entity[user];-",
            "name:DEF_TYPE;entity[user].attr[name];-",
            "value:MEM_ENTITY_ATTR_NORMAL_VAR;entity[user].attr[value];-",
            "integer:*;-;*"
        )
        chkKdls("function f() { update user @* {} ( .name = '', value = 123 ); }",
            "f:*;*;-",
            "user:DEF_ENTITY;-;def.rell/entity[user]",
            "name:MEM_ENTITY_ATTR_NORMAL_VAR;-;def.rell/entity[user].attr[name]",
            "value:MEM_ENTITY_ATTR_NORMAL_VAR;-;def.rell/entity[user].attr[value]"
        )
    }

    @Test fun testVarSimple() {
        chkKdls("function f() { val x = 123; return x; }", "f:*", "x:LOC_VAL;-;-", "x:LOC_VAL;-;local[x:0]")
        chkKdls("function f() { var x = 123; return x; }", "f:*", "x:LOC_VAR;-;-", "x:LOC_VAR;-;local[x:0]")
    }

    @Test fun testVarComplex() {
        val xyz = arrayOf("x:LOC_VAL;-;-", "y:LOC_VAL;-;-", "z:LOC_VAL;-;-")
        chkKdls("function f() { val (x, (y, z)) = (123, (456, 789)); return x; }", "f:*", *xyz, "x:LOC_VAL;-;local[x:0]")
        chkKdls("function f() { val (x, (y, z)) = (123, (456, 789)); return y; }", "f:*", *xyz, "y:LOC_VAL;-;local[y:0]")
        chkKdls("function f() { val (x, (y, z)) = (123, (456, 789)); return z; }", "f:*", *xyz, "z:LOC_VAL;-;local[z:0]")
    }
}
