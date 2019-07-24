=======
Library
=======

System classes
==============

::

    class block {
        block_height: integer;
        block_rid: byte_array;
        timestamp;
    }

    class transaction {
        tx_rid: byte_array;
        tx_hash: byte_array;
        tx_data: byte_array;
        block;
    }

It is not possible to create, modify or delete objects of those classes in code.

--------------

chain_context
-------------

``chain_context.args: module_args`` - module arguments specified in ``raw_config`` under path ``gtx.rellModuleArgs``.
The type is ``module_args``, which must be a user-defined record. If no ``module_args`` record is defined in the module,
the ``args`` field cannot be accessed.

Example of ``module_args``:

::

    record module_args {
        s: text;
        n: integer;
    }

Corresponding module configuration:

::

    {
        "gtx": {
            "rell": {
                "moduleArgs": {
                    "s": "Hello",
                    "n": 123
                }
            }
        }
    }

Code that reads ``module_args``:

::

    function f() {
        print(chain_context.args.s);
        print(chain_context.args.n);
    }

``chain_context.blockchain_rid: byte_array`` - blockchain RID

``chain_context.raw_config: gtv`` - blockchain configuration object, e. g. ``{"gtx":{"rell":{"mainFile":"main.rell"}}}``

op_context
----------

System namespace ``op_context`` can be used only in an operation or a function called from an operation, but not in a query.

``op_context.block_height: integer`` - the height of the block currently being built
(equivalent of ``op_context.transaction.block.block_height``).

``op_context.last_block_time: integer`` - the timestamp of the last block, in milliseconds
(like ``System.currentTimeMillis()`` in Java). Returns ``-1`` if there is no last block (the block currently being built
is the first block).

``op_context.transaction: transaction`` - the transaction currently being built.

--------------

Functions
================

Global Functions
----------------

``abs(integer): integer`` - absolute value

``exists(T?): boolean`` - returns ``true`` if the argument is ``null`` and ``false`` otherwise

``is_signer(byte_array): boolean`` - returns ``true`` if a byte array is
in the list of signers of current operation

``log(...)`` - print a message to the log (same usage as ``print``)

``max(integer, integer): integer`` - maximum of two values

``min(integer, integer): integer`` - minimum of two values

``print(...)`` - print a message to STDOUT:

-  ``print()`` - prints an empty line
-  ``print('Hello', 123)`` - prints ``"Hello 123"``

``verify_signature(message: byte_array, pubkey: pubkey, signature: byte_array): boolean`` - returns ``true``
if the given signature is a result of signing the message with a private key corresponding to the given public key

--------------

Require functions
-----------------

For checking a boolean condition:

``require(boolean[, text])`` - throws an exception if the argument is ``false``

For checking for ``null``:

``require(T?[, text]): T`` - throws an exception if the argument is
``null``, otherwise returns the argument

``require_not_empty(T?[, text]): T`` - same as the previous one

For checking for an empty collection:

``require_not_empty(list<T>[, text]): list<T>`` - throws an exception if
the argument is an empty collection, otherwise returns the argument

``require_not_empty(set<T>[, text]): set<T>`` - same as the previous

``require_not_empty(map<K,V>[, text]): map<K,V>`` - same as the previous

When passing a nullable collection to ``require_not_empty``, it throws an
exception if the argument is either ``null`` or an empty collection.

Examples:

::

    val x: integer? = calculate();
    val y = require(x, "x is null"); // type of "y" is "integer", not "integer?"

    val p: list<integer> = get_list();
    require_not_empty(p, "List is empty");

    val q: list<integer>? = try_to_get_list();
    require(q);           // fails if q is null
    require_not_empty(q); // fails if q is null or an empty list

--------------

integer
-------

``integer.MIN_VALUE`` = minimum value (``-2^63``)

``integer.MAX_VALUE`` = maximum value (``2^63-1``)

``integer(s: text, radix: integer = 10)`` - parse a signed string representation of an integer, fail if invalid

