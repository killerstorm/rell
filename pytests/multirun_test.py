#!/usr/bin/env python3

import re
import pytest

pytest.register_assert_rewrite('testlib')
pytest.register_assert_rewrite('apprunner')

import testlib
import apprunner

NO_TESTS_STDOUT = [
    '',
    '------------------------------------------------------------------------',
    'TEST RESULTS:',
    '',
    'SUMMARY: 0 FAILED / 0 PASSED / 0 TOTAL',
    '',
    '',
    '***** OK *****',
]

CTE_STDOUT = [
    '<LOG:INFO>PostchainApp - STARTING POSTCHAIN APP',
    '<LOG:INFO><RE>PostchainApp -     source directory: /.+',
    '<LOG:INFO><RE>PostchainApp -     run config file: /.+/.+[.]xml',
    '<LOG:INFO><RE>PostchainApp - ',
    '<LOG:INFO><RE>RellToolsUtils - rell: [0-9]+[.][0-9]+[.][0-9]+(-SNAPSHOT)?;.*',
]

def test__run_simple():
    app = apprunner.Multirun('multirun.sh -d work/testproj/src work/testproj/config/run-simple.xml')
    try:
        app.wait_till_up()
        app.check_query('{"type":"sum_digits_int","n":1000}', status = 200, text = '"73fb9a5de29b"')
        app.check_query('{"type":"sum_digits_dec","n":1000}', status = 200, text = '"73fb9a5de29b"')
        app.check_query('{"type":"get_module_args"}', status = 200, text = '{"x":123456,"y":"Hello!"}')
        app.check_query('{"type":"get_common_args"}', status = 200, text = '{"message":"Some common message..."}')
    finally:
        app.stop()

def test__run_simple__get_raw_config():
    app = apprunner.Multirun('multirun.sh -d work/testproj/src work/testproj/config/run-simple.xml')
    try:
        app.wait_till_up()
        r = app.send_post('query/iid_1', '{"type":"get_raw_config"}')
        assert r.status_code == 200
        testlib.check_json(r.text, {
            'blockstrategy': {
                'name': 'net.postchain.base.BaseBlockBuildingStrategy'
            },
            'configurationfactory': 'net.postchain.gtx.GTXBlockchainConfigurationFactory',
            'gtx': {
                'modules': [
                    'net.postchain.rell.module.RellPostchainModuleFactory',
                    'net.postchain.gtx.StandardOpsGTXModule'
                ],
                'rell': {
                    'moduleArgs': {
                        'run_common': {
                            'message': 'Some common message...'
                        },
                        'run_simple': {
                            'x': 123456,
                            'y': 'Hello!'
                        }
                    },
                    'modules': [
                        'run_simple'
                    ],
                    'version': testlib.RELL_VERSION,
                    'sources': {
                        'calc.rell': re.compile(r'.*', flags = re.DOTALL),
                        'run_common.rell': re.compile(r'.*', flags = re.DOTALL),
                        'run_simple.rell': re.compile(r'.*', flags = re.DOTALL),
                    }
                }
            },
            'signers': [
                '0350FE40766BC0CE8D08B3F5B810E49A8352FDD458606BD5FAFE5ACDCDC8FF3F57'
            ]
        })
    finally:
        app.stop()

def test__run_simple__get_app_structure():
    app = apprunner.Multirun('multirun.sh -d work/testproj/src work/testproj/config/run-simple.xml')
    try:
        app.wait_till_up()
        r = app.send_post('query/iid_1', '{"type":"rell.get_app_structure"}')
        assert r.status_code == 200

        testlib.check_json(r.text, {
            'modules': {
                'calc': {
                    'name': 'calc',
                    'functions': {
                        'factorial': {},
                        'sum_digits_decimal': {},
                        'sum_digits_integer': {},
                    }
                },
                'run_common': {
                    'name': 'run_common',
                    'queries': {
                        'get_common_args': {},
                    },
                    'structs': {
                        'module_args': {
                            'attributes': {
                                'message': { 'mutable': 0, 'type': 'text' },
                            }
                        }
                    }
                },
                'run_simple': {
                    'name': 'run_simple',
                    'functions': {
                        'get_module_args_0': {},
                    },
                    'queries': {
                        'get_args_chksum': {},
                        'get_module_args': {},
                        'get_raw_config': {},
                        'sum_digits_dec': {},
                        'sum_digits_int': {},
                    },
                    'structs': {
                        'module_args': {
                            'attributes': {
                                'x': { 'mutable': 0, 'type': 'integer' },
                                'y': { 'mutable': 0, 'type': 'text' },
                            }
                        }
                    }
                }
            }
        })
    finally:
        app.stop()

