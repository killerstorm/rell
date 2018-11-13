# Library

## Global Functions

`abs(integer): integer` - absolute value

`is_signer(byte_array): boolean` - returns `true` if a byte array is in the list of signers of current operation

`json(text): json` - parse a JSON

`log(...)` - print a message to the log (same usage as `print`)

`max(integer, integer): integer` - maximum of two values

`min(integer, integer): integer` - minimum of two values

`print(...)` - print a message to STDOUT:

* `print()` - prints an empty line
* `print('Hello', 123)` - prints `"Hello 123"`

### Require functions

For checking a boolean condition:

`require(boolean[, text])` - throws an exception if the argument is `false`

For checking for `null`:

`require(T?[, text]): T` - throws an exception if the argument is `null`, otherwise returns the argument

`requireNotEmpty(T?[, text]): T` - same as the previous one

For checking for an empty collection:

`requireNotEmpty(list<T>[, text]): list<T>` - throws an exception if the argument is an empty collection, otherwise returns the argument

`requireNotEmpty(set<T>[, text]): set<T>` - same as the previous

`requireNotEmpty(map<K,V>[, text]): map<K,V>` - same as the previous

When passing a nullable collection to `requireNotEmpty`, it throws an exception if the argument is either `null` or an empty collection.

Examples:
```
val x: integer? = calculate();
val y = require(x, "x is null"); // type of "y" is "integer", not "integer?"

val p: list<integer> = getList();
requireNotEmpty(p, "List is empty");

val q: list<integer>? = tryToGetList();
require(q);         // fails if q is null
requireNotEmpty(q); // fails if q is null or an empty list
```

---

### integer

`integer.MIN_VALUE` = minimum value (`-2^63`)

`integer.MAX_VALUE` = maximum value (`2^63-1`)

`integer(s: text, radix: integer = 10)` - parse a signed representation, fail if invalid

`integer.parseHex(text): integer` - parse an unsigned HEX representation

`.hex(): text` - convert to an unsigned HEX representation

`.str(radix: integer = 10)` - convert to a signed string representation

`.signum(): integer` - returns `-1`, `0` or `1` depending on the sign

---

## text

`.empty(): boolean`

`.size(): integer`

`.compareTo(text): integer` - as in Java

`.startsWith(text): boolean`

`.endsWith(text): boolean`

`.contains(text): boolean` - `true` if contains the given substring

`.indexOf(text, start: integer = 0): integer` - returns `-1` if substring is not found (as in Java)

`.lastIndexOf(text[, start: integer]): integer` - returns `-1` if substring is not found (as in Java)

`.sub(start: integer[, end: integer]): text` - get a substring (start-inclusive, end-exclusive)

`.replace(old: text, new: text)`

`.upperCase(): text`

`.lowerCase(): text`

`.split(text): list<text>` - strictly split by a separator (not a regular expression)

`.trim(): text` - remove leading and trailing whitespace

`.matches(text): boolean` - `true` if matches a regular expression

`.encode(): byte_array` - convert to a UTF-8 encoded byte array

`.charAt(integer): integer` - get a 16-bit code of a character

`.format(...)` - formats a string (as in Java):

* `'My name is <%s>'.format('Bob')` - returns `'My name is <Bob>'`

Special operators:

* `+` : concatenation
* `[]` : character access (returns single-character `text`)

---

## byte_array

`byte_array(text)` - create a `byte_array` from a HEX string, e.g.`'1234abcd'`

`byte_array(list<integer>)` - create a `byte_array` from a list; values must be 0 - 255

`.empty(): boolean`

`.size(): integer`

`.decode(): text` - decode a UTF-8 encoded text

`.sub(start: integer[, end: integer]): byte_array` - sub-array (start-inclusive, end-exclusive)

`.toList(): list<integer>` - list of values 0 - 255

Special operators:

* `+` : concatenation
* `[]` : element access

---

## range

`range(start: integer = 0, end: integer, step: integer = 1)` - start-inclusive, end-exclusive (as in Python):

* `range(10)` - a range from 0 (inclusive) to 10 (exclusive)
* `range(5, 10)` - from 5 to 10
* `range(5, 15, 4)` - from 5 to 15 with step 4, i. e. `[5, 9, 13]`
* `range(10, 5, -1)` - produces `[10, 9, 8, 7, 6]`
* `range(10, 5, -3)` - produces `[10, 7]`

Special operators:

* `in` - returns `true` if the value is in the range (taking `step` into account)

---

## list<T>

`list<T>()` - a new empty list

`list<T>(list<T>)` - a copy of the given list (list of subtype is accepted as well)

`list<T>(set<T>)` - a copy of the given set (set of subtype is accepted)

`.empty(): boolean`

`.size(): integer`

`.contains(T): boolean`

`.containsAll(list<T>): boolean`

`.containsAll(set<T>): boolean`

`.indexOf(T): integer` - returns `-1` if element is not found

`.sub(start: integer[, end: integer]): list<T>` - returns a sub-list (start-inclusive, end-exclusive)

`.str(): text` - returns e. g. `'[1, 2, 3, 4, 5]'`

`.add(T): boolean` - adds an element to the end, always returns `true`

`.add(pos: integer, T): boolean` - inserts an element at a position, always returns `true`

`.addAll(list<T>): boolean`

`.addAll(set<T>): boolean`

`.addAll(pos: integer, list<T>): boolean`

`.addAll(pos: integer, set<T>): boolean`

`.remove(T): boolean` - removes the first occurrence of the value, return `true` if found

`.removeAll(list<T>): boolean`

`.removeAll(set<T>): boolean`

`.removeAt(pos: integer): T` - removes an element at a given position

`.clear()`

Special operators:

* `[]` - element access (read/modify)
* `in` - returns `true` if the value is in the list

---

## set<T>

`set<T>()` - a new empty set

`set<T>(set<T>)` - a copy of the given set (set of subtype is accepted as well)

`set<T>(list<T>)` - a copy of the given list (with duplicates removed)

`.empty(): boolean`

`.size(): integer`

`.contains(T): boolean`

`.containsAll(list<T>): boolean`

`.containsAll(set<T>): boolean`

`.str(): text` - returns e. g. `'[1, 2, 3, 4, 5]'`

`.add(T): boolean` - if the element is not in the set, adds it and returns `true`

`.addAll(list<T>): boolean` - adds all elements, returns `true` if at least one added

`.addAll(set<T>): boolean` - adds all elements, returns `true` if at least one added

`.remove(T): boolean` - removes the element, returns `true` if found

`.removeAll(list<T>): boolean` - returns `true` if at least one removed

`.removeAll(set<T>): boolean` - returns `true` if at least one removed

`.clear()`

Special operators:

* `in` - returns `true` if the value is in the set

---

## map<K,V>

`map<K,V>()` - a new empty map

`map<K,V>(map<K,V>)` - a copy of the given map (map of subtypes is accepted as well)

`.empty(): boolean`

`.size(): integer`

`.contains(K): boolean`

`.get(K): V` - get value by key (same as `[]`)

`.str(): text` - returns e. g. `'{x=123, y=456}'`

`.clear()`

`.put(K, V)` - adds/replaces a key-value pair

`.putAll(map<K, V>)` - adds/replaces all key-value pairs from the given map

`.remove(K): V` - removes a key-value pair (fails if the key is not in the map)

`.keys(): set<K>` - returns a copy of keys

`.values(): list<V>` - returns a copy of values

Special operators:

* `[]` - get/set value by key
* `in` - returns `true` if a key is in the map
