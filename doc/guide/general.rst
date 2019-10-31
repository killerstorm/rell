=========================
General Language Features
=========================

Types
=====

Simple types:
-------------

-  ``boolean``
-  ``integer``
-  ``decimal``
-  ``text``
-  ``byte_array``
-  ``rowid`` - primary key of a database record, 64-bit integer, supports only comparison operations
-  ``json``
-  ``unit`` - no value; cannot be used explicitly
-  ``null`` - type of ``null`` expression; cannot be used explicitly

Simple type aliases:

-  ``pubkey`` = ``byte_array``
-  ``name`` = ``text``
-  ``timestamp`` = ``integer``
-  ``tuid`` = ``text``

Complex types:
--------------

-  class
-  ``T?`` - nullable type
-  ``record``
-  tuple: ``(T1, ..., Tn)``
-  ``list<T>``
-  ``set<T>``
-  ``map<K,V>``
-  ``range`` (can be used in ``for`` statement)
-  ``gtv`` - used to encode parameters and results of operations and queries
-  ``virtual<T>`` - a reduced data structure with Merkle tree

Nullable type
-------------

-  Class attributes cannot be nullable.
-  Can be used with almost any type (except nullable, ``unit``, ``null``).
-  Nullable nullable (``T??`` is not allowed).
-  Normal operations of the underlying type cannot be applied directly.
-  Supports ``?:``, ``?.`` and ``!!`` operators (like in Kotlin).

Compatibility with other types:

-  Can assign a value of type ``T`` to a variable of type ``T?``, but
   not the other way round.
-  Can assign ``null`` to a variable of type ``T?``, but not to a variable of type ``T``.
-  Can assign a value of type ``(T)`` (tuple) to a variable of type ``(T?)``.
-  Cannot assign a value of type ``list<T>`` to a variable of type ``list<T?>``.

Allowed operations:

-  Null comparison: ``x == null``, ``x != null``.
-  ``??`` - null check operator: ``x??`` is equivalent to ``x != null``
-  ``!!`` - null assertion operator: ``x!!`` returns value of ``x`` if ``x`` is not ``null``, otherwise throws an exception
-  ``?:`` - Elvis operator: ``x ?: y`` means ``x`` if ``x`` is not ``null``, otherwise ``y``
-  ``?.`` - safe access: ``x?.y`` results in ``x.y`` if ``x`` is not ``null`` and ``null`` otherwise;
   similarly, ``x?.y()`` either evaluates and returns ``x.y()`` or returns ``null``
-  ``require(x)``, ``require_not_empty(x)``: throws an exception if ``x`` is ``null``, otherwise returns value of ``x``

Examples:

::

    function f(): integer? { ... }

    val x: integer? = f();  // type of "x" is "integer?"
    val y = x;              // type of "y" is "integer?"

    val i = y!!;            // type of "i" is "integer"
    val j = require(y);     // type of "j" is "integer"

    val a = y ?: 456;       // type of "a" is "integer"
    val b = y ?: null;      // type of "b" is "integer?"

    val p = y!!;            // type of "p" is "integer"
    val q = y?.to_hex();    // type of "q" is "text?"

    if (x != null) {
        val u = x;          // type of "u" is "integer" - smart cast is applied to "x"
    } else {
        val v = x;          // type of "v" is "integer?"
    }

Tuple type
----------

Examples:

-  ``(integer)`` - one value
-  ``(integer, text)`` - two values
-  ``(integer, (text, boolean))`` - nested tuple
-  ``(x: integer, y: integer)`` - named fields (can be accessed as
   ``A.x``, ``A.y``)

Tuple types are compatible only if names and types of fields are the
same:

-  ``(x:integer, y:integer)`` and ``(a:integer,b:integer)`` are not compatible.
-  ``(x:integer, y:integer)`` and ``(integer,integer)`` are not compatible.

Collection types
----------------

Collection types are:

-  ``list<T>`` - an ordered list
-  ``set<T>`` - an unordered set, contains no duplicates
-  ``map<K,V>`` - a key-value map

Collection types are mutable, elements can be added or removed dynamically.

Only a non-mutable type can be used as a ``map`` key or a ``set`` element.

Following types are mutable:

