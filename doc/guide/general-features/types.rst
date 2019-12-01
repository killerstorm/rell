Types
=====

Simple types
------------

boolean
~~~~~~~

.. code-block:: rell

  val using_rell = true;
  if (using_rell) print("Awesome!");

integer
~~~~~~~

.. code-block:: rell

  val user_age : integer = 26;

decimal
~~~~~~~
A real number of up to 20 digits after the decimal point and up to 131072 digits before the decimal point.

.. code-block:: rell

  val approx_pi : decimal = 3.14159;
  val scientific_value : decimal = 55.77e-5;    

text
~~~~
Textual value. Same as ``string`` type in some other languages.

.. code-block:: rell

  val placeholder = "Lorem ipsum donor sit amet";
  print(placeholder.size());  // 26
  print(placeholder.empty()); // false

byte_array
~~~~~~~~~~

.. code-block:: rell

  val user_pubkey : byte_array = x"0373599a61cc6b3bc02a78c34313e1737ae9cfd56b9bb24360b437d469efdf3b15";
  print(user_pubkey.to_base64()); //A3NZmmHMazvAKnjDQxPhc3rpz9Vrm7JDYLQ31Gnv3zsV

rowid
~~~~~
Primary key of a database record, 64-bit integer, supports only comparison operations

json
~~~~
Stored in Postgres as ``JSON`` type, and can be parsed to ``text``;

.. code-block:: rell

  val json_text = '{ "name": "Alice" }';
  val json_value: json = json(json_text);
  print(json_value);

unit
~~~~
No value; cannot be used explicitly. Equivalent to `unit` type in Kotlin.

null
~~~~
Type of ``null`` expression; cannot be used explicitly

Simple type aliases:

-  ``pubkey`` = ``byte_array``
-  ``name`` = ``text``
-  ``timestamp`` = ``integer``
-  ``tuid`` = ``text``

--------------

Complex types
-------------

entity
~~~~~~

.. code-block:: rell

  entity user {
    key pubkey;
    index name;
  }

struct
~~~~~~
A struct is similar to an entity, but its instances exist in memory, not in a database.

.. code-block:: rell

  struct user {
    name: text;
    address: text;
    mutable balance: integer = 0;
  }


T? - nullable type
~~~~~~~~~~~~~~~~~~

.. code-block:: rell

  val nonexistent_user = user @? { .name == "Nonexistent Name" };
  require_not_empty(nonexistent_user); // Throws exception because user doesn't exists

tuple
~~~~~
Examples:

-  ``val single_number : (integer) = (16,)`` - one value
-  ``val user_tuple: (integer, text) = (26, "Bob")`` - two values
-  ``val named_tuple : (x: integer, y: integer) = (32, 26)`` - named fields (can be accessed as ``named_tuple.x``, ``named_tuple.y``)
-  ``(integer, (text, boolean))`` - nested tuple

Tuple types are compatible only if names and types of fields are the
same:

-  ``(x:integer, y:integer)`` and ``(a:integer,b:integer)`` are not compatible.
-  ``(x:integer, y:integer)`` and ``(integer,integer)`` are not compatible.


list<T>
~~~~~~~
Ordered collection type. Accept duplication.

.. code-block:: rell

  var messages = message @* { } ( @sort timestamp = .timestamp );
  messages.add(new_message);

set<T>
~~~~~~
Unordered collection type. Does *not* accept duplication.

.. code-block:: rell

  var my_classmates = set<user>();
  my_classmates.add(alice); // return true
  my_classmates.add(alice); // return false

map<K,V>
~~~~~~~~
A key/value pair collection type.

.. code-block:: rell

  var dictionary = map<text, text>();
  dictionary["Mordor"] = "A place where one does not simply walk into";

range
~~~~~
Can be used in ``for`` statement:

.. code-block:: rell

  for(count in range(10)){
    print(count); // prints out 0 to 9
  }

gtv
~~~
A type used to repsesent encoded arguments and results of remote operation and query calls.
It may be a simple value (integer, string, byte array), an array of values or a string-keyed dictionary.

Some Rell types are not Gtv-compatible. Values of such types cannot be converted to/from ``gtv``, and the types
cannot be used as types of operation/query parameters or result.

Rules of Gtv-compatibility:

- ``range`` is not Gtv-compatible
- a complex type is not Gtv-compatible if a type of its component is not Gtv-compatible


