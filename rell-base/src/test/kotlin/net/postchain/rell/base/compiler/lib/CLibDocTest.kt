/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.lib

import net.postchain.common.hexStringToByteArray
import net.postchain.rell.base.lmodel.L_TypeUtils
import net.postchain.rell.base.lmodel.dsl.BaseLTest
import net.postchain.rell.base.model.*
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.testutils.RellTestUtils
import net.postchain.rell.base.utils.doc.DocCode
import org.junit.Test
import java.math.BigInteger
import kotlin.test.assertEquals

class CLibDocTest: BaseCLibTest() {
    @Test fun testTypeExprCollections() {
        chkTypeExpr("list<integer>", "[list]<[integer]>")
        chkTypeExpr("map<integer,text>", "[map]<[integer], [text]>")
        chkTypeExpr("map<integer,list<(text,boolean)>>", "[map]<[integer], [list]<([text], [boolean])>>")
    }

    @Test fun testTypeExprVirtual() {
        chkTypeExpr("virtual<list<text>>", "<virtual><[list]<[text]>>")
        chkTypeExpr("virtual<set<text>>", "<virtual><[set]<[text]>>")
        chkTypeExpr("virtual<map<text,integer>>", "<virtual><[map]<[text], [integer]>>")
        chkTypeExpr("virtual<my_struct>", "<virtual><[my_struct]>")
        chkTypeExpr("virtual<(text,integer)>", "<virtual><([text], [integer])>")
    }

    @Test fun testTypeExprMirrorStruct() {
        chkTypeExpr("struct<my_entity>", "<struct><[my_entity]>")
        chkTypeExpr("struct<mutable my_entity>", "<struct><<mutable> [my_entity]>")
        chkTypeExpr("struct<my_object>", "<struct><[my_object]>")
        chkTypeExpr("struct<mutable my_object>", "<struct><<mutable> [my_object]>")
        chkTypeExpr("struct<my_operation>", "<struct><[my_operation]>")
        chkTypeExpr("struct<mutable my_operation>", "<struct><<mutable> [my_operation]>")
    }

    private fun chkTypeExpr(type: String, expected: String) {
        val rellCode = """
            struct my_struct {}
            entity my_entity {}
            object my_object {}
            operation my_operation() {}
            struct __s { x: $type; } 
        """

        val actual = RellTestUtils.processApp(rellCode) { tApp ->
            val struct = tApp.rApp.moduleMap.getValue(R_ModuleName.EMPTY).structs.getValue("__s")
            val attr = struct.struct.attributes.getValue(R_Name.of("x"))
            val mType = attr.type.mType
            val docCode = DocCode.builder().also { L_TypeUtils.docCode(it, mType) }.build()
            docCode.strCode()
        }

        assertEquals(expected, actual)
    }

    @Test fun testValue() {
        chkValue("null", Rt_NullValue, "<null> = <null>")
        chkValue("unit", Rt_UnitValue, "TYPE = unit")
        chkValue("boolean", Rt_BooleanValue.FALSE, "TYPE = <false>")
        chkValue("boolean", Rt_BooleanValue.TRUE, "TYPE = <true>")
        chkValue("integer", Rt_IntValue.get(123456), "TYPE = 123456")

        chkValue("big_integer", Rt_BigIntegerValue.get(123456), "TYPE = 123456L")
        chkValue("big_integer", Rt_BigIntegerValue.get(BigInteger.TWO.pow(4096)), "TYPE")

        chkValue("decimal", Rt_DecimalValue.get("123"), "TYPE = 123.0")
        chkValue("decimal", Rt_DecimalValue.get("123.456"), "TYPE = 123.456")
        chkValue("decimal", Rt_DecimalValue.get("123.456e6"), "TYPE = 123456000.0")
        chkValue("decimal", Rt_DecimalValue.get("123.456e2000"), "TYPE")

        chkValue("byte_array", Rt_ByteArrayValue.EMPTY, "TYPE = x\"\"")
        chkValue("byte_array", Rt_ByteArrayValue.get("12ab".hexStringToByteArray()), "TYPE = x\"12AB\"")
        chkValue("byte_array", Rt_ByteArrayValue.get("12ab".repeat(100).hexStringToByteArray()),
            "TYPE = x\"${"12AB".repeat(25)}...")

        chkValue("rowid", Rt_RowidValue.get(123456), "TYPE = rowid(123456)")
    }

    @Test fun testValueText() {
        chkValue("text", Rt_TextValue.get(""), "TYPE = \"\"")
        chkValue("text", Rt_TextValue.get("abc"), "TYPE = \"abc\"")
        chkValue("text", Rt_TextValue.get("foo'bar"), "TYPE = \"foo'bar\"")
        chkValue("text", Rt_TextValue.get("foo\"bar"), "TYPE = \"foo\\\"bar\"")
        chkValue("text", Rt_TextValue.get("foo\tbar"), "TYPE = \"foo\\tbar\"")
        chkValue("text", Rt_TextValue.get("foo\nbar"), "TYPE = \"foo\\nbar\"")
        chkValue("text", Rt_TextValue.get("foo\u001Fbar"), "TYPE = \"foo\\u001Fbar\"")
        chkValue("text", Rt_TextValue.get("Привет"), "TYPE = \"Привет\"")
        chkValue("text", Rt_TextValue.get("food".repeat(100)), "TYPE = \"${"food".repeat(25)}...")
    }

    @Test fun testValueComplex() {
        chkValue("list<integer>", Rt_ListValue(R_ListType(R_IntegerType), mutableListOf()), "[list]<[integer]>")
        chkValue("set<integer>", Rt_SetValue(R_SetType(R_IntegerType), mutableSetOf()), "[set]<[integer]>")

        chkValue("map<integer,text>", Rt_MapValue(R_MapType(R_IntegerType, R_TextType), mutableMapOf()),
            "[map]<[integer], [text]>")

        val tupleType = R_TupleType.create(R_IntegerType, R_TextType)
        chkValue("(integer,text)", Rt_TupleValue(tupleType, listOf(Rt_IntValue.get(123), Rt_TextValue.get("abc"))),
            "([integer], [text])")
    }

    private fun chkValue(type: String, value: Rt_Value, expected: String) {
        val mod = makeModule {
            constant("C", type, value)
        }
        val expected2 = expected.replace("TYPE", "[$type]")
        BaseLTest.chkDoc(mod.lModule, "C", "CONSTANT|test:C", "<val> C: $expected2")
    }
}
