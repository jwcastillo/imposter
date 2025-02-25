/*
 * Copyright (c) 2016-2024.
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
package io.gatehill.imposter.http

import com.google.common.base.Strings.isNullOrEmpty
import io.gatehill.imposter.config.ResolvedResourceConfig
import io.gatehill.imposter.http.util.PathNormaliser
import io.gatehill.imposter.plugin.config.PluginConfig
import io.gatehill.imposter.plugin.config.resource.BasicResourceConfig
import io.gatehill.imposter.plugin.config.resource.conditional.MatchOperator
import io.gatehill.imposter.plugin.config.resource.expression.ExpressionMatcherConfig
import io.gatehill.imposter.plugin.config.resource.expression.ExpressionMatchersConfigHolder
import io.gatehill.imposter.plugin.config.resource.request.BaseRequestBodyConfig
import io.gatehill.imposter.plugin.config.resource.request.RequestBodyResourceConfig
import io.gatehill.imposter.plugin.config.system.SystemConfigHolder
import io.gatehill.imposter.service.script.EvalScriptService
import io.gatehill.imposter.util.BodyQueryUtil
import io.gatehill.imposter.util.InjectorUtil
import io.gatehill.imposter.util.LogUtil
import io.gatehill.imposter.util.MatchUtil
import io.gatehill.imposter.util.PlaceholderUtil
import org.apache.logging.log4j.LogManager


/**
 * Base class for matching resources using elements of the HTTP request.
 *
 * @author Pete Cornish
 */
abstract class AbstractResourceMatcher : ResourceMatcher {
    private val evalScriptService: EvalScriptService by lazy { InjectorUtil.getInstance() }

    override fun matchAllResourceConfigs(
        pluginConfig: PluginConfig,
        resources: List<ResolvedResourceConfig>,
        httpExchange: HttpExchange,
    ): List<BasicResourceConfig> {
        val resourceConfigs = filterResourceConfigs(pluginConfig, resources, httpExchange)
        LOGGER.trace(
            "Matched {} resource configs for {}: {}",
            resourceConfigs.size,
            LogUtil.describeRequestShort(httpExchange),
            resourceConfigs
        )
        return resourceConfigs.map { it.resource.config };
    }

    override fun matchSingleResourceConfig(
        pluginConfig: PluginConfig,
        resources: List<ResolvedResourceConfig>,
        httpExchange: HttpExchange,
    ): BasicResourceConfig? {
        val resourceConfigs = filterResourceConfigs(pluginConfig, resources, httpExchange)
        when (resourceConfigs.size) {
            0 -> {
                LOGGER.trace("No matching resource config for {}", LogUtil.describeRequestShort(httpExchange))
                return null
            }

            1 -> {
                LOGGER.debug("Matched resource config for {}", LogUtil.describeRequestShort(httpExchange))
                return resourceConfigs[0].resource.config
            }

            else -> {
                // multiple candidates - prefer exact matches
                val exactMatches = resourceConfigs.filter { it.exact }
                when (exactMatches.size) {
                    0 -> {
                        LOGGER.warn(
                            "More than one resource config matched a wildcard path for {} - this is probably a configuration error. Guessing first resource configuration.",
                            LogUtil.describeRequestShort(httpExchange)
                        )
                        return resourceConfigs[0].resource.config
                    }

                    1 -> {
                        LOGGER.debug("Matched resource config for {}", LogUtil.describeRequestShort(httpExchange))
                        return exactMatches[0].resource.config
                    }

                    else -> {
                        // find the most specific
                        val sorted = exactMatches.sortedByDescending { it.score }
                        if (sorted[0].score > sorted[1].score) {
                            LOGGER.debug("Matched resource config for {}", LogUtil.describeRequestShort(httpExchange))
                        } else {
                            LOGGER.warn(
                                "More than one resource config matched an exact path for {} - this is probably a configuration error. Guessing first resource configuration.",
                                LogUtil.describeRequestShort(httpExchange)
                            )
                        }
                        return sorted[0].resource.config
                    }
                }
            }
        }
    }

