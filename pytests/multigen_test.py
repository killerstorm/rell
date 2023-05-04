#!/usr/bin/env python3

import os
import re
import tempfile
import pytest

pytest.register_assert_rewrite('testlib')
pytest.register_assert_rewrite('apprunner')

import testlib
import apprunner

def run_multigen(command, **args):
    dir = tempfile.mkdtemp()
    print('temp dir:', dir)
    assert os.listdir(dir) == []
    testlib.check_command(command % dir, **args)
    return dir

def run_postchain_wipe_db(dir):
    testlib.check_command('postchain.sh wipe-db -nc %s/node-config.properties' % dir, log_format = testlib.LOG_FORMAT_POSTCHAIN, stdout = [
        '<RE>wipe-db will be executed with: -nc /tmp/[0-9a-z_]+/node-config[.]properties',
        'Database has been wiped successfully',
    ])

def test__run_simple__generate():
    dir = run_multigen('multigen.sh -d work/testproj/src -o %s work/testproj/config/run-simple.xml')

    files = os.listdir(dir)
    assert 'node-config.properties' in files
    assert 'private.properties' in files
    assert 'blockchains' in files

    assert os.listdir(dir + '/blockchains') == ['1']

    files = sorted(os.listdir(dir + '/blockchains/1'))
    assert files == ['0.gtv', '0.xml', 'brid.txt']

    with open(dir + '/blockchains/1/brid.txt', 'rt') as f:
        brid = f.read()
    assert re.fullmatch(r'[0-9A-F]{64}', brid), brid

def test__run_simple__start_node():
    dir = run_multigen('multigen.sh -d work/testproj/src -o %s work/testproj/config/run-simple.xml')
    run_postchain_wipe_db(dir)

    app = apprunner.Postchain('postchain.sh run-node-auto -d %s' % dir)
    try:
        app.wait_till_up()

        r = app.send_post('query/iid_1', '{"type":"sum_digits_int","n":1000}')
        assert r.status_code == 200
        assert r.text == '"73fb9a5de29b"'

        r = app.send_post('query/iid_1', '{"type":"sum_digits_dec","n":1000}')
        assert r.status_code == 200
        assert r.text == '"73fb9a5de29b"'

        r = app.send_post('query/iid_1', '{"type":"get_module_args"}')
        assert r.status_code == 200
        assert r.text == '{"x":123456,"y":"Hello!"}'

        r = app.send_post('query/iid_1', '{"type":"get_common_args"}')
        assert r.status_code == 200
        assert r.text == '{"message":"Some common message..."}'
    finally:
        app.stop()

def test__run_stack_trace():
    dir = run_multigen('multigen.sh -d work/testproj/src -o %s work/testproj/config/run-stack_trace.xml')
    run_postchain_wipe_db(dir)

    app = apprunner.Postchain('postchain.sh run-node-auto -d %s' % dir)
    try:
        app.wait_till_up()

        app.skip_output()
        r = app.send_post('query/iid_1', '{"type":"main_q","x":12345}')
        assert r.status_code == 400
        assert r.text == r'{"error":"[stack_trace:calc(stack_trace/main.rell:7)] Query \u0027main_q\u0027 failed: x must be positive, but was 0"}'

        app.ignore_output(r'<LOG:DEBUG><RE>RestApi - Request body:.+')

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

def test__runxml_docs_sample():
    dir = run_multigen('multigen.sh -d work/runxml-docs-sample/src -o %s work/runxml-docs-sample/run.xml')

    files = os.listdir(dir)
    assert 'node-config.properties' in files
    assert 'private.properties' in files
    assert 'blockchains' in files

    assert sorted(os.listdir(dir + '/blockchains')) == ['1', '2']
    assert sorted(os.listdir(dir + '/blockchains/1')) == ['0.gtv', '0.xml', '1000.gtv', '1000.xml', 'brid.txt']
    assert sorted(os.listdir(dir + '/blockchains/2')) == ['0.gtv', '0.xml', '1000.gtv', '1000.xml', '2000.gtv', '2000.xml', '3000.gtv', '3000.xml', 'brid.txt']

    with open(dir + '/blockchains/1/brid.txt', 'rt') as f:
        brid = f.read()
    assert re.fullmatch(r'[0-9A-F]{64}', brid), brid

    with open(dir + '/blockchains/2/brid.txt', 'rt') as f:
        brid = f.read()
    assert re.fullmatch(r'[0-9A-F]{64}', brid), brid

def test__run_blktx():
    dir = run_multigen('multigen.sh -d work/testproj/src -o %s work/testproj/config/run-blktx.xml')
    run_postchain_wipe_db(dir)

    app = apprunner.Postchain('postchain.sh run-node-auto -d %s' % dir)
    try:
        app.wait_till_up()
        r = app.send_post('query/iid_2', '{"type":"get_data"}')
        assert r.status_code == 200
        assert r.text == '[[],[]]'
    finally:
        app.stop()

def test__modargs_ok():
    run_multigen('multigen.sh -d work/testproj/src -o %s work/testproj/config/run-modargs-ok.xml')

def test__modargs_missing():
    run_multigen('multigen.sh -d work/testproj/src -o %s work/testproj/config/run-modargs-missing.xml', code = 2,
        stderr = 'ERROR: Missing module_args for module(s): modargs.bar\n')

def test__modargs_wrong():
    run_multigen('multigen.sh -d work/testproj/src -o %s work/testproj/config/run-modargs-wrong.xml', code = 2,
        stderr = "ERROR: Bad module_args for module 'modargs.bar': Decoding type 'text': expected STRING, actual INTEGER (attribute: modargs.bar:module_args.a)\n")

def test__modargs_extra():
    run_multigen('multigen.sh -d work/testproj/src -o %s work/testproj/config/run-modargs-extra.xml')
