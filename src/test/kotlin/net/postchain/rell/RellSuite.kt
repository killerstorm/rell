package net.postchain.rell

import net.postchain.rell.lib.*
import net.postchain.rell.misc.*
import net.postchain.rell.module.*
import org.junit.Ignore
import org.junit.runner.RunWith
import org.junit.runners.Suite

@Suite.SuiteClasses(
        AtExprOpTest::class,
        AtExprPathTest::class,
        AtExprTest::class,
        ClassTest::class,
        CreateTest::class,
        EnumTest::class,
        ExpressionTest::class,
        ExternalTest::class,
        IncludeTest::class,
        IncrementTest::class,
        InterpOpTest::class,
        LogAnnotationTest::class,
        ModuleTest::class,
        NamespaceTest::class,
        NullableTest::class,
        NullAnalysisTest::class,
        NullPropagationTest::class,
        ObjectTest::class,
        OperationTest::class,
        QueryTest::class,
        RecordTest::class,
        SqlInitTest::class,
        StatementTest::class,
        TokenizerTest::class,
        TupleMatchingTest::class,
        TypeTest::class,
        UpdateDeleteExprTest::class,
        UpdateDeletePathTest::class,
        UpdateDeleteTest::class,
        UserFunctionTest::class,
        VirtualTest::class,
        WhenTest::class,

        LibBlockTransactionTest::class,
        LibByteArrayTest::class,
        LibCryptoTest::class,
        LibGtvTest::class,
        LibIntegerTest::class,
        LibListTest::class,
        LibMapTest::class,
        LibOpContextTest::class,
        LibRangeTest::class,
        LibRequireTest::class,
        LibSetTest::class,
        LibTest::class,
        LibTextTest::class,

        BetterParseExperimentalTest::class,
        GraphUtilsTest::class,
        KotlinTest::class,
        RellConfigGenTest::class,
        RunConfigGenTest::class,

        BasicModuleTest::class,
        GtvRtConversionTest::class,
        GtxExternalTest::class,
        GtxModuleTest::class,
        GtxTest::class
)
@RunWith(Suite::class)
@Ignore
class RellSuite