virtual<T>
~~~~~~~~~~
A reduced data structure with Merkle tree

-----------------

More on types
-------------

T? - nullable type
~~~~~~~~~~~~~~~~~~

-  Entity attributes cannot be nullable.
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

.. code-block:: rell

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


Collection types
~~~~~~~~~~~~~~~~

Collection types are:

-  ``list<T>`` - an ordered list
-  ``set<T>`` - an unordered set, contains no duplicates
-  ``map<K,V>`` - a key-value map

Collection types are mutable, elements can be added or removed dynamically.

Only a non-mutable type can be used as a ``map`` key or a ``set`` element.

Following types are mutable:

-  Collection types (``list``, ``set``, ``map``) - always.
-  Nullable type - only if the underlying type is mutable.
-  Struct type - if the struct has a mutable field, or a field of a mutable type.
-  Tuple - if a type of an element is mutable.

Creating collections:

::
    // list
    val l1 = [ 1, 2, 3, 4, 5 ];
    val l2 = list<integer>();

    // set
    val s = set<integer>();

    // map
    val m1 = [ 'Bob' : 123, 'Alice' : 456 ];
    val m2 = map<text, integer>();
    

decimal
~~~~~~~

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

.. code-block:: rell

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

tuples
~~~~~~

-  ``(1, 2, 3)`` - three values
-  ``(123, 'Hello')`` - two values
-  ``(456,)`` - one value (because of the comma)
-  ``(789)`` - *not* a tuple (no comma)
-  ``(a = 123, b = 'Hello')`` - tuple with named fields

Reading tuple fields:

- ``t[0]``, ``t[1]`` - by index
- ``t.a``, ``t.b`` - by name (for named fields)

Unpacking tuples:

.. code-block:: rell

    val t = (123, 'Hello');
    val (n, s) = t;           // n = 123, s = 'Hello'

Works for arbitrarily nested tuples:

.. code-block:: rell

    val (n, (p, (x, y), q)) = calculate();

Special symbol ``_`` is used to ignore a tuple element:

.. code-block:: rell

    val (_, s) = (123, 'Hello'); // s = 'Hello'

Variable types can be specified explicitly:

::

    val (n: integer, s: text) = (123, 'Hello');

Unpacking can be used in a loop:

::

    val l: list<(integer, text)> = get_tuples();
    for ((x, y) in l) {
        print(x, y);
    }

Virtual types
~~~~~~~~~~~~~

Type ``virtual<T>`` can be used only with following types ``T``:

- ``list<*>``
- ``set<*>``
- ``map<text, *>``
- ``struct``
- tuple

Additionally, types of all internal elements of ``T`` must satisfy following constraints:

- must be Gtv-compatible
- for a ``map`` type, the key type must be ``text`` (i. e. ``map<text, *>``)

Operations available for all virtual types:

- member access: ``[]`` for ``list`` and ``map``, ``.name`` for ``struct`` and tuple
- ``.to_full(): T`` - converts the virtual value to the original value, if the value is full
  (all internal elements are present), otherwise throws an exception
- ``.hash(): byte_array`` - returns the hash of the value, which is the same as the hash of the
  original value.
- ``virtual<T>.from_gtv(gtv): virtual<T>`` - decodes a virtual value from a Gtv.

Features of ``virtual<T>``:

- it is immutable
- reading a member of type ``list<*>``, ``map<*,*>``, ``struct`` or tuple returns a value of
  the corresponding virtual type, not of the actual member type
- cannot be converted to Gtv, so cannot be used as a return type of a ``query``

Example:

::

    struct rec { t: text; s: integer; }

    operation op(recs: virtual<list<rec>>) {
        for (rec in recs) {                 // type of "rec" is "virtual<rec>", not "rec"
            val full = rec.to_full();       // type of "full" is "rec", fails if the value is not full
            print(full.t);
        }
    }

----------

Subtypes
--------

If type ``B`` is a subtype of type ``A``, a value of type ``B`` can be
assigned to a variable of type ``A`` (or passed as a parameter of type
``A``).

-  ``T`` is a subtype of ``T?``.
-  ``null`` is a subtype of ``T?``.
-  ``(T,P)`` is a subtype of ``(T?,P?)``, ``(T?,P)`` and ``(T,P?)``.

--------------

*Rell v0.10.0*