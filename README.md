## Language Guide

[Modules](doc/guide/modules.rst)

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
    git checkout ver-3.1.0
    ```
2. Build `postchain2`:  
    ```
    mvn clean install -DskipTests
    ```
3. Build `rellr`:  
    ```
    mvn clean package -DskipTests
    ```

## Command Line Interpreter

Run `rell.sh`:

```
Usage: rell [-qv] [--json] [--json-args] [--json-result] [--resetdb] [--sqlinitlog] [--sqllog] [--typecheck] [--db-properties=DB_PROPERTIES] [--db-url=DB_URL] [-d=SOURCE_DIR] [MODULE] [ENTRY] [ARGS...]
Executes a rell program
      [MODULE]          Module name
      [ENTRY]           Entry point (operation/query/function name)
      [ARGS...]         Call arguments
      --db-properties=DB_PROPERTIES
                        Database connection properties file (same format as node-config.properties)
      --db-url=DB_URL   Database JDBC URL, e. g. jdbc:postgresql://localhost/relltestdb?user=relltestuser&password=1234
      --json            Equivalent to --json-args --json-result
      --json-args       Accept Rell program arguments in JSON format
      --json-result     Print Rell program result in JSON format
      --resetdb         Reset database (drop everything)
      --sqlinitlog      Enable SQL tables structure update logging
      --sqllog          Enable SQL logging
      --typecheck       Run-time type checking (debug)
  -d, --source-dir=SOURCE_DIR
                        Rell source code directory (default: current directory)
  -q, --quiet           No useless messages
  -v, --version         Print version and quit
```

To execute an operation without a database connection:

```
./rell.sh RELL_FILE OPERATION_NAME [ARGS...]
```

To create database tables (**drops all existing tables**):

```
./rell.sh --db-url JDBC_URL --resetdb RELL_FILE
```

To execute an operation with a database connection (using existing tables):

```
./rell.sh --db-url JDBC_URL RELL_FILE OPERATION_NAME [ARGS...]
```

Argument `--db-properties` can be used istead of `--db-url` in the above examples.

## Run a Simple Poschain Node

Start a single-chain Postchain Node with a Rell app. This utility is used by the `Run As` - `Rell Simple Postchain App` command in Eclipse IDE.

A `node-config.properties` and a Rell file must be specified.

```
$ ./singlerun.sh

Usage: PostchainAppLaunch --node-config=NODE_CONFIG_FILE [-d=SOURCE_DIR] MODULE
Runs a Rell Postchain app
      MODULE   Module name
      --node-config=NODE_CONFIG_FILE
               Node configuration (.properties)
  -d, --source-dir=SOURCE_DIR
               Rell source code directory (default: current directory)
```

## Run a Multi-Chain Postchain Node (Run.XML)

Runs a Postchain node with one or multiple blockchains. The configuration is specified
in the Run.XML format. This utility is used by the `Run As` - `Rell Postchain App` command in the Eclipse IDE.

```
./multirun.sh

Usage: RellRunConfigLaunch --source-dir=SOURCE_DIR RUN_CONFIG
Launch a run config
      RUN_CONFIG   Run config file
      --source-dir=SOURCE_DIR
                   Rell source directory
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

## Copyright & License information

Copyright (c) 2017-2020 ChromaWay AB. All rights reserved.

This software can used either under terms of commercial license
obtained from ChromaWay AB, or, alternatively, under the terms
of the GNU General Public License with additional linking exceptions.
See file LICENSE for details.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