``integer.from_text(s: text, radix: integer = 10): integer`` - same as ``integer(text, integer)``

``integer.from_hex(text): integer`` - parse an unsigned HEX representation

``.to_text(radix: integer = 10)`` - convert to a signed string representation

``.to_hex(): text`` - convert to an unsigned HEX representation

``.signum(): integer`` - returns ``-1``, ``0`` or ``1`` depending on the sign

--------------

text
----

``text.from_bytes(byte_array, ignore_invalid: boolean = false)`` - if ``ignore_invalid`` is ``false``,
throws an exception when the byte array is not a valid UTF-8 encoded string, otherwise replaces invalid characters
with a placeholder.

``.empty(): boolean``

``.size(): integer``

``.compare_to(text): integer`` - as in Java

``.starts_with(text): boolean``

``.ends_with(text): boolean``

``.contains(text): boolean`` - ``true`` if contains the given substring

``.index_of(text, start: integer = 0): integer`` - returns ``-1`` if
substring is not found (as in Java)

``.last_index_of(text[, start: integer]): integer`` - returns ``-1`` if
substring is not found (as in Java)

``.sub(start: integer[, end: integer]): text`` - get a substring
(start-inclusive, end-exclusive)

``.replace(old: text, new: text)``

``.upper_case(): text``

``.lower_case(): text``

``.split(text): list<text>`` - strictly split by a separator (not a regular expression)

``.trim(): text`` - remove leading and trailing whitespace

``.matches(text): boolean`` - ``true`` if matches a regular expression

``.to_bytes(): byte_array`` - convert to a UTF-8 encoded byte array

``.char_at(integer): integer`` - get a 16-bit code of a character

``.format(...)`` - formats a string (as in Java):

-  ``'My name is <%s>'.format('Bob')`` - returns ``'My name is <Bob>'``

Special operators:

-  ``+`` : concatenation
-  ``[]`` : character access (returns single-character ``text``)

--------------

byte_array
----------

``byte_array(text)`` - creates a ``byte_array`` from a HEX string, e.g. ``'1234abcd'``, throws an exception if the
string is not a valid HEX sequence

``byte_array.from_hex(text): byte_array`` - same as ``byte_array(text)``

``byte_array.from_base64(text): byte_array`` - creates a ``byte_array`` from a Base64 string, throws an exception if
the string is invalid

``byte_array.from_list(list<integer>): byte_array`` - creates a ``byte_array`` from a list; values must be 0 - 255,
otherwise an exception is thrown

``.empty(): boolean``

``.size(): integer``

``.sub(start: integer[, end: integer]): byte_array`` - sub-array (start-inclusive, end-exclusive)

``.to_hex(): text`` - returns a HEX representation of the byte array, e.g. ``'1234abcd'``

``.to_base64(): text`` - returns a Base64 representation of the byte array

``.to_list(): list<integer>`` - list of values 0 - 255

Special operators:

-  ``+`` : concatenation
-  ``[]`` : element access

--------------

range
-----

``range(start: integer = 0, end: integer, step: integer = 1)`` -
start-inclusive, end-exclusive (as in Python):

-  ``range(10)`` - a range from 0 (inclusive) to 10 (exclusive)
-  ``range(5, 10)`` - from 5 to 10
-  ``range(5, 15, 4)`` - from 5 to 15 with step 4, i. e. ``[5, 9, 13]``
-  ``range(10, 5, -1)`` - produces ``[10, 9, 8, 7, 6]``
-  ``range(10, 5, -3)`` - produces ``[10, 7]``

Special operators:

-  ``in`` - returns ``true`` if the value is in the range (taking ``step`` into account)

--------------

list<T>
--------

Constructors:

``list<T>()`` - a new empty list

``list<T>(list<T>)`` - a copy of the given list (list of subtype is accepted as well)

``list<T>(set<T>)`` - a copy of the given set (set of subtype is accepted)

Methods:

``.add(T): boolean`` - adds an element to the end, always returns ``true``

