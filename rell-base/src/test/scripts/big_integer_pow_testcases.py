#!/usr/bin/env python3

import math
import sys
import os

PRECISION = 131072
LIMIT = 10 ** PRECISION

sys.set_int_max_str_digits(PRECISION + 1)

def print_testcase(x, exp, is_max):
    p = x ** exp
    s = str(p)
    head = s[:9]
    tail = s[-9:]
    print('chkPowOverflow("%d", %d, %d, "%s", "%s", %s)' % (x, exp, len(s), head, tail, ['false','true'][is_max]))

def calc_testcase(x):
    exp = math.floor(math.log(LIMIT, x))

    y = x
    k = 2 ** y.bit_length()
    while k > 0:
        r = y + k
        if r ** exp < LIMIT:
            y = r
        k //= 2

    lx = len(str(x ** exp))
    ly = len(str(y ** exp))

    print_testcase(x, exp, y == x)
    if y != x:
        print_testcase(y, exp, True)

def main():
    print('// generated by', os.path.basename(__file__))
    for x in [2,3,5,7,11,15,31,123,123456,123456789,12345678987654321,2**64,2**128]:
        calc_testcase(x)

main()