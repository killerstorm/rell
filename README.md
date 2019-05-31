## Language Guide

[General Language Features](doc/guide/general.rst)

[Database Operations](doc/guide/database.rst)

[Library](doc/guide/library.rst)

## Build

Simplest way to build:

1. Check out the `postchain2` repository:  
    ```
    git clone git@bitbucket.org:chromawallet/postchain2.git
    ```  
    Switch to the right branch:  
    ```
    git checkout v3.0
    ```
2. Build `postchain2`:  
    ```
    mvn clean install -DskipTests
    ```
3. Build `rellr`:  
    ```
    mvn -Pconsole clean package -DskipTests
    ```

## Command Line Interpreter

Run `rell.sh`:

```
Usage: rell [--resetdb] [--dburl=URL] FILE [OP] [ARGS...]
Executes a rell program
      FILE          Rell source file
      [OP]          Operation or query name
      [ARGS...]     Call arguments
      --dburl=URL   Database JDBC URL, e. g. jdbc:postgresql://localhost/relltestdb?user=relltestuser&password=1234
      --resetdb     Reset database (drop all and create tables from scratch)
      --sqllog      Enable SQL logging
```

To execute an operation without a database connection:

```
./rell.sh RELL_FILE OPERATION_NAME [ARGS...]
```

To create database tables (**drops all existing tables**):

```
./rell.sh --dburl JDBC_URL --resetdb RELL_FILE
```

To execute an operation with a database connection (using existing tables):

```
./rell.sh --dburl JDBC_URL RELL_FILE OPERATION_NAME [ARGS...]
```

## Running Unit Tests

First, set up a PostgreSQL database and user:

```
CREATE DATABASE "relltestdb" WITH TEMPLATE = template0 LC_COLLATE = 'C' LC_CTYPE = 'C';
CREATE USER "relltestuser" WITH PASSWORD '1234';
GRANT ALL ON DATABASE "relltestdb" TO "relltestuser";
```

By default, database connection configuration for tests is taken from file `config.properties` in the `postchain2` artifact. At the moment of writing:

```
database.url=jdbc:postgresql://localhost:5432/postchain
database.username=postchain
database.password=postchain
```

Those values can be overridden in a local file `src/test/resources/local-config.properties` (git-ignored). For example:

```
database.url=jdbc:postgresql://test-sql-server/relltestdb
database.username=relltestuser
database.password=1234

include = config.properties
```

**WARNING**: Unit tests drop all existing tables in the specified database, so make sure you specify a right database.

To run unit tests in IntelliJ, select the `net.postchain.rell` package (or an individual tests class) and press Ctrl-Shift-F10 (or right click and choose "Run 'Tests in 'net.postchain.rell''").
