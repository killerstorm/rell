#### Command-Line Tools

##### multirun.sh

Runs an application described by a Run.XML configuration.

```
Usage: RellRunConfigLaunch --source-dir=SOURCE_DIR RUN_CONFIG
Launch a run config
      RUN_CONFIG   Run config file
      --source-dir=SOURCE_DIR
                   Rell source directory
```

##### multigen.sh

Creates a Postchain blockchain XML configuration from a Run.XML configuration.

```
Usage: RellRunConfigGen [--dry-run] [--output-dir=OUTPUT_DIR] --source-dir=SOURCE_DIR RUN_CONFIG
Generate blockchain config from a run config
      RUN_CONFIG   Run config file
      --dry-run    Do not create files
      --output-dir=OUTPUT_DIR
                   Output directory
      --source-dir=SOURCE_DIR
                   Rell source directory
```

Example of a generated directory tree:

```
out/
├── blockchains
│   ├── 1
│   │   ├── 0.xml
│   │   ├── 1000.xml
│   │   └── brid.txt
│   └── 2
│       ├── 0.xml
│       ├── 1000.xml
│       ├── 2000.xml
│       ├── 3000.xml
│       └── brid.txt
├── node-config.properties
└── private.properties
``` 

#### Run.XML Format

Example of a Run.XML file:

```xml
<run wipe-db="true">
    <nodes>
        <config src="config/node-config.properties" add-signers="false" />
    </nodes>
    <chains>
        <chain name="user" iid="1" brid="01234567abcdef01234567abcdef01234567abcdef01234567abcdef01234567">
            <config height="0">
                <module src="user/user" />
                <gtv path="gtx/rell/moduleArgs">
                    <dict>
                        <entry key="foo"><bytea>0373599a61cc6b3bc02a78c34313e1737ae9cfd56b9bb24360b437d469efdf3b15</bytea></entry>
                    </dict>
                </gtv>
            </config>
            <config height="1000">
                <module src="user_1000/user">
                    <args>
                        <arg key="foo"><bytea>0373599a61cc6b3bc02a78c34313e1737ae9cfd56b9bb24360b437d469efdf3b15</bytea></arg>
                    </args>
                </module>
                <gtv path="path" src="config/template.xml"/>
            </config>
        </chain>
        <chain name="city" iid="2" brid="abcdef01234567abcdef01234567abcdef01234567abcdef01234567abcdef01">
            <config height="0" add-dependencies="false">
                <module src="city/city" />
                <gtv path="signers">
                    <array>
                        <bytea>0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57</bytea>
                    </array>
                </gtv>
            </config>
            <include src="config/city-include-1.xml"/>
            <include src="config/city-include-2.xml" root="false"/>
            <dependencies>
                <dependency name="user" chain="user" />
            </dependencies>
        </chain>
    </chains>
</run>
```

Top-level elements are:

* `nodes` - defines Postchain nodes
* `chains` - defines blockchains

##### Nodes

Node configuration is provided in a standard Postchain `node-config.properties` format.

Specifying a path to an existing `node-config.properties` file (path is relative to the Run.XML file):

```xml
<nodes>
    <config src="config/node-config.properties" add-signers="false" />
</nodes>
```

Specifying node configuration properties directly, as text:

```xml
<nodes>
    <config add-signers="false">
        database.driverclass=org.postgresql.Driver
        database.url=jdbc:postgresql://localhost/postchain
        database.username=postchain
        database.password=postchain
        database.schema=test_app
        
        activechainids=1
        
        api.port=7740
        api.basepath=
        
        node.0.id=node0
        node.0.host=127.0.0.1
        node.0.port=9870
        node.0.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57
        
        messaging.privkey=3132333435363738393031323334353637383930313233343536373839303131
        messaging.pubkey=0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57
    </config>
</nodes>
```

##### Chains

A `chain` element can have multiple `config` elements and a `dependencies` element inside.

A single chain may have specific configurations assigned to specific block heights.

```xml
<config height="0" add-dependencies="false">
    <module src="city/city" />
    <gtv path="signers">
        <array>
            <bytea>0350fe40766bc0ce8d08b3f5b810e49a8352fdd458606bd5fafe5acdcdc8ff3f57</bytea>
        </array>
    </gtv>
</config>
```

A `module` element specifies a Rell module used by the chain. Attribute `src` is a path to the main Rell file of the
module, without the `.rell` suffix. The path is relative to the Rell source directory specified via the `--source-dir`
command line argument. The source code of the main Rell file and all Rell files it includes will be injected into the
generated blockchain XML configuration.

Elements `gtv` are used to inject GTXML fragments directly into the generated Postchain blockchain XML configuration.
Attribute `path` specifies a dictionary path for the fragment (default is root). For example, the fragment

```xml
<gtv path="gtx/rell/moduleArgs">
    <dict>
        <entry key="foo"><bytea>0373599a61cc6b3bc02a78c34313e1737ae9cfd56b9bb24360b437d469efdf3b15</bytea></entry>
    </dict>
</gtv>
```

will produce a blockchain XML:

```xml
<dict>
    <entry key="gtx">
        <dict>
            <entry key="rell">
                <dict>
                    <entry key="moduleArgs">
                        <dict>
                            <entry key="foo">
                                <bytea>0373599A61CC6B3BC02A78C34313E1737AE9CFD56B9BB24360B437D469EFDF3B15</bytea>
                            </entry>
                        </dict>
                    </entry>
                </dict>
            </entry>
        </dict>
    </entry>
</dict>
``` 

GTXML contents to be injected shall be either specified as a nested element of a `gtv` element, or placed in an XML file
referenced via `src` attribute.

##### Included files

Other XML files can be included anywhere in a Run.XML using `include` tag. Included files may include other XML files
as well.

Including a file with its root element replacing the `include` element:

```xml
<include src="config/city-include-1.xml"/>
```

Including a file without its root element, the `include` is replaced by the child elements of the root element
of the file:

```xml
<include src="config/city-include-2.xml" root="false"/>
```