-  Collection types (``list``, ``set``, ``map``) - always.
-  Nullable type - only if the underlying type is mutable.
-  Record type - if the record has a mutable field, or a field of a mutable type.
-  Tuple - if a type of an element is mutable.

decimal
-------------

A real number of up to 20 digits after the decimal point and up to 131072 digits before the decimal point.

It is not a normal floating-point type found in many other languages (like ``float`` and ``double`` in
C/C++/Java):

- ``decimal`` type is accurate when working with numbers within its range. For example, in Java and Javascript,
  expressions ``1E+20 + 1 - 1E+20`` and ``1.0 - 0.1 - 0.1 - 0.1`` return an inaccurate result, while ``decimal`` result
  is accurate.
- Numbers are stored in a decimal form, not in a binary form, so conversions to and from a string are lossless (except when
  rounding occurs if there are more than 20 digits after the point).
- Floating-point types allow to store much smaller numbers, like ``1E-300``; ``decimal`` can only store ``1E-20``,
  but not a smaller nonzero number.
- ``decimal`` operations are way slower (10 times and more).
- Floating-point types have fixed size (8 bytes for ``double``), while ``decimal`` has a variable size and needs a lot of
  space for large numbers (~120B for ``1E+300 - 1`` or ~54KiB for ``1E+131071 - 1``).

In the code one can use decimal literals:

::

    123.456
    0.123
    .456
    33E+10
    55.77e-5

Such numbers have ``decimal`` type. Simple numbers without a decimal point and exponent, like 12345, have ``integer``
type.

Common operations:

- Conversions: functions ``decimal(text)``, ``decimal(integer)``, ``integer(decimal)``, ``decimal.to_integer()``.
- Arithmetic: ``+``, ``-``, ``*``, ``/``, ``%``.
- Rounding: ``decimal.ceil()``, ``decimal.floor()``, ``decimal.round()``.

See the `Library <library.html>`_ page for the full list.

Some features:

- All decimal numbers (results of decimal operations) are implicitly rounded to 20 decimal places. For instance,
  ``decimal('1E-20')`` returns a non-zero, while ``decimal('1E-21')`` returns a zero value.
- Operations on decimal numbers may be considerably slower than integer operations (at least 10 times slower for
  same integer numbers).
- Large decimal numbers may require a lot of space: ~0.41 bytes per decimal digit (~54KiB for 1E+131071) in memory and
  ~0.5 bytes per digit in a database.
- Internally, the type ``java.lang.BigDecimal`` is used in the interpreter, and ``NUMERIC`` in SQL.


gtv
--------

``gtv`` is a type used to repsesent encoded arguments and results of remote operation and query calls.
It may be a simple value (integer, string, byte array), an array of values or a string-keyed dictionary.

Some Rell types are not Gtv-compatible. Values of such types cannot be converted to/from ``gtv``, and the types
cannot be used as types of operation/query parameters or result.

Rules of Gtv-compatibility:

- ``range`` is not Gtv-compatible
- a complex type is not Gtv-compatible if a type of its component is not Gtv-compatible

Virtual types
-------------

Type ``virtual<T>`` can be used only with following types ``T``:

- ``list<*>``
- ``set<*>``
- ``map<text, *>``
- ``record``
- tuple

Additionally, types of all internal elements of ``T`` must satisfy following constraints:

- must be Gtv-compatible
- for a ``map`` type, the key type must be ``text`` (i. e. ``map<text, *>``)

Operations available for all virtual types:

- member access: ``[]`` for ``list`` and ``map``, ``.name`` for ``record`` and tuple
- ``.to_full(): T`` - converts the virtual value to the original value, if the value is full
  (all internal elements are present), otherwise throws an exception
- ``.hash(): byte_array`` - returns the hash of the value, which is the same as the hash of the
  original value.
- ``virtual<T>.from_gtv(gtv): virtual<T>`` - decodes a virtual value from a Gtv.

Features of ``virtual<T>``:

- it is immutable
- reading a member of type ``list<*>``, ``map<*,*>``, ``record`` or tuple returns a value of
  the corresponding virtual type, not of the actual member type
- cannot be converted to Gtv, so cannot be used as a return type of a ``query``

Example:

::

    record rec { t: text; s: integer; }

    operation op(recs: virtual<list<rec>>) {
        for (rec in recs) {                 // type of "rec" is "virtual<rec>", not "rec"
            val full = rec.to_full();       // type of "full" is "rec", fails if the value is not full
            print(full.t);
        }
    }