    private fun filterResourceConfigs(
        pluginConfig: PluginConfig,
        resources: List<ResolvedResourceConfig>,
        httpExchange: HttpExchange,
    ): List<MatchedResource> {
        return resources.map { matchRequest(pluginConfig, it, httpExchange) }.filter { it.matched }
    }

    /**
     * Determine if the resource configuration matches the current request.
     *
     * @param pluginConfig
     * @param resource     the resource configuration
     * @param httpExchange the current exchange
     * @return `true` if the resource matches the request, otherwise `false`
     */
    protected abstract fun matchRequest(
        pluginConfig: PluginConfig,
        resource: ResolvedResourceConfig,
        httpExchange: HttpExchange,
    ): MatchedResource

    protected fun matchPath(
        httpExchange: HttpExchange,
        resourceConfig: BasicResourceConfig,
        request: HttpRequest,
    ): ResourceMatchResult {
        val matchDescription = "path"

        val pathMatch = resourceConfig.path?.takeIf(String::isNotEmpty)?.let { resourceConfigPath ->
            val resourcePathWithoutWildcard = resourceConfigPath.substring(0, resourceConfigPath.length - 1)
            if (resourceConfigPath.endsWith("*") && request.path.startsWith(resourcePathWithoutWildcard)) {
                return@let ResourceMatchResult.wildcardMatch(matchDescription)
            } else if (request.path == resourceConfigPath) {
                return@let ResourceMatchResult.exactMatch(matchDescription)
            } else {
                val currentRoute = httpExchange.currentRoute
                if (null != currentRoute && isPathTemplateMatch(currentRoute, resourceConfigPath)) {
                    return@let ResourceMatchResult.exactMatch(matchDescription)
                } else {
                    return@let ResourceMatchResult.notMatched(matchDescription)
                }
            }
            // path is un-set
        } ?: ResourceMatchResult.noConfig(matchDescription)

        return pathMatch
    }

    private fun isPathTemplateMatch(currentRoute: HttpRoute, resourceConfigPath: String): Boolean {
        val normalisedParams = currentRoute.router.normalisedParams.toMutableMap()

        // TODO consider caching the normalised resource path
        val normalisedResourcePath = PathNormaliser.normalisePath(normalisedParams, resourceConfigPath)

        // note: route path template can be null when a regex route is used
        return currentRoute.path == normalisedResourcePath
    }

