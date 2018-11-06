See [Language Guide](Language.md).

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