Subtypes
--------

If type ``B`` is a subtype of type ``A``, a value of type ``B`` can be
assigned to a variable of type ``A`` (or passed as a parameter of type
``A``).

-  ``T`` is a subtype of ``T?``.
-  ``null`` is a subtype of ``T?``.
-  ``(T,P)`` is a subtype of ``(T?,P?)``, ``(T?,P)`` and ``(T,P?)``.

--------------

Module definitions
==================

Class
-----

Instances of a class in Rell are stored in a database, not in memory. They have to be created and deleted explicitly
using Rell ``create`` and ``delete`` expressions. An in-memory equivalent of a class in Rell is a record.

A variable of a class type holds an ID (primary key) of the corresponding database record, but not its attribute values.

::

    class company {
        name: text;
        address: text;
    }

    class user {
        first_name: text;
        last_name: text;
        year_of_birth: integer;
        mutable salary: integer;
    }

If attribute type is not specified, it will be the same as attribute name:

::

    class user {
        name;       // built-in type "name"
        company;    // user-defined type "company" (error if no such type)
    }

Attributes may have default values:

::

    class user {
        home_city: text = 'New York';
    }

An ID (database primary key) of a class object can be accessed via the ``rowid`` implicit attribute (of type ``rowid``):

::

    val u = user @ { .name == 'Bob' };
    print(u.rowid);

    val alice_id = user @ { .name == 'Alice' } ( .rowid );
    print(alice_id);

Keys and Indices
~~~~~~~~~~~~~~~~

Classes can have ``key`` and ``index`` clauses:

::

    class user {
        name: text;
        address: text;
        key name;
        index address;
    }

Keys and indices may have multiple attributes:

::

    class user {
        first_name: text;
        last_name: text;
        key first_name, last_name;
    }

Attribute definitions can be combined with ``key`` or ``index`` clauses,
but such definition has restrictions (e. g. cannot specify ``mutable``):

::

    class user {
        key first_name: text, last_name: text;
        index address: text;
    }

Class annotations
~~~~~~~~~~~~~~~~~

::

    @log class user {
        name: text;
    }

The ``@log`` annotation has following effects:

- Special attribute ``transaction`` of type ``transaction`` is added to the class.
- When an object is created, ``transaction`` is set to the result of ``op_context.transaction`` (current transaction).
- Class cannot have mutable attributes.
- Objects cannot be deleted.

Object
------

Object is similar to class, but there can be only one instance of an object:

::

    object event_stats {
        mutable event_count: integer = 0;
        mutable last_event: text = 'n/a';
    }

Reading object attributes:

::

    query get_event_count() = event_stats.event_count;

Modifying an object:

::

    operation process_event(event: text) {
        update event_stats ( event_count += 1, last_event = event );
    }

Features of objects:

- Like classes, objects are stored in a database.
- Objects are initialized automatically during blockchain initialization.
- Cannot create or delete an object from code.
- Attributes of an object must have default values.

Record
------

A record is similar to a class, but its instances exist in memory, not in a database.

::

    record user {
        name: text;
        address: text;
        mutable balance: integer = 0;
    }

Features of records:

- Attributes are immutable by default, and only mutable when declared with ``mutable`` keyword.
- Attributes can have
- An attribute may have a default value, which is used if the attribute is not specified during construction.
- Records are deleted from memory implicitly by a garbage collector.

Creating record values:

::

    val u = user(name = 'Bob', address = 'New York');

Same rules as for the ``create`` expression apply: no need to specify attribute name if it can be resolved implicitly
by name or type:

::

    val name = 'Bob';
    val address = 'New York';
    val u = user(name, address);
    val u2 = user(address, name); // Order does not matter - same record object is created.

Record attributes can be accessed using operator ``.``:

::

    print(u.name, u.address);

Safe-access operator ``?.`` can be used to read or modify attributes of a nullable record:

::

    val u: user? = find_user('Bob');
    u?.balance += 100;        // no-op if 'u' is null

Enum
-----

Enum declaration:

::

    enum currency {
        USD,
        EUR,
        GBP
    }

Values are stored in a database as integers. Each constant has a numeric value equal to its position in the enum
(the first value is 0).

