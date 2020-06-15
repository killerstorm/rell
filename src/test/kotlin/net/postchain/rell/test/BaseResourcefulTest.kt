/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import org.junit.After
import java.io.Closeable

abstract class BaseResourcefulTest {
    private val resources = mutableListOf<AutoCloseable>()

    @After fun after() {
        for (resource in resources) {
            try {
                resource.close()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    protected fun <T: AutoCloseable> resource(resource: T): T {
        resources.add(resource)
        return resource
    }
}
