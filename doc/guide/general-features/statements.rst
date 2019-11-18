Statements
==========

Local variable declaration
--------------------------

Constants:

::

    val x = 123;
    val y: text = 'Hello';

Variables:

::

    var x: integer;
    var y = 123;
    var z: text = 'Hello';

Basic statements
----------------

Assignment:

::

    x = 123;
    values[i] = z;
    y += 15;

Function call:

::

    print('Hello');

Return:

::

    return;
    return 123;

Block:

::

    {
        val x = calc();
        print(x);
    }

If statement
------------

::

    if (x == 5) print('Hello');

    if (y == 10) {
        print('Hello');
    } else {
        print('Bye');
    }

    if (x == 0) {
        return 'Zero';
    } else if (x == 1) {
        return 'One';
    } else {
        return 'Many';
    }

Can also be used as an expression:

::

    function my_abs(x: integer): integer = if (x >= 0) x else -x;

When statement
--------------

Similar to ``switch`` in C++ or Java, but using the syntax of ``when`` in Kotlin:

::

    when(x) {
        1 -> return 'One';
        2, 3 -> return 'Few';
        else -> {
            val res = 'Many: ' + x;
            return res;
        }
    }

Features:

- Can use both constants as well as arbitrary expressions.
- When using constant values, the compiler checks that all values are unique.
- When using with an enum type, values can be specified by simple name, not full name.

A form of ``when`` without an argument is equivalent to a chain of ``if`` ... ``else`` ``if``:

::

    when {
        x == 1 -> return 'One';
        x >= 2 and x <= 7 -> return 'Several';
        x == 11, x == 111 -> return 'Magic number';
        some_value > 1000 -> return 'Special case';
        else -> return 'Unknown';
    }

- Can use arbitrary boolean expressions.
- When multiple comma-separated expressions are specified, any of them triggers the block (i. e. they are combined via OR).

Both forms of ``when`` (with and without an argument) can be used as an expression:

::

    return when(x) {
        1 -> 'One';
        2, 3 -> 'Few';
        else -> 'Many';
    }

- ``else`` must always be specified, unless all possible values of the argument are specified (possible for boolean
  and enum types).
- Can be used in at-expression, in which case it is translated to SQL ``CASE WHEN`` ... ``THEN`` expression.

Loop statements
---------------

For:

::

    for (x in range(10)) {
        print(x);
    }

    for (u in user @* {}) {
        print(u.name);
    }

The expression after ``in`` may return a ``range`` or a collection
(``list``, ``set``, ``map``).

Tuple unpacking can be used in a loop:

::

    val l: list<(integer, text)> = get_list();
    for ((n, s) in l) { ... }

While:

::

    while (x < 10) {
        print(x);
        x = x + 1;
    }

Break:

::

    for (u in user @* {}) {
        if (u.company == 'Facebook') {
            print(u.name);
            break;
        }
    }

    while (x < 5) {
        if (values[x] == 3) break;
        x = x + 1;
    }

-------------

*Rell v0.10.0*