def test__run_stack_trace__main_q():
    app = apprunner.Multirun('multirun.sh -d work/testproj/src work/testproj/config/run-stack_trace.xml')
    try:
        app.wait_till_up()

        app.skip_output()

        app.check_query('{"type":"main_q","x":12345}', status = 400,
            text = r'{"error":"[stack_trace:calc(stack_trace/main.rell:7)] Query \u0027main_q\u0027 failed: x must be positive, but was 0"}')

        app.check_output([
            '<LOG:INFO>Rell - [stack_trace:main_q(stack_trace/main.rell:34)] main start',
            '<LOG:INFO>Rell - ERROR Query \'main_q\' failed: x must be positive, but was 0',
            '\tat stack_trace:calc(stack_trace/main.rell:7)',
            '\tat stack_trace:calc(stack_trace/main.rell:11)',
            '\tat stack_trace:calc(stack_trace/main.rell:12)',
            '\tat stack_trace:calc(stack_trace/main.rell:13)',
            '\tat stack_trace:calc(stack_trace/main.rell:14)',
            '\tat stack_trace:calc(stack_trace/main.rell:15)',
            '\tat stack_trace:main_q(stack_trace/main.rell:35)',
        ], ignore_rest = True)
    finally:
        app.stop()

def test__run_stack_trace__error_q():
    app = apprunner.Multirun('multirun.sh -d work/testproj/src work/testproj/config/run-stack_trace.xml')
    try:
        app.wait_till_up()

        def check_error(arg, expected):
            app.check_query('{"type":"error_q","e":"' + arg + '"}', status = 400, text = r'{"error":"' + expected + '"}')

        p = r'[stack_trace:error(stack_trace/errors.rell:%d)] Query \u0027error_q\u0027 failed: %s'

        check_error('/', p % (3, r'Division by zero: 1 / 0'))
        check_error('%', p % (4, r'Division by zero: 1 % 0'))
        check_error('-', p % (5, r'Integer overflow: -(-9223372036854775808)'))
        check_error('abs', p % (6, r'System function \u0027abs\u0027: Integer overflow: -9223372036854775808'))
        check_error('decimal', p % (7, r'System function \u0027decimal\u0027: Invalid decimal value: \u0027hello\u0027'))
        check_error('integer.from_hex', p % (8, r'System function \u0027integer.from_hex\u0027: Invalid hex number: \u0027hello\u0027'))
        check_error('list[]', p % (9, r'List index out of bounds: 1000 (size 0)'))
        check_error('require', p % (11, r'Requirement error'))
        check_error('text[]', p % (12, r'Index out of bounds: 1000 (length 5)'))
        check_error('text.char_at', p % (13, r'System function \u0027text.char_at\u0027: Index out of bounds: 1000 (length 5)'))

        app.check_query('{"type":"error_q","e":"???"}', status = 200, text = '0')
    finally:
        app.stop()

def test__run_stack_trace_entities():
    app = apprunner.Multirun('multirun.sh -d work/testproj/src work/testproj/config/run-stack_trace_entities.xml')
    try:
        app.wait_till_up()

        app.skip_output()
        app.check_query('{"type":"ent_main_q"}', status = 400,
            text = r'{"error":"[stack_trace.entities:ent_main(stack_trace/entities.rell:6)] Query \u0027ent_main_q\u0027 failed: No records found"}')

        app.check_output([
            '<LOG:INFO>Rell - ERROR Query \'ent_main_q\' failed: No records found',
            '\tat stack_trace.entities:ent_main(stack_trace/entities.rell:6)',
            '\tat stack_trace.entities:ent_main_q(stack_trace/entities.rell:11)',
        ], ignore_rest = True)
    finally:
        app.stop()