Usage:

::

    var c: currency;
    c = currency.USD;

Enum-specific functions and properties:

::

    val cs: list<currency> = currency.values() // Returns all values (in the order in which they are declared)

    val eur = currency.value('EUR') // Finds enum value by name
    val gbp = currency.value(2) // Finds enum value by index

    val usd_str: text = currency.USD.name // Returns 'USD'
    val usd_value: integer = currency.USD.value // Returns 0.

Query
-----

-  Cannot modify the data in the database (compile-time check).
-  Must return a value.
-  If return type is not explicitly specified, it is implicitly deducted.
-  Parameter types and return type must be Gtv-compatible.

Short form:

::

    query q(x: integer): integer = x * x;

Full form:

::

    query q(x: integer): integer {
        return x * x;
    }

Operation
---------

-  Can modify the data in the database.
-  Does not return a value.
-  Parameter types must be Gtv-compatible.

::

    operation create_user(name: text) {
        create user(name = name);
    }

Function
--------

-  Can return nothing or a value.
-  Can modify the data in the database when called from an operation (run-time check).
-  Can be called from queries, operations or functions.
-  If return type is not specified explicitly, it is ``unit`` (no return value).

Short form:

::

    function f(x: integer): integer = x * x;

Full form:

::

    function f(x: integer): integer {
        return x * x;
    }

When return type is not specified, it is considered ``unit``:

::

    function f(x: integer) {
        print(x);
    }

Namespace
---------

Definitions can be put in a namespace:

::

    namespace foo {
        class user {
            name;
            country;
        }

        record point {
            x: integer;
            y: integer;
        }

        enum country {
            USA,
            DE,
            FR
        }
    }

    query get_users_by_country(c: foo.country) = foo.user @* { .country == c };

Features of namespaces:

- No need to specify a full name within a namespace, i. e. can use ``country`` under namespace ``foo`` directly, not as
  ``foo.country``.
- Names of tables for classes and objects defined in a namespace contain the full name, e. g. the table for class
  ``foo.user`` will be named ``c0.foo.user``.
- It is allowed to define namespace with same name multiple times with different inner definitions.

Anonymous namespace:

::

    namespace {
        // some definitions
    }

Can be used to apply an annotation to a set of definitions:

::

    @mount('foo.bar')
    namespace {
        class user {}
        class company {}
    }

External
--------

External blocks are used to access classes defined in other blockchains:

::

    external 'foo' {
        @log class user {
            name;
        }
    }

    query get_all_users() = user @* {};

In this example, ``'foo'`` is the name of an external blockchain. To be used in an external block, a blockchain
must be defined in the blockchain configuration (``dependencies`` node).

Every blockchain has its ``chain_id``, which is included in table names for classes and objects of that chain. If the
blockchain ``'foo'`` has ``chain_id`` = 123, the table for the class ``user`` will be called ``c123.user``.

Other features:

- External classes must be annotated with the ``@log`` annotation. This implies that those classes cannot have mutable
  attributes.
- Objects of external classes cannot be created or deleted.
- Only classes and namespaces are allowed inside of an external block.
- Can have only one external block for a specific blockchain name.
- When selecting objects of an external class (using at-expression), an implicit block height filter is applied, so
  the active blockchain can see only those blocks of the external blockchain whose height is lower than a specific value.
- Every blockchain stores the structure of its classes in meta-information tables. When a blockchain is started,
  the meta-information of all involved external blockchains is verified to make sure that all declared external classes
  exist and have declared attributes.

Transactions and blocks
~~~~~~~~~~~~~~~~~~~~~~~

To access blocks and transactions of an external blockchian, a special syntax is used:

::

    namespace foo {
        external 'foo' {
            class transaction;
            class block;
        }
    }

   function get_foo_transactions(): list<foo.transaction> = foo.transaction @* {};
   function get_foo_blocks(): list<foo.block> = foo.block @* {};

- External block must be put in a namespace in order to prevent name conflict, since classes ``transaction`` and
  ``block`` are already defined in the top-level scope (they represent transactions and blocks of the active blockchain).
- Namespace name can be arbitrary.
- External and non-external transactions/blocks are distinct, incompatible types.
- When selecting external transactions or blocks, an implicit height filter is applied (like for external classes).

