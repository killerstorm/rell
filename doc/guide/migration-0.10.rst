=========================
Upgrading to Rell 0.10
=========================

There are two kinds of breaking changes in Rell 0.10.0:

1. Rell Language:

   - Module System; ``include`` is deprecated and will not work.
   - Mount names: mapping of entities and objects to SQL tables changed.
   - ``class`` and ``record`` renamed to ``entity`` and ``struct``, the code using old keywords will not compile.
   - Previously deprecated library functions are now unavailable; the code using them will not compile.

2. Configuration and tools:

   - Postchain ``blockchain.xml``: now needs a list of modules instead of the main file name; module arguments are
     per-module.
   - Run.XML format: specifying module instead of main file; module arguments are per-module.
   - Command-line tools: accept a source directory path and main module name combination instead of the main .rell file path.

Step-by-step upgrade
====================

1. Read about the :doc:`Module System <modules>`.
2. Read about :ref:`mount names <general-mount-names>`.
3. Use the ``migrate-v0.10.sh`` tool to rename ``class``, ``record`` and deprecated function names (see below).
4. Manually update the source code to use the Module System instead of ``include``.
5. Use ``@mount`` annotation to set correct mount names to entities, objects, operations and queries
   (recommended to apply ``@mount`` to entire modules or namespaces, not to individual definitions).
6. Update configuration files, if necessary (see the details below).
7. The Web IDE users the root module as the main module, so make sure you have it and import all required modules there.

Details
===================

migrate-v0.10.sh tool
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

The tool can be found in the ``postchain-node`` directory of a Rell distribution.
It renames ``class``, ``record`` and most of deprecated functions, e. g. ``requireNotEmpty()`` -> ``require_not_empty``.

.. code-block:: none

    Usage: migrator [--dry-run] DIRECTORY
    Replaces deprecated keywords and names in all .rell files in the directory (recursively)
          DIRECTORY   Directory
          --dry-run   Do not modify files, only print replace counts

Specify a Rell source directory as an argument, and the tool will do renaming in all .rell files in that
directory and its subdirectories.

**NOTE.** UTF-8 encoding is always used by the tool; if files use a different encoding, some characters
may be broken. It is recommended to not run the tool if there are uncommitted changes in the directory.
After running it, review the changes it made.

blockchain.xml
~~~~~~~~~~~~~~~~~~~~

New ``blockchain.xml`` Rell configuration looks like this (only changed parts shown):

.. code-block:: xml

    <dict>
        <entry key="gtx">
            <dict>
                <entry key="rell">
                    <dict>
                        <entry key="moduleArgs">
                            <dict>
                                <entry key="app.foo">
                                    <dict>
                                        <entry key="message">
                                            <string>Some common message...</string>
                                        </entry>
                                    </dict>
                                </entry>
                                <entry key="app.bar">
                                    <dict>
                                        <entry key="x">
                                            <int>123456</int>
                                        </entry>
                                        <entry key="y">
                                            <string>Hello!</string>
                                        </entry>
                                    </dict>
                                </entry>
                            </dict>
                        </entry>
                        <entry key="modules">
                            <array>
                                <string>app.foo</string>
                                <string>app.bar</string>
                            </array>
                        </entry>
                    </dict>
                </entry>
            </dict>
        </entry>
    </dict>

What was changed:

- ``gtx.rell.moduleArgs`` is now a dictionary, specifying ``module_args`` for multiple modules
  (in older versions there was only one ``module_args`` for a Rell application, now there can be one ``module_args``
  per module).
- ``gtx.rell.modules`` is an array of module names

run.xml
~~~~~~~~~~~~~~~~~~~~

An example of a new ``run.xml`` file:

.. code-block:: xml

    <run wipe-db="true">
        <nodes>
            <config src="node-config.properties" add-signers="false" />
        </nodes>
        <chains>
            <chain name="test" iid="1" brid="01234567abcdef01234567abcdef01234567abcdef01234567abcdef01234567">
                <config height="0">
                    <app module="app.main">
                        <args module="app.bar">
                            <arg key="x"><int>123456</int></arg>
                            <arg key="y"><string>Hello!</string></arg>
                        </args>
                        <args module="app.foo">
                            <arg key="message"><string>Some common message...</string></arg>
                        </args>
                    </app>
                </config>
            </chain>
        </chains>
    </run>

What was changed:

- ``module`` tag replaced by ``app``, which has ``module`` attribute
- there can be multiple ``args`` elements, each must have a ``module`` attribute
