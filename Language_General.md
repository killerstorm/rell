# General Language Features

# 1. Types

Simple types:

* `boolean`
* `integer`
* `text`
* `byte_array`
* `json`
* `unit` (no value; cannot be used explicitly)
* `null` (type of `null` expression; cannot be used explicitly)

Simple type aliases:

* `pubkey` = `byte_array`
* `name` = `text`
* `tuid` = `text`

Complex types:

* object reference
* `T?` - nullable type
* `range` (can be used in `for` statement)
* `list<T>`
* `set<T>`
* `map<K,V>`
* tuple: `(T1, ..., Tn)`

### Nullable type

* Class attributes cannot be nullable.
* Can be used with almost any type (except nullable, `unit`, `null`).
* Nullable nullable (`T??` is not allowed).
* Normal operations of the underlying type cannot be applied directly.
* Supports `?:`, `?.` and `!!` operators (like in Kotlin).

Compatibility with other types:

* Can assign a value of type `T` to a variable of type `T?`, but not the other way round.
* Can assign `null` to a variable of type `T?`, but not to a variable of type `T`.
* Can assign a value of type `(T)` (tuple) to a variable of type `(T?)`.
* Cannot assign a value of type `list<T>` to a variable of type `list<T?>`.

Allowed operations:

* Null comparison: `x == null`, `x != null`.
* `?:` - Elvis operator: `x ?: y` means `x` if `x` is not `null`, otherwise `y`.
* `?.` - safe access: `x?.y` results in `x.y` if `x` is not `null` and `null` otherwise.
* Operator `?.` can be used with function calls, e. g. `x?.upperCase()`.
* `!!` - null check operator: `x!!` returns value of `x` if `x` is not `null`, otherwise throws an exception.
* `require(x)`, `requireNotEmpty(x)`: throws an exception if `x` is `null`, otherwise returns value of `x`.

Examples:
```
val x: integer? = 123;
val y = x;            // type of "y" is "integer?"
val z = y!!;          // type of "z" is "integer"
val p = require(y);   // type of "p" is "integer"
```

### Tuple type

Examples:

* `(integer)` - one value
* `(integer, text)` - two values
* `(integer, (text, boolean))` - nested tuple
* `(x: integer, y: integer)` - named fields (can be accessed as `A.x`, `A.y`)

Tuple types are compatible only if names and types of fields are the same:

* `(x:integer, y:integer)` and `(a:integer,b:integer)` are not compatible.
* `(x:integer, y:integer)` and `(integer,integer)` are not compatible.

### Subtypes

If type `B` is a subtype of type `A`, a value of type `B` can be assigned to a variable of type `A` (or passed as a parameter of type `A`).

* `T` is a subtype of `T?`.
* `null` is a subtype of `T?`.
* `(T,P)` is a subtype of `(T?,P?)`, `(T?,P)` and `(T,P?)`.

# 2. Module definition

## Class

```
class company {
    key name: text;
    index address: text;
}

class user {
    key firstName: text, lastName: text;
    index company;
    yearOfBirth: integer;
    mutable salary: integer;
}
```

Attributes may have default values:

```
class user {
    homeCity: text = 'New York';
}
```

## Query

* Cannot modify the data in the database (compile-time check).
* Must return a value.
* If return type is not explicitly specified, it is implicitly deducted.

Short form:

```
query q(x: integer): integer = x * x;
```

Full form:

```
query q(x: integer): integer {
    return x * x;
}
```

## Operation

* Can modify the data in the database.
* Does not return a value.

```
operation createUser(name: text) {
    create user(name = name);
}
```

## Function

* Can return nothing or a value.
* Can modify the data in the database when called from an operation (run-time check).
* Can be called from queries, operations or functions.
* If return type is not specified explicitly, it is `unit` (no return value).

Short form:

```
function f(x: integer): integer = x * x;
```

Full form:

```
function f(x: integer): integer {
    return x * x;
}
```

When return type is not specified, it is considered `unit`:

```
function f(x: integer) {
    print(x);
}
```

# 3. Expressions

## Values

Simple values:

* Null: `null` (type is `null`)
* Boolean: `true`, `false`
* Integer: `123`, `0`, `-456`
* Text: `'Hello'`, `"World"`
* Byte array: `x'1234'`, `x"ABCD"`

Tuple:

* `(1, 2, 3)` - three values
* `(123, 'Hello')` - two values
* `(456,)` - one value (because of the comma)
* `(789)` - not a tuple (no comma)
* `(a: 123, b: 'Hello')` - tuple with named fields

List:
```
[ 1, 2, 3, 4, 5 ]
```

Map:
```
[ 'Bob' : 123, 'Alice' : 456 ]
```

## Operators

#### Special:

* `.` - member access: `user.name`, `s.sub(5, 10)`
* `()` - function call: `print('Hello')`, `value.str()`
* `[]` - element access: `values[i]`

#### Null handling:

* `?:` - Elvis operator: `x ?: y` returns `x` if `x` is not `null`, otherwise returns `y`.
* `?.` - safe access operator: `x?.y` returns `x.y` if `x` is not `null`, otherwise returns `null`; similarly, `x?.y()` returns either `x.y()` or `null`.
* `!!` - null check: `x!!` returns `x` if `x` is not `null`, otherwise throws an exception.

Examples:
```
val x: integer? = 123;
val y = x;              // type of "y" is "integer?"

val a = y ?: 456;       // type of "a" is "integer"
val b = y ?: null;      // type of "b" is "integer?"

val p = y!!;            // type of "p" is "integer"
val q = y?.hex();       // type of "q" is "text?"
```

#### Comparison:

* `==`
* `!=`
* `<`
* `>`
* `<=`
* `>=`

#### Arithmetical:

* `+`
* `-`
* `*`
* `/`
* `%`

#### Logical:

* `and`
* `or`
* `not`

#### Other:

* `in` - check if an element is in a range/set/map

# 4. Statements

## Local variable declaration

Constants:

```
val x = 123;
val y: text = 'Hello';
```

Variables:

```
var x: integer;
var y = 123;
var z: text = 'Hello';
```

## Basic statements

Assignment:

```
x = 123;
values[i] = z;
y += 15;
```

Function call:

```
print('Hello');
```

Return:

```
return;
return 123;
```

Block:

```
{
    val x = calc();
    print(x);
}
```

## If statement

```
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

```

## Loop statements

For:

```
for (x in range(10)) {
    print(x);
}

for (u in user @* {}) {
    print(u.name);
}
```

The expression after `in` may return a `range` or a collection (`list`, `set`, `map`).

While:

```
while (x < 10) {
    print(x);
    x = x + 1;
}
```

Break:

```
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
```
