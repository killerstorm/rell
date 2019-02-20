=========================
General Language Features
=========================

--------------

Types
=====

Simple types:
-------------

-  ``boolean``
-  ``integer``
-  ``text``
-  ``byte_array``
-  ``json``
-  ``unit`` (no value; cannot be used explicitly)
-  ``null`` (type of ``null`` expression; cannot be used explicitly)

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
-  ``GTXValue`` - used to encode parameters and results of operations and queries

Nullable type
-------------

-  Class attributes cannot be nullable.
-  Can be used with almost any type (except nullable, ``unit``,
   ``null``).
-  Nullable nullable (``T??`` is not allowed).
-  Normal operations of the underlying type cannot be applied directly.
-  Supports ``?:``, ``?.`` and ``!!`` operators (like in Kotlin).

Compatibility with other types:

-  Can assign a value of type ``T`` to a variable of type ``T?``, but
   not the other way round.
-  Can assign ``null`` to a variable of type ``T?``, but not to a
   variable of type ``T``.
-  Can assign a value of type ``(T)`` (tuple) to a variable of type
   ``(T?)``.
-  Cannot assign a value of type ``list<T>`` to a variable of type
   ``list<T?>``.

Allowed operations:

-  Null comparison: ``x == null``, ``x != null``.
-  ``?:`` - Elvis operator: ``x ?: y`` means ``x`` if ``x`` is not
   ``null``, otherwise ``y``.
-  ``?.`` - safe access: ``x?.y`` results in ``x.y`` if ``x`` is not
   ``null`` and ``null`` otherwise.
-  Operator ``?.`` can be used with function calls, e. g.
   ``x?.upperCase()``.
-  ``!!`` - null check operator: ``x!!`` returns value of ``x`` if ``x``
   is not ``null``, otherwise throws an exception.
-  ``require(x)``, ``requireNotEmpty(x)``: throws an exception if ``x``
   is ``null``, otherwise returns value of ``x``.

Examples:

::

   val x: integer? = 123;
   val y = x;            // type of "y" is "integer?"
   val z = y!!;          // type of "z" is "integer"
   val p = require(y);   // type of "p" is "integer"

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

-  ``(x:integer, y:integer)`` and ``(a:integer,b:integer)`` are not
   compatible.
-  ``(x:integer, y:integer)`` and ``(integer,integer)`` are not
   compatible.

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

GTXValue
--------

``GTXValue`` is a type used to repsesent encoded arguments and results of remote operation and query calls.
It may be a simple value (integer, string, byte array), an array of values or a string-keyed map.

Some Rell types are not GTX-compatible. Values of such types cannot be converted to/from ``GTXValue``, and the types
cannot be used as types of operation/query parameters or result.

Rules of GTX-compatibility:

- a ``map<K,V>`` is GTX-compatible only if ``K`` is ``text``.
- ``range`` is not GTX-compatible
- a complex type is not GTX-compatible if a type of its component is not GTX-compatible

For queries, a type must be pretty-GTX-compatible. Rules are:

- a type must be GTX-compatible
- for tuples: either all fields have names, or no field has a name
- component types must be pretty-GTX-compatible as well

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

::

   class company {
       name: text;
       address: text;
   }

   class user {
       firstName: text;
       lastName: text;
       yearOfBirth: integer;
       mutable salary: integer;
   }

If attribute type is not specified, it will be the same as attribute
name:

::

   class user {
       name;       // built-in type "name"
       company;    // user-defined type "company" (error if no such type)
   }

Attributes may have default values:

::

   class user {
       homeCity: text = 'New York';
   }

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
       firstName: text;
       lastName: text;
       key firstName, lastName;
   }

Attribute definitions can be combined with ``key`` or ``index`` clauses,
but such definition has restrictions (e. g. cannot specify ``mutable``):

::

   class user {
       key firstName: text, lastName: text;
       index address: text;
   }

Class annotations
~~~~~~~~~~~~~~~~~

::

   class user (log) {
       name: text;
   }

The ``log`` annotation has following effects:

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

Record declaration:

::

   record user {
       name: text;
       address: text;
       mutable balance: integer = 0;
   }

- Attributes are immutable by default, and only mutable when declared with ``mutable`` keyword.
- An attribute may have a default value, which is used if the attribute is not specified during construction.

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

   val u: user? = findUser('Bob');
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

   val usdStr: text = currency.USD.name // Returns 'USD'
   val usdValue: integer = currency.USD.value // Return 0.

Query
-----

-  Cannot modify the data in the database (compile-time check).
-  Must return a value.
-  If return type is not explicitly specified, it is implicitly
   deducted.
-  Parameter types and return type must be pretty-GTX-compatible.

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
-  Parameter types must be GTX-compatible.

::

   operation createUser(name: text) {
       create user(name = name);
   }

Function
--------

-  Can return nothing or a value.
-  Can modify the data in the database when called from an operation
   (run-time check).
-  Can be called from queries, operations or functions.
-  If return type is not specified explicitly, it is ``unit`` (no return
   value).

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
-  ``()`` - function call: ``print('Hello')``, ``value.str()``
-  ``[]`` - element access: ``values[i]``

Null handling:
~~~~~~~~~~~~~~

-  ``?:`` - Elvis operator: ``x ?: y`` returns ``x`` if ``x`` is not
   ``null``, otherwise returns ``y``.
-  ``?.`` - safe access operator: ``x?.y`` returns ``x.y`` if ``x`` is
   not ``null``, otherwise returns ``null``; similarly, ``x?.y()``
   returns either ``x.y()`` or ``null``.
-  ``!!`` - null check: ``x!!`` returns ``x`` if ``x`` is not ``null``,
   otherwise throws an exception.
   
Examples:

::

   val x: integer? = 123;
   val y = x;              // type of "y" is "integer?"

   val a = y ?: 456;       // type of "a" is "integer"
   val b = y ?: null;      // type of "b" is "integer?"

   val p = y!!;            // type of "p" is "integer"
   val q = y?.hex();       // type of "q" is "text?"

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
tuple, ``record``, ``list``, ``set``, ``map``, ``GTXValue``, ``range``.

Example:

::

   val x = [1, 2, 3];
   val y = list(x);
   print(x == y);      // true - values are equal
   print(x === y);     // false - two different objects

If:
~~~~~~~~~~~

Operator ``if`` is used for conditional evaluation:

::

   val max = if (a >= b) a else b;
   return max;

Arithmetical:
~~~~~~~~~~~~~

-  ``+``
-  ``-``
-  ``*``
-  ``/``
-  ``%``

Logical:
~~~~~~~~

-  ``and``
-  ``or``
-  ``not``

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
