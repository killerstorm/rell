# Database Operations

# 1. At-Operator

Simplest form:

`user @ { name = 'Bob' }`

General syntax:

`<from> <cardinality> { <where> } [<what>]`

## Cardinality

Specifies whether the expression must return one or many objects:

* `T @? {}` - returns `T`, zero or one, fails if more than one found.
* `T @ {}` - returns `T`, exactly one, fails if zero or more than one found.
* `T @* {}` - returns `list<T>`, zero or more.
* `T @+ {}` - returns `list<T>`, one or more, fails if none found.

## From-part

Simple (one class):

`user @* { name = 'Bob' }`

Complex (one or more classes):

`(user, company) @* { user.name = 'Bob' and company.name = 'Microsoft' and user.xyz = company.xyz }`

Specifying class aliases:

`(u: user) @* { u.name = 'Bob' }`

`(u: user, c: company) @* { u.name = 'Bob' and c.name = 'Microsoft' and u.xyz = c.xyz }`


## Where-part

Zero or more comma-separated expressions using class attributes, local variables or system functions:

`user @* {}` - returns all users

`user @ { name = 'Bill', company = 'Microsoft' }` - returns a specific user (all conditions must match)

## What-part

Simple what:

`user @* {}.company.name` - returns an attribute of the object

Complex what:

`user @* {} ( company.name, company.address )` - returns a tuple of values (if more than one)

Specifying names of result tuple fields:

`user @* {} ( x = company.name, y = company.address, z = yearOfBirth )`

Sorting:

`user @* {} ( sort lastName, sort firstName )` - sort by `lastName` first, then by `firstName`.

`user @* {} ( -sort yearOfBirth, sort lastName )` - sort by `yearOfBirth` desdending, then by `lastName` ascending.

Field names can be combined with sorting:

`user @* {} ( sort x = lastName, -sort y = yearOfBirth )`

## Result type

Depends on the cardinality, from- and what-parts.

* From- and what-parts define the type of a single record, `T`.
* Cardinality defines the type of the @-operator result: either `T` or `list<T>`.

Examples:

* `user @ { ... }` - returns `user`
* `(user, company) @ { ... }` - returns a tuple `(user,company)`
* `(user, company) @* { ... }` - returns `list<(user,company)>`
* `user @ { ... }.name` - returns `text` (type of `name`)
* `user @* { ... }.name` - returns `list<text>`
* `user @ { ... } ( name )` - returns `text`
* `user @ { ... } ( firstName, lastName )` - returns `(text,text)`
* `(user, company) @ { ... } ( user.firstName, user.lastName, company )` - returns `(text,text,company)`

# 2. Create Statement

Must specify all attributes that don't have default values.


```
create user(name = 'Bob', company = company @ { name = 'Amazon' });
```

No need to specify attribute name if it can be matched by name or type:
```
val name = 'Bob';
create user(name, company @ { name = 'Amazon' });
```

Can use the created object:
```
val newCompany = create company(name = 'Amazon');
val newUser = create user(name = 'Bob', newCompany);
print('Created new user:', newUser);
```

# 3. Update Statement

```
update user @ { name = 'Bob' } ( company = 'Microsoft' );
update user @ { name = 'Alice' } ( salary += 5000 );
```

Using multiple classes with aliases. The first class is the one being updated. Other classes can be used in the where-part:

```
update (u: user, c: company) @ { u.xyz = c.xyz, u.name = 'Bob', c.name = 'Google' } ( city = 'Seattle' );
```

# 4. Delete Statement

```
delete user @ { name = 'Bob' };
```

Using multiple classes. Similar to `update`, only the object(s) of the first class will be deleted:
```
update (u: user, c: company) @ { u.xyz = c.xyz, u.name = 'Bob', c.name = 'Google' };
```
