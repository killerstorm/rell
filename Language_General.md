# General Language Features

# 1. Types

Simple types:

* `boolean`
* `integer`
* `text`
* `byte_array`
* `json`
* `unit` (no value; cannot be used explicitly)

Simple type aliases:

* `pubkey` = `byte_array`
* `name` = `text`
* `tuid` = `text`

Complex types:

* object reference
* `range` (can be used in `for` statement)
* `list<T>`
* `set<T>`
* `map<K,V>`
* tuple: `(T1, ..., Tn)`

### Tuple type

Examples:

`(integer)` - one value

`(integer, text)` - two values

`(integer, (text, boolean))` - nested tuple

`(x: integer, y: integer)` - named fields (can be accessed as `A.x`, `A.y`)


# 2. Module definition

## Class

```
class company {
    key text name;
    index text address;
}

class user {
    key text firstName, lastName;
    index company;
    integer yearOfBirth;
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

* Boolean: `true`, `false`
* Integer: `123`, `0`, `-456`
* Text: `'Hello'`, `"World"`
* Byte array: `x'1234'`, `x"ABCD"`

List:
```
[ 1, 2, 3, 4, 5 ]
```

Map:
```
[ 'Bob' : 123, 'Alice' : 456 ]
```

## Operators

Special:

* `.` - member access: `x.foo`, `x.bar(123)`
* `()` - function call: `print('Hello')`, `value.str()`
* `[]` - element access: `values[i]`

Comparison:

* `==`
* `!=`
* `<`
* `>`
* `<=`
* `>=`

Arithmetical:

* `+`
* `-`
* `*`
* `/`
* `%`

Logical:

* `and`
* `or`
* `not`

Other:

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
```

## Loop statements

For:

```
for (x in range(10) {
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

## Require

Throws an error if a condition is not met.

```
require(name != '');
require(name != '', 'Name is empty!');
require(user @ { name = 'Bob' }, 'User not found!');
```