``.add(pos: integer, T): boolean`` - inserts an element at a position, always returns ``true``

``.add_all(list<T>): boolean``

``.add_all(set<T>): boolean``

``.add_all(pos: integer, list<T>): boolean``

``.add_all(pos: integer, set<T>): boolean``

``.clear()``

``.contains(T): boolean``

``.contains_all(list<T>): boolean``

``.contains_all(set<T>): boolean``

``.empty(): boolean``

``.index_of(T): integer`` - returns ``-1`` if element is not found

``.remove(T): boolean`` - removes the first occurrence of the value, return ``true`` if found

``.remove_all(list<T>): boolean``

``.remove_all(set<T>): boolean``

``.remove_at(pos: integer): T`` - removes an element at a given position

``.size(): integer``

``._sort()`` - sorts this list, returns nothing (name is ``_sort``, because ``sort`` is a keyword in Rell)

``.sorted(): list<T>`` - returns a sorted copy of this list

``.to_text(): text`` - returns e. g. ``'[1, 2, 3, 4, 5]'``

``.sub(start: integer[, end: integer]): list<T>`` - returns a sub-list (start-inclusive, end-exclusive)

Special operators:

-  ``[]`` - element access (read/modify)
-  ``in`` - returns ``true`` if the value is in the list

--------------

virtual<list<T>>
----------------

``virtual<list<T>>.from_gtv(gtv): virtual<list<T>>`` - decodes a Gtv

``.empty(): boolean``

``.get(integer): virtual<T>`` - returns an element, same as ``[]``

``.hash(): byte_array``

``.size(): integer``

``.to_full(): list<T>`` - converts to the original value, fails if the value is not full

``.to_text(): text`` - returns a text representation

Special operators:

-  ``[]`` - element read, returns ``virtual<T>`` (or just ``T`` for simple types)
-  ``in`` - returns ``true`` if the given integer index is present in the virtual list

--------------

set<T>
-------

Constructors:

``set<T>()`` - a new empty set

``set<T>(set<T>)`` - a copy of the given set (set of subtype is accepted as well)

``set<T>(list<T>)`` - a copy of the given list (with duplicates removed)

Methods:

``.add(T): boolean`` - if the element is not in the set, adds it and returns ``true``

``.add_all(list<T>): boolean`` - adds all elements, returns ``true`` if at least one added

``.add_all(set<T>): boolean`` - adds all elements, returns ``true`` if at least one added

``.clear()``

``.contains(T): boolean``

``.contains_all(list<T>): boolean``

``.contains_all(set<T>): boolean``

``.empty(): boolean``

``.remove(T): boolean`` - removes the element, returns ``true`` if found

``.remove_all(list<T>): boolean`` - returns ``true`` if at least one removed

``.remove_all(set<T>): boolean`` - returns ``true`` if at least one removed

``.size(): integer``

``.sorted(): list<T>`` - returns a sorted copy of this set (as a list)

``.to_text(): text`` - returns e. g. ``'[1, 2, 3, 4, 5]'``

Special operators:

-  ``in`` - returns ``true`` if the value is in the set

--------------

virtual<set<T>>
----------------

``virtual<set<T>>.from_gtv(gtv): virtual<set<T>>`` - decodes a Gtv

``.empty(): boolean``

``.hash(): byte_array``

``.size(): integer``

``.to_full(): set<T>`` - converts to the original value, fails if the value is not full

``.to_text(): text`` - returns a text representation

Special operators:

-  ``in`` - returns ``true`` if the given value is present in the virtual set;
   the type of the operand is ``virtual<T>>`` (or just ``T`` for simple types)

--------------

map<K,V>
--------

Constructors:

``map<K,V>()`` - a new empty map

``map<K,V>(map<K,V>)`` - a copy of the given map (map of subtypes is accepted as well)

Methods:

``.clear()``

``.contains(K): boolean``

``.empty(): boolean``

``.get(K): V`` - get value by key (same as ``[]``)

``.put(K, V)`` - adds/replaces a key-value pair