def test__run_tests():
    testlib.check_tests('multirun.sh -d work/testproj/src work/testproj/config/run-tests.xml --test', 0, [
        'TEST RESULTS:',
        '',
        'OK foo[1]:run_tests.common_test:test_common',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_module_args',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_module_args',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_user',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_create_data',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_create_data',
        'OK foo[1]:run_tests.foo_extra_test:test_foo_extra',
        'OK bar[2]:run_tests.bar.bar_test:test_module_args',
        'OK bar[2]:run_tests.bar.bar_test:test_common_module_args',
        'OK bar[2]:run_tests.bar.bar_test:test_common_user',
        'OK bar[2]:run_tests.bar.bar_test:test_create_data',
        'OK bar[2]:run_tests.bar.bar_test:test_common_create_data',
        'OK bar[2]:run_tests.bar.bar_test:test_nop',
        'OK bar[2]:run_tests.bar_extra_test:test_bar_extra',
        'OK bar[2]:run_tests.common_test:test_common',
        '',
        'SUMMARY: 0 FAILED / 15 PASSED / 15 TOTAL',
        '',
        '',
        '***** OK *****',
    ])

def test__run_tests__filter_common():
    testlib.check_tests('multirun.sh -d work/testproj/src work/testproj/config/run-tests.xml --test --test-filter run_tests.common*:*', 0, [
        'TEST RESULTS:',
        '',
        'OK foo[1]:run_tests.common_test:test_common',
        'OK bar[2]:run_tests.common_test:test_common',
        '',
        'SUMMARY: 0 FAILED / 2 PASSED / 2 TOTAL',
        '',
        '',
        '***** OK *****',
    ])

def test__run_tests__filter_foo():
    testlib.check_tests('multirun.sh -d work/testproj/src work/testproj/config/run-tests.xml --test --test-filter run_tests.foo*:*', 0, [
        'TEST RESULTS:',
        '',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_module_args',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_module_args',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_user',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_create_data',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_create_data',
        'OK foo[1]:run_tests.foo_extra_test:test_foo_extra',
        '',
        'SUMMARY: 0 FAILED / 6 PASSED / 6 TOTAL',
        '',
        '',
        '***** OK *****',
    ])

def test__run_tests__filter_bar():
    testlib.check_tests('multirun.sh -d work/testproj/src work/testproj/config/run-tests.xml --test --test-filter run_tests.bar.bar_test', 0, [
        'TEST RESULTS:',
        '',
        'OK bar[2]:run_tests.bar.bar_test:test_module_args',
        'OK bar[2]:run_tests.bar.bar_test:test_common_module_args',
        'OK bar[2]:run_tests.bar.bar_test:test_common_user',
        'OK bar[2]:run_tests.bar.bar_test:test_create_data',
        'OK bar[2]:run_tests.bar.bar_test:test_common_create_data',
        'OK bar[2]:run_tests.bar.bar_test:test_nop',
        '',
        'SUMMARY: 0 FAILED / 6 PASSED / 6 TOTAL',
        '',
        '',
        '***** OK *****',
    ])

