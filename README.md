## Language Guide

[Modules](doc/guide/modules.rst)

[General Language Features](doc/guide/general.rst)

[Database Operations](doc/guide/database.rst)

[Library](doc/guide/library.rst)

## Build

To build a distribution archive (w/o running unit tests):

 ```
 mvn clean install -DskipTests -Pdistro
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
CREATE USER "postchain";
ALTER USER "postchain" WITH PASSWORD 'postchain';
CREATE DATABASE "postchain" WITH TEMPLATE = template0 LC_COLLATE = 'C.UTF-8' LC_CTYPE = 'C.UTF-8' ENCODING 'UTF-8';
GRANT ALL PRIVILEGES ON DATABASE "postchain" TO "postchain";
```

Database connection configuration for tests is taken from the file `src/test/resources/rell-db-config.properties`.

**WARNING**: Unit tests drop all existing tables in the specified database, so make sure you specify a right database.

To run unit tests in IntelliJ, select the `net.postchain.rell` package (or an individual tests class) and press
Ctrl-Shift-F10 (or right click and choose "Run 'Tests in 'net.postchain.rell''").

## Generating rell grammar test snippets

### Using IntelliJ:
1. Create run configuration from `work/Test_snippets.run.xml` file
2. Run it

Archive will be created in `user.home` directory, with name `testsources-<RELL_VERSION>.zip`

**Do not run snippet generation using Maven, currently it's not supported** 

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