``.keys(): set<K>`` - returns a copy of keys

``.put_all(map<K, V>)`` - adds/replaces all key-value pairs from the given map

``.remove(K): V`` - removes a key-value pair (fails if the key is not in the map)

``.size(): integer``

``.to_text(): text`` - returns e. g. ``'{x=123, y=456}'``

``.values(): list<V>`` - returns a copy of values

Special operators:

-  ``[]`` - get/set value by key
-  ``in`` - returns ``true`` if a key is in the map

--------------

virtual<map<K,V>>
------------------

``virtual<map<K,V>>.from_gtv(gtv): virtual<map<K,V>>`` - decodes a Gtv

``.contains(K): boolean`` - same as operator ``in``

``.empty(): boolean``

``.get(K): virtual<V>`` - same as operator ``[]``

``.hash(): byte_array``

``.keys(): set<K>`` - returns a copy of keys

``.size(): integer``

``.to_full(): map<K,V>`` - converts to the original value, fails if the value is not full

``.to_text(): text`` - returns a text representation

``.values(): list<virtual<V>>`` - returns a copy of values
(if ``V`` is a simple type, returns ``list<V>``)

Special operators:

-  ``[]`` - get value by key, fails if not found, returns ``virtual<V>`` (or just ``V`` for simple types)
-  ``in`` - returns ``true`` if a key is in the map

--------------

enum
------

Assuming ``T`` is an enum type.

``T.values(): list<T>`` - returns all values of the enum, in the order of declaration

``T.value(text): T`` - finds a value by name, throws en exception if not found

``T.value(integer): T`` - finds a value by index, throws an exception if not found

Enum value properties:

``.name: text`` - the name of the enum value

``.value: integer`` - the numeric value (index) associated with the enum value

--------------

gtv
--------

``gtv.from_json(text): gtv`` - decode a ``gtv`` from a JSON string

``gtv.from_json(json): gtv`` - decode a ``gtv`` from a ``json`` value

``gtv.from_bytes(byte_array): gtv`` - decode a ``gtv`` from a binary-encoded form

``.to_json(): json`` - convert to JSON

``.to_bytes(): byte_array`` - convert to bytes

``.hash(): byte_array`` - returns a cryptographic hash of the value

--------------

gtv-related functions
---------------------

Functions available for all Gtv-compatible types:

``T.from_gtv(gtv): T`` - decode from a ``gtv``

``T.from_gtv_pretty(gtv): T`` - decode from a pretty-encoded ``gtv``

``.to_gtv(): gtv`` - convert to a ``gtv``

``.to_gtv_pretty(): gtv`` - convert to a pretty ``gtv``

``.hash(): byte_array`` - returns a cryptographic hash of the value (same as ``.to_gtv().hash()``)

Examples:

::

    val g = [1, 2, 3].to_gtv();
    val l = list<integer>.from_gtv(g);   // Returns [1, 2, 3]
    print(g.hash());

--------------

json
--------

``json(text)`` - create a ``json`` value from a string; fails if not a valid JSON string

``.to_text(): text`` - convert to string

--------------

record
------

Functions available for all ``record`` types:

``T.from_bytes(byte_array): T`` - decode from a binary-encoded ``gtv``
(same as ``T.from_gtv(gtv.from_bytes(x))``)

``T.from_gtv(gtv): T`` - decode from a ``gtv``

``T.from_gtv_pretty(gtv): T`` - decode from a pretty-encoded ``gtv``

``.to_bytes(): byte_array`` - encode in binary format (same as ``.to_gtv().to_bytes()``)

``.to_gtv(): gtv`` - convert to a ``gtv``

``.to_gtv_pretty(): gtv`` - convert to a pretty ``gtv``

--------------

virtual<record>
----------------

``virtual<R>.from_gtv(gtv): R`` - decodes a Gtv

``.hash(): byte_array``

``.to_full(): R`` - converts to the original value, fails if the value is not full

--------------

*Rell v0.9.0*