Expressions
===========

Values
------

Simple values:

-  Null: ``null`` (type is ``null``)
-  Boolean: ``true``, ``false``
-  Integer: ``123``, ``0``, ``-456``
-  Text: ``'Hello'``, ``"World"``
-  Byte array: ``x'1234'``, ``x"ABCD"``

Text literals may have escape-sequences:

-  Standard: ``\r``, ``\n``, ``\t``, ``\b``.
-  Special characters: ``\"``, ``\'``, ``\\``.
-  Unicode: ``\u003A``.

Operators
---------

Special
~~~~~~~

-  ``.`` - member access: ``user.name``, ``s.sub(5, 10)``
-  ``()`` - function call: ``print('Hello')``, ``value.to_text()``
-  ``[]`` - element access: ``values[i]``

Comparison
~~~~~~~~~~

-  ``==``
-  ``!=``
-  ``===``
-  ``!==``
-  ``<``
-  ``>``
-  ``<=``
-  ``>=``

Operators ``==`` and ``!=`` compare values. For complex types (collections, tuples, structs) they compare member
values, recursively. For ``entity`` values only object IDs are compared.

Operators ``===`` and ``!==`` compare references, not values. They can be used only on types:
tuple, ``struct``, ``list``, ``set``, ``map``, ``gtv``, ``range``.

Example:

::

    val x = [1, 2, 3];
    val y = list(x);
    print(x == y);      // true - values are equal
    print(x === y);     // false - two different objects

Arithmetical
~~~~~~~~~~~~

-  ``+``
-  ``-``
-  ``*``
-  ``/``
-  ``%``
-  ``++``
-  ``--``

Logical
~~~~~~~

-  ``and``
-  ``or``
-  ``not``

If
~~~~~~~~

Operator ``if`` is used for conditional evaluation:

::

    val max = if (a >= b) a else b;
    return max;

Other
~~~~~

-  ``in`` - check if an element is in a range/set/map

-------------

*Rell v0.10.1*