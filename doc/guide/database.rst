===================
Database Operations
===================

-------------

At-Operator
===========

Simplest form:

``user @ { .name == 'Bob' }``

General syntax:

``<from> <cardinality> { <where> } [<what>] [limit N]``

.. _languagedatabase-cardinality:

Cardinality
-----------

Specifies whether the expression must return one or many objects:

-  ``T @? {}`` - returns ``T``, zero or one, fails if more than one
   found.
-  ``T @ {}`` - returns ``T``, exactly one, fails if zero or more than
   one found.
-  ``T @* {}`` - returns ``list<T>``, zero or more.
-  ``T @+ {}`` - returns ``list<T>``, one or more, fails if none found.

From-part
---------

Simple (one class):

``user @* { .name == 'Bob' }``

Complex (one or more classes):

``(user, company) @* { user.name == 'Bob' and company.name == 'Microsoft' and user.xyz == company.xyz }``

Specifying class aliases:

``(u: user) @* { u.name == 'Bob' }``

``(u: user, c: company) @* { u.name == 'Bob' and c.name == 'Microsoft' and u.xyz == c.xyz }``

Where-part
----------

Zero or more comma-separated expressions using class attributes, local
variables or system functions:

``user @* {}`` - returns all users

``user @ { .name == 'Bill', .company == 'Microsoft' }`` - returns a specific
user (all conditions must match)

Attributes of a class can be accessed with a dot, e. g. ``.name`` or with a class name or alias, ``user.name``.

Class attributes can also be matched implicitly by name or type:

::

   val ms = company @ { .name == 'Microsoft' };
   val name = 'Bill';
   return user @ { name, ms };

Explanation: the first where-expression is the local variable ``name``, there is an attribute called ``name`` in the
class ``user``. The second expression is ``ms``, there is no such attribute, but the type of the local variable ``ms``
is ``company``, and there is an attribute of type ``company`` in ``user``.

What-part
---------

Simple example:

``user @ { .name == 'Bob' } ( .company.name )`` - returns a single value
(name of the user's company)

``user @ { .name == 'Bob' } ( .company.name, .company.address )`` - returns
a tuple of two values

Specifying names of result tuple fields:

``user @* {} ( x = .company.name, y = .company.address, z = .yearOfBirth )``
- returns a tuple with named fields (``x``, ``y``, ``z``)

Sorting:

``user @* {} ( sort .lastName, sort .firstName )`` - sort by ``lastName``
first, then by ``firstName``.

``user @* {} ( -sort .yearOfBirth, sort .lastName )`` - sort by
``yearOfBirth`` desdending, then by ``lastName`` ascending.

Field names can be combined with sorting:

``user @* {} ( sort x = .lastName, -sort y = .yearOfBirth )``

When field names are not specified explicitly, they can be deducted
implicitly by attribute name:

::

   val u = user @ { ... } ( .firstName, .lastName, age = 2018 - .yearOfBirth );
   print(u.firstName, u.lastName, u.age);

Tail part
---------

Limiting records:

``user @* { .company == 'Microsoft' } limit 10``

Returns at most 10 objects. The limit is applied before the cardinality
check, so the following code can't fail with "more than one object"
error:

``val u: user = user @ { .company == 'Microsoft' } limit 1;``

Result type
-----------

Depends on the cardinality, from- and what-parts.

-  From- and what-parts define the type of a single record, ``T``.
-  Cardinality defines the type of the @-operator result: ``T?``, ``T``
   or ``list<T>``.

Examples:

-  ``user @ { ... }`` - returns ``user``
-  ``user @? { ... }`` - returns ``user?``
-  ``user @* { ... }`` - returns ``list<user>``
-  ``user @+ { ... }`` - returns ``list<user>``
-  ``(user, company) @ { ... }`` - returns a tuple ``(user,company)``
-  ``(user, company) @* { ... }`` - returns \`list<(user,company)>``
-  ``user @ { ... } ( .name )`` - returns ``text``
-  ``user @ { ... } ( .firstName, .lastName )`` - returns ``(text,text)``
-  ``(user, company) @ { ... } ( user.firstName, user.lastName, company )`` 
- returns ``(text,text,company)``

Nested At-Operators
-------------------

A nested at-operator can be used in any expression inside of another
at-operator:

``user @* { .company == company @ { .name == 'Microsoft' } } ( ... )``

This is equivalent to:

::

   val c = company @ { .name == 'Microsoft' };
   user @* { .company == c } ( ... )

-------------

Create Statement
================

Must specify all attributes that don't have default values.

::

   create user(name = 'Bob', company = company @ { .name == 'Amazon' });

No need to specify attribute name if it can be matched by name or type:

::

   val name = 'Bob';
   create user(name, company @ { company.name == 'Amazon' });

Can use the created object:

::

   val newCompany = create company(name = 'Amazon');
   val newUser = create user(name = 'Bob', newCompany);
   print('Created new user:', newUser);

-------------

Update Statement
================

Operators ``@``, ``@?``, ``@*``, ``@+`` are used to specify cardinality, like for the at-operator.
If the number of updated records does not match the cardinality, a run-time error occurs.

::

   update user @ { .name == 'Bob' } ( company = 'Microsoft' );             // exactly one
   update user @? { .name == 'Bob' } ( deleted = true );                   // zero or one
   update user @* { .company.name == 'Bad Company' } ( salary -= 1000 );   // any number

Can change only ``mutable`` attributes.

Class attributes can be matched implicitly by name or type:

::

   val company = 'Microsoft';
   update user @ { .name == 'Bob' } ( company );

Using multiple classes with aliases. The first class is the one being
updated. Other classes can be used in the where-part:

::

   update (u: user, c: company) @ { u.xyz == c.xyz, u.name == 'Bob', c.name == 'Google' } ( city = 'Seattle' );

Can specify an arbitrary expression returning a class, a nullable class or a collection of a class:

::

   val u = user @? { .name == 'Bob' };
   update u ( salary += 5000 );

-------------

Delete Statement
================

Operators ``@``, ``@?``, ``@*``, ``@+`` are used to specify cardinality, like for the at-operator.
If the number of deleted records does not match the cardinality, a run-time error occurs.

::

   delete user @ { .name == 'Bob' };                    // exactly one
   delete user @? { .name == 'Bob' };                   // zero or one
   delete user @* { .company.name == 'Bad Company' };   // any number

Using multiple classes. Similar to ``update``, only the object(s) of the first class will be deleted:

::

   delete (u: user, c: company) @ { u.xyz == c.xyz, u.name == 'Bob', c.name == 'Google' };

Can specify an arbitrary expression returning a class, a nullable class or a collection of a class:

::

   val u = user @? { .name == 'Bob' };
   delete u;
