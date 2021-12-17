/*
 * Copyright (c) 2016-2021.
 *
 * This file is part of Imposter.
 *
 * "Commons Clause" License Condition v1.0
 *
 * The Software is provided to you by the Licensor under the License, as
 * defined below, subject to the following condition.
 *
 * Without limiting other conditions in the License, the grant of rights
 * under the License will not include, and the License does not grant to
 * you, the right to Sell the Software.
 *
 * For purposes of the foregoing, "Sell" means practicing any or all of
 * the rights granted to you under the License to provide to third parties,
 * for a fee or other consideration (including without limitation fees for
 * hosting or consulting/support services related to the Software), a
 * product or service whose value derives, entirely or substantially, from
 * the functionality of the Software. Any license notice or attribution
 * required by the License must also include this Commons Clause License
 * Condition notice.
 *
 * Software: Imposter
 *
 * License: GNU Lesser General Public License version 3
 *
 * Licensor: Peter Cornish
 *
 * Imposter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Imposter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Imposter.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.gatehill.imposter.util

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.gatehill.imposter.config.util.EnvVars
import java.util.*

/**
 * @author Pete Cornish
 */
object MapUtil {
    @JvmField
    val JSON_MAPPER = ObjectMapper()

    @JvmField
    val YAML_MAPPER = YAMLMapper()

    /**
     * Don't apply standard configuration to this mapper.
     */
    val STATS_MAPPER = ObjectMapper().also {
        if (EnvVars.getEnv("IMPOSTER_LOG_SUMMARY_PRETTY")?.toBoolean() == true) {
            it.enable(SerializationFeature.INDENT_OUTPUT)
        }
    }

    private val DESERIALISERS = arrayOf(
        JSON_MAPPER,
        YAML_MAPPER
    )

    private fun configureMapper(mapper: ObjectMapper) {
        addJavaTimeSupport(mapper)
        mapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        mapper.registerKotlinModule()
    }

    /**
     * Adds support for JSR-310 data types
     *
     * @param mapper the [@ObjectMapper] to modify
     */
    @JvmStatic
    fun addJavaTimeSupport(mapper: ObjectMapper) {
        mapper.registerModule(JavaTimeModule())
    }

    init {
        JSON_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL)
        JSON_MAPPER.enable(SerializationFeature.INDENT_OUTPUT)
        Arrays.stream(DESERIALISERS).forEach(this::configureMapper)
    }
}