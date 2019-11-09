Module definitions
==================

Entity
------

Values (instances) of an entity in Rell are stored in a database, not in memory.
They have to be created and deleted explicitly using Rell ``create`` and ``delete`` expressions.
An in-memory equivalent of an entity in Rell is a struct.

A variable of an entity type holds an ID (primary key) of the corresponding database record, but not its attribute values.

::

    entity company {
        name: text;
        address: text;
    }

    entity user {
        first_name: text;
        last_name: text;
        year_of_birth: integer;
        mutable salary: integer;
    }

If attribute type is not specified, it will be the same as attribute name:

::

    entity user {
        name;       // built-in type "name"
        company;    // user-defined type "company" (error if no such type)
    }

Attributes may have default values:

::

    entity user {
        home_city: text = 'New York';
    }

An ID (database primary key) of an entity value can be accessed via the ``rowid`` implicit attribute (of type ``rowid``):

::

    val u = user @ { .name == 'Bob' };
    print(u.rowid);

    val alice_id = user @ { .name == 'Alice' } ( .rowid );
    print(alice_id);

Keys and Indices
~~~~~~~~~~~~~~~~

Entities can have ``key`` and ``index`` clauses:

::

    entity user {
        name: text;
        address: text;
        key name;
        index address;
    }

Keys and indices may have multiple attributes:

::

    entity user {
        first_name: text;
        last_name: text;
        key first_name, last_name;
    }

Attribute definitions can be combined with ``key`` or ``index`` clauses,
but such definition has restrictions (e. g. cannot specify ``mutable``):

::

    entity user {
        key first_name: text, last_name: text;
        index address: text;
    }

Entity annotations
~~~~~~~~~~~~~~~~~~

::

    @log entity user {
        name: text;
    }

The ``@log`` annotation has following effects:

- Special attribute ``transaction`` of type ``transaction`` is added to the entity.
- When an entity value is created, ``transaction`` is set to the result of ``op_context.transaction`` (current transaction).
- Entity cannot have mutable attributes.
- Values cannot be deleted.

Object
------

Object is similar to entity, but there can be only one instance of an object:

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

- Like entities, objects are stored in a database.
- Objects are initialized automatically during blockchain initialization.
- Cannot create or delete an object from code.
- Attributes of an object must have default values.

Struct
------

Struct is similar to entity, but its values exist in memory, not in a database.

::

    struct user {
        name: text;
        address: text;
        mutable balance: integer = 0;
    }

Features of structs:

- Attributes are immutable by default, and only mutable when declared with ``mutable`` keyword.
- Attributes can have
- An attribute may have a default value, which is used if the attribute is not specified during construction.
- Structs are deleted from memory implicitly by a garbage collector.

Creating struct values:

::

    val u = user(name = 'Bob', address = 'New York');

Same rules as for the ``create`` expression apply: no need to specify attribute name if it can be resolved implicitly
by name or type:

::

    val name = 'Bob';
    val address = 'New York';
    val u = user(name, address);
    val u2 = user(address, name); // Order does not matter - same struct value is created.

Struct attributes can be accessed using operator ``.``:

::

    print(u.name, u.address);

Safe-access operator ``?.`` can be used to read or modify attributes of a nullable struct:

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
        entity user {
            name;
            country;
        }

        struct point {
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
- Names of tables for entities and objects defined in a namespace contain the full name, e. g. the table for entity
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
        entity user {}
        entity company {}
    }

External
--------

External blocks are used to access entities defined in other blockchains:

::

    external 'foo' {
        @log entity user {
            name;
        }
    }

    query get_all_users() = user @* {};

In this example, ``'foo'`` is the name of an external blockchain. To be used in an external block, a blockchain
must be defined in the blockchain configuration (``dependencies`` node).

Every blockchain has its ``chain_id``, which is included in table names for entities and objects of that chain. If the
blockchain ``'foo'`` has ``chain_id`` = 123, the table for the entity ``user`` will be called ``c123.user``.

Other features:

- External entities must be annotated with the ``@log`` annotation. This implies that those entity cannot have mutable
  attributes.
- Values of external entities cannot be created or deleted.
- Only entities and namespaces are allowed inside of an external block.
- Can have only one external block for a specific blockchain name.
- When selecting values of an external entity (using at-expression), an implicit block height filter is applied, so
  the active blockchain can see only those blocks of the external blockchain whose height is lower than a specific value.
- Every blockchain stores the structure of its entities in meta-information tables. When a blockchain is started,
  the meta-information of all involved external blockchains is verified to make sure that all declared external entities
  exist and have declared attributes.

Transactions and blocks
~~~~~~~~~~~~~~~~~~~~~~~

To access blocks and transactions of an external blockchian, a special syntax is used:

::

    namespace foo {
        external 'foo' {
            entity transaction;
            entity block;
        }
    }

   function get_foo_transactions(): list<foo.transaction> = foo.transaction @* {};
   function get_foo_blocks(): list<foo.block> = foo.block @* {};

- External block must be put in a namespace in order to prevent name conflict, since entities ``transaction`` and
  ``block`` are already defined in the top-level scope (they represent transactions and blocks of the active blockchain).
- Namespace name can be arbitrary.
- External and non-external transactions/blocks are distinct, incompatible types.
- When selecting external transactions or blocks, an implicit height filter is applied (like for external entities).

.. _general-mount-names:

Mount names
-----------

Entities, objects, operations and queries have mount names:

- for entities and objects, those names are the SQL table names where the data is stored
- for operations and queries, a mount name is used to invoke an operation or a query from the outside

By default, a mount name is defined by a fully-qualified name of a definition:

::

    namespace foo {
        namespace bar {
            entity user {}
        }
    }

The mount name for the entity ``user`` is ``foo.bar.user``.

To specify a custom mount name, ``@mount`` annotation is used:

::

    @mount('foo.bar.user')
    entity user {}

The ``@mount`` annotation can be specified for entities, objects, operations and queries.

In addition, it can be specified for a namespace:

::

    @mount('foo.bar')
    namespace ns {
        entity user {}
    }

or a module:

::

    @mount('foo.bar')
    module;

    entity user {}

In both cases, the mount name of ``user`` is ``foo.bar.user``.

A mount name can be relative to the context mount name. For example, when defined in a namespace

::

    @mount('a.b.c')
    namespace ns {
        entity user {}
    }

entity ``user`` will have following mount names when annotated with ``@mount``:

- ``@mount('.d.user')`` -> ``a.b.c.d.user``
- ``@mount('^.user')`` -> ``a.b.user``
- ``@mount('^^.x.user')`` -> ``a.x.user``

Special character ``.`` appends names to the context mount name, and ``^`` removes the last part from the context
mount name.

A mount name can end with ``.``, in that case the name of the definition is appended to the mount name:

::

    @mount('foo.')
    entity user {}      // mount name = "foo.user"

    @mount('foo')
    entity user {}      // mount name = "foo"

--------------

*Rell v0.10.0*