def test__run_tests__chain():
    testlib.check_tests('multirun.sh -d work/testproj/src work/testproj/config/run-tests.xml --test --test-chain foo,bar', 0, [
        'TEST RESULTS:',
        '',
        'OK foo[1]:run_tests.common_test:test_common',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_module_args',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_module_args',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_user',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_create_data',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_create_data',
        'OK foo[1]:run_tests.foo_extra_test:test_foo_extra',
        'OK bar[2]:run_tests.bar.bar_test:test_module_args',
        'OK bar[2]:run_tests.bar.bar_test:test_common_module_args',
        'OK bar[2]:run_tests.bar.bar_test:test_common_user',
        'OK bar[2]:run_tests.bar.bar_test:test_create_data',
        'OK bar[2]:run_tests.bar.bar_test:test_common_create_data',
        'OK bar[2]:run_tests.bar.bar_test:test_nop',
        'OK bar[2]:run_tests.bar_extra_test:test_bar_extra',
        'OK bar[2]:run_tests.common_test:test_common',
        '',
        'SUMMARY: 0 FAILED / 15 PASSED / 15 TOTAL',
        '',
        '',
        '***** OK *****',
    ])

    testlib.check_tests('multirun.sh -d work/testproj/src work/testproj/config/run-tests.xml --test --test-chain bar,foo', 0, [
        'TEST RESULTS:',
        '',
        'OK foo[1]:run_tests.common_test:test_common',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_module_args',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_module_args',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_user',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_create_data',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_create_data',
        'OK foo[1]:run_tests.foo_extra_test:test_foo_extra',
        'OK bar[2]:run_tests.bar.bar_test:test_module_args',
        'OK bar[2]:run_tests.bar.bar_test:test_common_module_args',
        'OK bar[2]:run_tests.bar.bar_test:test_common_user',
        'OK bar[2]:run_tests.bar.bar_test:test_create_data',
        'OK bar[2]:run_tests.bar.bar_test:test_common_create_data',
        'OK bar[2]:run_tests.bar.bar_test:test_nop',
        'OK bar[2]:run_tests.bar_extra_test:test_bar_extra',
        'OK bar[2]:run_tests.common_test:test_common',
        '',
        'SUMMARY: 0 FAILED / 15 PASSED / 15 TOTAL',
        '',
        '',
        '***** OK *****',
    ])

    testlib.check_tests('multirun.sh -d work/testproj/src work/testproj/config/run-tests.xml --test --test-chain foo', 0, [
        'TEST RESULTS:',
        '',
        'OK foo[1]:run_tests.common_test:test_common',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_module_args',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_module_args',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_user',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_create_data',
        'OK foo[1]:run_tests.foo_10.foo_10_test:test_common_create_data',
        'OK foo[1]:run_tests.foo_extra_test:test_foo_extra',
        '',
        'SUMMARY: 0 FAILED / 7 PASSED / 7 TOTAL',
        '',
        '',
        '***** OK *****',
    ])
    testlib.check_tests('multirun.sh -d work/testproj/src work/testproj/config/run-tests.xml --test --test-chain bar', 0, [
        'TEST RESULTS:',
        '',
        'OK bar[2]:run_tests.bar.bar_test:test_module_args',
        'OK bar[2]:run_tests.bar.bar_test:test_common_module_args',
        'OK bar[2]:run_tests.bar.bar_test:test_common_user',
        'OK bar[2]:run_tests.bar.bar_test:test_create_data',
        'OK bar[2]:run_tests.bar.bar_test:test_common_create_data',
        'OK bar[2]:run_tests.bar.bar_test:test_nop',
        'OK bar[2]:run_tests.bar_extra_test:test_bar_extra',
        'OK bar[2]:run_tests.common_test:test_common',
        '',
        'SUMMARY: 0 FAILED / 8 PASSED / 8 TOTAL',
        '',
        '',
        '***** OK *****',
    ])

def test__run_tests_generic():
    testlib.check_tests('multirun.sh -d work/testproj/src work/testproj/config/run-tests-generic.xml --test', 1, [
        'TEST RESULTS:',
        '',
        'OK A[1]:run_tests.generic.tests:test_get_app_name',
        'OK A[1]:run_tests.generic.tests:test_add_user',
        'OK B[2]:run_tests.generic.tests:test_get_app_name',
        'OK B[2]:run_tests.generic.tests:test_add_user',
        '',
        'FAILED A[1]:run_tests.generic.tests:test_fail',
        'FAILED B[2]:run_tests.generic.tests:test_fail',
        '',
        'SUMMARY: 2 FAILED / 4 PASSED / 6 TOTAL',
        '',
        '',
        '***** FAILED *****',
    ])

def test__run_extend__test():
    testlib.check_command('multirun.sh -d work/testproj/src work/testproj/config/run-extend.xml --test',
        stdout = [
            '------------------------------------------------------------------------',
            'TEST foo[1]:extend.test:test',
            '[f]',
            'OK foo[1]:extend.test:test',
            '',
            '------------------------------------------------------------------------',
            'TEST RESULTS:',
            '',
            'OK foo[1]:extend.test:test',
            '',
            'SUMMARY: 0 FAILED / 1 PASSED / 1 TOTAL',
            '',
            '',
            '***** OK *****',
        ],
        stdout_ignore = [
            '<LOG:INFO><RE>SQLDatabaseAccess - Upgrading to version [0-9]+',
            '<LOG:INFO><RE>SQLDatabaseAccess - Database version has been updated to version: [0-9]+',
        ]
    )

def test__run_extend_g__test():
    testlib.check_command('multirun.sh -d work/testproj/src work/testproj/config/run-extend-g.xml --test',
        stdout = [
            '------------------------------------------------------------------------',
            'TEST foo[1]:extend.test:test',
            '[g, f]',
            'OK foo[1]:extend.test:test',
            '',
            '------------------------------------------------------------------------',
            'TEST RESULTS:',
            '',
            'OK foo[1]:extend.test:test',
            '',
            'SUMMARY: 0 FAILED / 1 PASSED / 1 TOTAL',
            '',
            '',
            '***** OK *****',
        ],
        stdout_ignore = [
            '<LOG:INFO><RE>SQLDatabaseAccess - Upgrading to version [0-9]+',
            '<LOG:INFO><RE>SQLDatabaseAccess - Database version has been updated to version: [0-9]+',
        ]
    )