    /**
     * Match the request body against the supplied configuration.
     *
     * @param httpExchange   thc current exchange
     * @param resourceConfig the match configuration
     * @return `true` if the configuration is empty, or the request body matches the configuration, otherwise `false`
     */
    protected fun matchRequestBody(
        httpExchange: HttpExchange,
        pluginConfig: PluginConfig,
        resourceConfig: BasicResourceConfig,
    ): ResourceMatchResult {
        val matchDescription = "request body"
        if (resourceConfig !is RequestBodyResourceConfig) {
            // none configured
            return ResourceMatchResult.noConfig(matchDescription)
        }

        resourceConfig.requestBody?.allOf?.let { bodyConfigs ->
            if (LOGGER.isTraceEnabled) {
                LOGGER.trace(
                    "Matching against all of ${bodyConfigs.size} request body configs for ${
                        LogUtil.describeRequestShort(
                            httpExchange
                        )
                    }: $bodyConfigs"
                )
            }
            val parentConfigNamespaces = resourceConfig.requestBody?.xmlNamespaces
            val allMatch = bodyConfigs.all { childConfig ->
                val matchType = matchUsingBodyConfig(
                    matchDescription,
                    childConfig,
                    pluginConfig,
                    parentConfigNamespaces,
                    httpExchange
                )
                return@all matchType.type == MatchResultType.EXACT_MATCH
            }
            return if (allMatch) {
                // each matched config contributes to the weight
                ResourceMatchResult.exactMatch(matchDescription, bodyConfigs.size)
            } else {
                ResourceMatchResult.notMatched(matchDescription)
            }

        } ?: resourceConfig.requestBody?.anyOf?.let { bodyConfigs ->
            if (LOGGER.isTraceEnabled) {
                LOGGER.trace(
                    "Matching against any of ${bodyConfigs.size} request body configs for ${
                        LogUtil.describeRequestShort(
                            httpExchange
                        )
                    }: $bodyConfigs"
                )
            }
            val parentConfigNamespaces = resourceConfig.requestBody?.xmlNamespaces
            val anyMatch = bodyConfigs.any { childConfig ->
                val matchType = matchUsingBodyConfig(
                    matchDescription,
                    childConfig,
                    pluginConfig,
                    parentConfigNamespaces,
                    httpExchange
                )
                return@any matchType.type == MatchResultType.EXACT_MATCH
            }
            return if (anyMatch) {
                ResourceMatchResult.exactMatch(matchDescription)
            } else {
                ResourceMatchResult.notMatched(matchDescription)
            }

        } ?: resourceConfig.requestBody?.let { singleRequestBodyConfig ->
            if (LOGGER.isTraceEnabled) {
                LOGGER.trace(
                    "Matching against a single request body config for ${LogUtil.describeRequestShort(httpExchange)}: $singleRequestBodyConfig"
                )
            }
            return matchUsingBodyConfig(
                matchDescription,
                singleRequestBodyConfig,
                pluginConfig,
                emptyMap(),
                httpExchange
            )

        } ?: run {
            if (LOGGER.isTraceEnabled) {
                LOGGER.trace("No request body config to match for ${LogUtil.describeRequestShort(httpExchange)}")
            }
            // none configured
            return ResourceMatchResult.noConfig(matchDescription)
        }
    }

    private fun matchUsingBodyConfig(
        matchDescription: String,
        bodyConfig: BaseRequestBodyConfig,
        pluginConfig: PluginConfig,
        additionalNamespaces: Map<String, String>?,
        httpExchange: HttpExchange,
    ): ResourceMatchResult {
        return if (!isNullOrEmpty(bodyConfig.jsonPath)) {
            LOGGER.trace("Matching body using jsonPath")
            matchRequestBodyJsonPath(matchDescription, bodyConfig, httpExchange)
        } else if (!isNullOrEmpty(bodyConfig.xPath)) {
            LOGGER.trace("Matching body using xPath")
            matchRequestBodyXPath(matchDescription, bodyConfig, pluginConfig, additionalNamespaces, httpExchange)
        } else if (null != bodyConfig.operator) {
            LOGGER.trace("Matching body using whole string")
            checkBodyMatch(matchDescription, bodyConfig, httpExchange.request.bodyAsString)
        } else {
            // none configured
            ResourceMatchResult.noConfig(matchDescription)
        }
    }

    private fun matchRequestBodyJsonPath(
        matchDescription: String,
        bodyConfig: BaseRequestBodyConfig,
        httpExchange: HttpExchange,
    ): ResourceMatchResult {
        val bodyValue = BodyQueryUtil.queryRequestBodyJsonPath(
            bodyConfig.jsonPath!!,
            httpExchange
        )
        // resource matching always uses strings
        return checkBodyMatch(matchDescription, bodyConfig, bodyValue?.toString())
    }

    private fun matchRequestBodyXPath(
        matchDescription: String,
        bodyConfig: BaseRequestBodyConfig,
        pluginConfig: PluginConfig,
        additionalNamespaces: Map<String, String>?,
        httpExchange: HttpExchange,
    ): ResourceMatchResult {
        val allNamespaces = bodyConfig.xmlNamespaces?.toMutableMap() ?: mutableMapOf()
        additionalNamespaces?.let(allNamespaces::putAll)

        if (pluginConfig is SystemConfigHolder) {
            pluginConfig.systemConfig?.xmlNamespaces?.let { allNamespaces.putAll(it) }
        }
        val bodyValue = BodyQueryUtil.queryRequestBodyXPath(
            bodyConfig.xPath!!,
            allNamespaces,
            httpExchange
        )
        return checkBodyMatch(matchDescription, bodyConfig, bodyValue)
    }

    private fun checkBodyMatch(
        matchDescription: String,
        bodyConfig: BaseRequestBodyConfig,
        actualValue: Any?,
    ): ResourceMatchResult {
        // defaults to equality check
        val operator = bodyConfig.operator ?: MatchOperator.EqualTo

        val match = if (MatchUtil.conditionMatches(bodyConfig.value, operator, actualValue?.toString())) {
            ResourceMatchResult.exactMatch(matchDescription)
        } else {
            ResourceMatchResult.notMatched(matchDescription)
        }
        if (LOGGER.isTraceEnabled) {
            LOGGER.trace("Body match result for {} '{}': {}", operator, bodyConfig.value, match)
        }
        return match
    }

    /**
     * Match expressions against the request.
     */
    protected fun matchExpressions(
        httpExchange: HttpExchange,
        resourceConfig: BasicResourceConfig
    ): ResourceMatchResult {
        if (resourceConfig !is ExpressionMatchersConfigHolder) {
            return ResourceMatchResult.noConfig("expressions")
        }

        val allOf = resourceConfig.allOf
        val anyOf = resourceConfig.anyOf

        if (!allOf.isNullOrEmpty()) {
            val allOfResults = allOf.map { evalConfig ->
                matchUsingExpressionConfig(httpExchange, evalConfig)
            }
            if (!allOfResults.all { it }) {
                return ResourceMatchResult.notMatched("allOf")
            }
            // each matched config contributes to the weight
            return ResourceMatchResult.exactMatch("allOf", allOf.size)

        } else if (!anyOf.isNullOrEmpty()) {
            val anyOfResults = anyOf.map { evalConfig ->
                matchUsingExpressionConfig(httpExchange, evalConfig)
            }
            if (!anyOfResults.any { it }) {
                return ResourceMatchResult.notMatched("anyOf")
            }
            return ResourceMatchResult.exactMatch("anyOf")

        } else {
            return ResourceMatchResult.noConfig("expressions")
        }
    }

    private fun matchUsingExpressionConfig(
        httpExchange: HttpExchange,
        evalConfig: ExpressionMatcherConfig
    ): Boolean {
        val expression = evalConfig.expression ?: return false
        val operator = evalConfig.operator ?: MatchOperator.EqualTo
        val expectedValue = evalConfig.value

        // evaluate the expression
        val actualValue = PlaceholderUtil.replace(expression, httpExchange, PlaceholderUtil.templateEvaluators)

        // compare using the operator
        return MatchUtil.conditionMatches(expectedValue, operator, actualValue)
    }

    protected fun matchEval(
        httpExchange: HttpExchange,
        pluginConfig: PluginConfig,
        resource: ResolvedResourceConfig,
    ) = evalScriptService.evalScript(httpExchange, pluginConfig, resource.config)

    fun determineMatch(
        results: List<ResourceMatchResult>,
        resource: ResolvedResourceConfig,
        httpExchange: HttpExchange,
    ): MatchedResource {
        // true if exact match or wildcard match, or partial config (implies match all)
        val matched = results.none { it.type == MatchResultType.NOT_MATCHED } &&
                !results.all { it.type == MatchResultType.NO_CONFIG }

        // all matched and none of type wildcard
        val exact = matched && results.none { it.type == MatchResultType.WILDCARD_MATCH }

        // score is the number of exact or wildcard matches
        val score =
            results.filter { it.type == MatchResultType.EXACT_MATCH || it.type == MatchResultType.WILDCARD_MATCH }
                .sumOf { it.weight }

        val outcome = MatchedResource(resource, matched, score, exact)
        if (LOGGER.isTraceEnabled) {
            LOGGER.trace(
                "Request match evaluation for '{}' to resource {}, from results: {}, outcome: {}",
                LogUtil.describeRequest(httpExchange),
                resource.config,
                results,
                outcome,
            )
        }
        return outcome
    }

    data class MatchedResource(
        val resource: ResolvedResourceConfig,
        val matched: Boolean,
        val score: Int,
        val exact: Boolean,
    )

    companion object {
        private val LOGGER = LogManager.getLogger(AbstractResourceMatcher::class.java)
    }
}
