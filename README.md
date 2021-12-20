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
    Switch to the right tag:  
    ```
    git checkout ver-3.4.1
    ```
2. Build `postchain2`:  
    ```
    mvn clean install -DskipTests
    ```
3. Build `rellr`:  
    ```
    mvn clean package -DskipTests
    ```

## Command Line Interpreter / Shell

Run `rell.sh`:

```
Rell 0.10.4 (2021-06-25T08:30:27+0000)
Type '\q' to quit or '\?' for help.
>>> 2+2
4
```

To execute an operation without a database connection:

```
./rell.sh -d SOURCE_DIRECTORY MODULE_NAME OPERATION_NAME [ARGS...]
```

To create database tables (**drops all existing tables**):

```
./rell.sh -d SOURCE_DIRECTORY --db-url JDBC_URL --resetdb MODULE_NAME
```

To execute an operation with a database connection (using existing tables):

```
./rell.sh -d SOURCE_DIRECTORY --db-url JDBC_URL MODULE_NAME OPERATION_NAME [ARGS...]
```

Argument `--db-properties` can be used istead of `--db-url` in the above examples.

## Run a Multi-Chain Postchain Node (Run.XML)

Runs a Postchain node with one or multiple blockchains. The configuration is specified
in the Run.XML format. This utility is used by the `Run As` - `Rell Postchain App` command in the Eclipse IDE.

```
./multirun.sh

Usage: RellRunConfigLaunch [-d=SOURCE_DIR] RUN_CONFIG
Launch a run config
      RUN_CONFIG   Run config file
  -d, --source-dir=SOURCE_DIR
                   Rell source code directory (default: current directory)
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

Copyright (c) 2017-2021 ChromaWay AB. All rights reserved.

This software can used either under terms of commercial license
obtained from ChromaWay AB, or, alternatively, under the terms
of the GNU General Public License with additional linking exceptions.
See file LICENSE for details.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