def test__run_extend_h__test():
    testlib.check_command('multirun.sh -d work/testproj/src work/testproj/config/run-extend-h.xml --test',
        stdout = [
            '------------------------------------------------------------------------',
            'TEST foo[1]:extend.test:test',
            '[h, f]',
            'OK foo[1]:extend.test:test',
            '',
            '------------------------------------------------------------------------',
            'TEST RESULTS:',
            '',
            'OK foo[1]:extend.test:test',
            '',
            'SUMMARY: 0 FAILED / 1 PASSED / 1 TOTAL',
            '',
            '',
            '***** OK *****',
        ],
        stdout_ignore = [
            '<LOG:INFO><RE>SQLDatabaseAccess - Upgrading to version [0-9]+',
            '<LOG:INFO><RE>SQLDatabaseAccess - Database version has been updated to version: [0-9]+',
        ]
    )

def test__run_tests_simple__test():
    testlib.check_tests('multirun.sh -d work/testproj/src work/testproj/config/run-tests-simple.xml --test', code = 1, expected = [
            'TEST RESULTS:',
            '',
            'OK foo[1]:tests.calc_test:test_square',
            'OK foo[1]:tests.calc_test:test_cube',
            'OK foo[1]:tests.data_test:test_add_user',
            'OK foo[1]:tests.data_test:test_remove_user',
            'OK foo[1]:tests.event_test:test_event',
            'OK foo[1]:tests.foobar:test_foo',
            'OK foo[1]:tests.lib_test:test_lib',
            '',
            'FAILED foo[1]:tests.foobar:test_fail_require',
            'FAILED foo[1]:tests.foobar:test_fail_assert_equals',
            '',
            'SUMMARY: 2 FAILED / 7 PASSED / 9 TOTAL',
            '',
            '',
            '***** FAILED *****',
    ])

def test__modargs_ok():
    app = apprunner.Multirun('multirun.sh -d work/testproj/src work/testproj/config/run-modargs-ok.xml')
    try:
        app.wait_till_up()
        app.check_query('{"type":"foo_args"}', status = 200, text = '{"x":123,"y":"Bob"}')
        app.check_query('{"type":"bar_args"}', status = 200, text = '{"a":"Alice","b":456}')
    finally:
        app.stop()

def test__modargs_ok__test():
    testlib.check_command('multirun.sh -d work/testproj/src work/testproj/config/run-modargs-ok.xml --test', stdout = NO_TESTS_STDOUT)

def test__modargs_extra():
    app = apprunner.Multirun('multirun.sh -d work/testproj/src work/testproj/config/run-modargs-extra.xml')
    try:
        app.wait_till_up()
        app.check_query('{"type":"foo_args"}', status = 200, text = '{"x":123,"y":"Bob"}')
        app.check_query('{"type":"bar_args"}', status = 200, text = '{"a":"Alice","b":456}')
    finally:
        app.stop()

def test__modargs_extra__test():
    testlib.check_command('multirun.sh -d work/testproj/src work/testproj/config/run-modargs-extra.xml --test', stdout = NO_TESTS_STDOUT)

def test__modargs_missing():
    testlib.check_command('multirun.sh -d work/testproj/src work/testproj/config/run-modargs-missing.xml', code = 2, stdout = CTE_STDOUT,
        stderr = 'ERROR: Missing module_args for module(s): modargs.bar\n')

def test__modargs_missing__test():
    testlib.check_command('multirun.sh -d work/testproj/src work/testproj/config/run-modargs-missing.xml --test', code = 2,
        stderr = 'ERROR: Missing module_args for module(s): modargs.bar\n')

def test__modargs_wrong():
    testlib.check_command('multirun.sh -d work/testproj/src work/testproj/config/run-modargs-wrong.xml', code = 2, stdout = CTE_STDOUT,
        stderr = "ERROR: Bad module_args for module 'modargs.bar': Decoding type 'text': expected STRING, actual INTEGER (attribute: modargs.bar:module_args.a)\n")

def test__modargs_wrong__test():
    testlib.check_command('multirun.sh -d work/testproj/src work/testproj/config/run-modargs-wrong.xml --test', code = 2,
        stderr = "ERROR: Bad module_args for module 'modargs.bar': Decoding type 'text': expected STRING, actual INTEGER (attribute: modargs.bar:module_args.a)\n")