Mount names
-----------

Classes, objects, operations and queries have mount names:

- for classes and objects, those names are the SQL table names where the data is stored
- for operations and queries, a mount name is used to invoke an operation or a query from the outside

By default, a mount name is defined by a fully-qualified name of a definition:

::

    namespace foo {
        namespace bar {
            class user {}
        }
    }

The mount name for the class ``user`` is ``foo.bar.user``.

To specify a custom mount name, ``@mount`` annotation is used:

::

    @mount('foo.bar.user')
    class user {}

The ``@mount`` annotation can be specified for classes, objects, operations and queries.

In addition, it can be specified for a namespace:

::

    @mount('foo.bar')
    namespace ns {
        class user {}
    }

or a module:

::

    @mount('foo.bar')
    module;

    class user {}

In both cases, the mount name of ``user`` is ``foo.bar.user``.

A mount name can be relative to the context mount name. For example, when defined in a namespace

::

    @mount('a.b.c')
    namespace ns {
        class user {}
    }

class ``user`` will have following mount names when annotated with ``@mount``:

- ``@mount('.d.user')`` -> ``a.b.c.d.user``
- ``@mount('^.user')`` -> ``a.b.user``
- ``@mount('^^.x.user')`` -> ``a.x.user``

Special character ``.`` appends names to the context mount name, and ``^`` removes the last part from the context
mount name.

A mount name can end with ``.``, in that case the name of the definition is appended to the mount name:

- ``@mount('foo.') class user {}`` gives mount name ``foo.user``, while
- ``@mount('foo') class user {}`` gives mount name ``foo``.

--------------

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

Tuple:

-  ``(1, 2, 3)`` - three values
-  ``(123, 'Hello')`` - two values
-  ``(456,)`` - one value (because of the comma)
-  ``(789)`` - not a tuple (no comma)
-  ``(a = 123, b = 'Hello')`` - tuple with named fields

List:

::

    [ 1, 2, 3, 4, 5 ]

Map:

::

    [ 'Bob' : 123, 'Alice' : 456 ]

Operators
---------

Special:
~~~~~~~~

-  ``.`` - member access: ``user.name``, ``s.sub(5, 10)``
-  ``()`` - function call: ``print('Hello')``, ``value.to_text()``
-  ``[]`` - element access: ``values[i]``

Comparison:
~~~~~~~~~~~

-  ``==``
-  ``!=``
-  ``===``
-  ``!==``
-  ``<``
-  ``>``
-  ``<=``
-  ``>=``

Operators ``==`` and ``!=`` compare values. For complex types (collections, tuples, records) they compare member
values, recursively. For ``class`` object values only object IDs are compared.

Operators ``===`` and ``!==`` compare references, not values. They can be used only on types:
tuple, ``record``, ``list``, ``set``, ``map``, ``gtv``, ``range``.

Example:

::

    val x = [1, 2, 3];
    val y = list(x);
    print(x == y);      // true - values are equal
    print(x === y);     // false - two different objects

Arithmetical:
~~~~~~~~~~~~~

-  ``+``
-  ``-``
-  ``*``
-  ``/``
-  ``%``
-  ``++``
-  ``--``

Logical:
~~~~~~~~

-  ``and``
-  ``or``
-  ``not``

If:
~~~~~~~~~~~

Operator ``if`` is used for conditional evaluation:

::

    val max = if (a >= b) a else b;
    return max;

Other:
~~~~~~

-  ``in`` - check if an element is in a range/set/map

-------------

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

Tuple unpacking
~~~~~~~~~~~~~~~

::

    val t = (123, 'Hello');
    val (n, s) = t;           // n = 123, s = 'Hello'

Works with arbitrarily nested tuples:

::

    val (n, (p, (x, y), q)) = calculate();

Special symbol ``_`` is used to ignore a tuple element:

::

    val (_, s) = (123, 'Hello'); // s = 'Hello'

Variable types can be specified explicitly:

::

    val (n: integer, s: text) = (123, 'Hello');

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

Tuple unpacking can be used:

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

Miscellaneous
=============

Comments
--------

Single-line comment:

::

    print("Hello"); // Some comment

Multiline comment:

::

    print("Hello"/*, "World"*/);
    /*
    print("Bye");
    */

--------------

*Rell v0.9.1*