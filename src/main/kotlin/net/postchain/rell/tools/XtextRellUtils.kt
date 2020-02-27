/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools

import org.apache.commons.configuration2.PropertiesConfiguration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler
import java.io.File

object XtextRellUtils {
    @JvmStatic
    fun isValidDatabaseProperties(file: File): Boolean {
        val params = Parameters().properties()
                .setFile(file)
                .setListDelimiterHandler(DefaultListDelimiterHandler(','))

        val conf = FileBasedConfigurationBuilder(PropertiesConfiguration::class.java)
                .configure(params)
                .configuration

        val res = conf.containsKey("database.url")
        return res
    }
}
