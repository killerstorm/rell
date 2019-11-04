=========================
Modules
=========================

Rell application consists of modules. A module is either a single ``.rell`` file or a directory with one or multiple ``.rell`` files.

A single-file Rell module must have a module header:

::

    module;

    // entities, operations, queries, functions and other definitions

If a ``.rell`` file has no module header, it is a part of a directory-module. All such ``.rell`` files in a directory
belong to the same directory-module. An exception is a file called ``module.rell``: it always belongs to a directory-module,
even if it has a module header. It is not mandatory for a directory-module to have a ``module.rell``.

Every file of a directory-module sees definitions of all other files of the module. A file-module file sees only its own
definitions.

Example of a Rell source directory tree:

.. code-block:: none

    .
    └── app
        ├── multi
        │   ├── functions.rell
        │   ├── module.rell
        │   ├── operations.rell
        │   └── queries.rell
        └── single.rell

**app/multi/functions.rell**:

::

    function g(): integer = 456;

**app/multi/module.rell**:

::

    module;
    enum state { OPEN, CLOSED }

**app/single.rell**:

::

    module;
    function f(): integer = 123;

Every module has a name defined by its source directory path. The sample source directory tree given above defines
two modules:

- ``app.multi`` - a directory-module in the directory ``app/multi`` (consisting of 4 files)
- ``app.single`` - a file-module in the file ``app/single.rell``

There may be a root module - a directory-module which consists of .rell files located in the root of the source directory.
Root module has an empty name. Web IDE uses the root module as the default main module of a Rell application.

Import
==============

To access module's definitions, the module has to be imported:

::

    import app.single;

    function test() {
        single.f();         // Calling the function "f" defined in the module "app.single".
    }

When importing a module, it is added to the current namespace with some alias. By default, the alias is the last
part of the module name, i. e. ``single`` for the module ``app.single`` or ``multi`` for ``app.multi``. The definitions
of the module can be accessed via the alias.

A custom alias can be specified:

::

    import alias: app.multi;

    function test() {
        alias.g();
    }

It is possible to specify a relative name of a module when importing. In that case, the name of the imported module is derived
from the name of the current module. For example, if the current module is ``a.b.c``,

- ``import .d;`` imports ``a.b.c.d``
- ``import alias: ^;`` imports ``a.b``
- ``import alias: ^^;`` imports ``a``
- ``import ^.e;`` imports ``a.b.e``

Run-time
==============

At run-time, not all modules defined in a source directory tree are active.
There is a main module which is specified when starting a Rell application.
Only the main module and all modules imported by it (directly or indirectly) are active.

When a module is active, its operations and queries can be invoked, and tables for its entities and objects are added to the
database on initialization.

--------------

*Rell v0.10.0*