/*
 * Copyright (c) 2016-2023.
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
package io.gatehill.imposter.service.script

import com.google.common.cache.CacheBuilder
import io.gatehill.imposter.ImposterConfig
import io.gatehill.imposter.config.util.EnvVars
import io.gatehill.imposter.http.HttpExchange
import io.gatehill.imposter.http.HttpRouter
import io.gatehill.imposter.lifecycle.EngineLifecycleHooks
import io.gatehill.imposter.lifecycle.EngineLifecycleListener
import io.gatehill.imposter.lifecycle.ScriptLifecycleHooks
import io.gatehill.imposter.plugin.config.PluginConfig
import io.gatehill.imposter.plugin.config.ResourcesHolder
import io.gatehill.imposter.plugin.config.resource.BasicResourceConfig
import io.gatehill.imposter.plugin.config.resource.EvalResourceConfig
import io.gatehill.imposter.plugin.config.resource.ResponseConfig
import io.gatehill.imposter.script.ExecutionContext
import io.gatehill.imposter.script.ReadWriteResponseBehaviour
import io.gatehill.imposter.script.RuntimeContext
import io.gatehill.imposter.script.ScriptUtil
import io.gatehill.imposter.service.ScriptSource
import io.gatehill.imposter.service.ScriptedResponseService
import io.gatehill.imposter.util.LogUtil
import io.gatehill.imposter.util.MetricsUtil
import io.micrometer.core.instrument.Timer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.util.Supplier
import java.util.concurrent.ExecutionException
import javax.inject.Inject

/**
 * @author Pete Cornish
 */
class ScriptedResponseServiceImpl @Inject constructor(
    engineLifecycle: EngineLifecycleHooks,
    private val scriptLifecycle: ScriptLifecycleHooks,
    private val scriptServiceFactory: ScriptServiceFactory,
    private val inlineScriptService: InlineScriptService,
) : ScriptedResponseService, EngineLifecycleListener {

    /**
     * Caches loggers to avoid logging framework lookup cost.
     */
    private val loggerCache = CacheBuilder.newBuilder().maximumSize(20).build<String, Logger>()

    private var executionTimer: Timer? = null

    init {
        MetricsUtil.doIfMetricsEnabled(METRIC_SCRIPT_EXECUTION_DURATION) { registry ->
            executionTimer = Timer
                .builder(METRIC_SCRIPT_EXECUTION_DURATION)
                .description("Script engine execution duration in seconds")
                .register(registry)
        }.orElseDo { executionTimer = null }

        engineLifecycle.registerListener(this)
    }

    override fun afterRoutesConfigured(
        imposterConfig: ImposterConfig,
        allPluginConfigs: List<PluginConfig>,
        router: HttpRouter
    ) {
        initScripts(allPluginConfigs)
    }

    private fun initScripts(allPluginConfigs: List<PluginConfig>) {
        val allScriptFiles = mutableListOf<Pair<PluginConfig, ResponseConfig>>()

        // root resource
        allPluginConfigs.filter { it is BasicResourceConfig }.forEach { config ->
            allScriptFiles += config to (config as BasicResourceConfig).responseConfig
        }
        // child resources
        allPluginConfigs.filter { it is ResourcesHolder<*> }.forEach { config ->
            (config as ResourcesHolder<*>).resources?.forEach { resource ->
                allScriptFiles += config to resource.responseConfig

                // inline scripts
                if (resource is EvalResourceConfig) {
                    inlineScriptService.initScript(resource)
                }

                // TODO precache inline script steps as well as evals
            }
        }

        allScriptFiles.distinctBy { (_, responseConfig) -> responseConfig.scriptFile }
            .forEach { (config, responseConfig) -> initScript(config, responseConfig) }
    }

    private fun initScript(pluginConfig: PluginConfig, responseConfig: ResponseConfig) {
        responseConfig.scriptFile?.let { scriptFile ->
            val scriptPath = ScriptUtil.resolveScriptPath(pluginConfig, scriptFile)
            scriptServiceFactory.fetchScriptService(scriptFile).initScript(
                ScriptSource(file = scriptPath)
            )
        }
    }

    override fun determineResponseFromScript(
        httpExchange: HttpExchange,
        pluginConfig: PluginConfig,
        scriptCode: String?,
        scriptFile: String?,
        additionalContext: Map<String, Any>?
    ): ReadWriteResponseBehaviour {
        return try {
            val scriptExecutor = {
                determineResponseFromScriptInternal(
                    httpExchange,
                    pluginConfig,
                    scriptCode,
                    scriptFile,
                    additionalContext
                )
            }
            executionTimer?.recordCallable(scriptExecutor) ?: scriptExecutor()

        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private fun determineResponseFromScriptInternal(
        httpExchange: HttpExchange,
        pluginConfig: PluginConfig,
        scriptCode: String?,
        scriptFile: String?,
        additionalContext: Map<String, Any>?
    ): ReadWriteResponseBehaviour {
        val script = scriptCode?.let {
            ScriptSource(code = scriptCode)
        } ?: scriptFile?.let {
            ScriptSource(file = ScriptUtil.resolveScriptPath(pluginConfig, scriptFile))
        } ?: throw IllegalStateException("Script file or code not set")

        return execute(httpExchange, pluginConfig, script, additionalContext)
    }

    private fun execute(
        httpExchange: HttpExchange,
        pluginConfig: PluginConfig,
        script: ScriptSource,
        additionalContext: Map<String, Any>?
    ): ReadWriteResponseBehaviour {
        try {
            val executionStart = System.nanoTime()
            LOGGER.trace(
                "Executing script '{}' for request: {}",
                script,
                LogUtil.describeRequestShort(httpExchange)
            )
            val executionContext = ScriptUtil.buildContext(httpExchange, additionalContext)
            LOGGER.trace("Context for request: {}", Supplier<Any> { executionContext })

            val additionalBindings = getAdditionalBindings(httpExchange, executionContext)
            val scriptLogger = buildScriptLogger(script.source)

            val runtimeContext = RuntimeContext(
                EnvVars.getEnv(),
                scriptLogger,
                pluginConfig,
                additionalBindings,
                executionContext
            )

            // execute the script using an appropriate implementation and read response behaviour
            val scriptService = scriptServiceFactory.fetchScriptService(script.source)
            val responseBehaviour = scriptService.executeScript(script, runtimeContext)

            // fire post execution hooks
            scriptLifecycle.forEach { listener ->
                listener.afterSuccessfulScriptExecution(additionalBindings, responseBehaviour)
            }

            val scriptDuration = (System.nanoTime() - executionStart) / 1000000f

            // used for summary logging
            httpExchange.put(LogUtil.KEY_SCRIPT_DURATION, scriptDuration)

            LOGGER.debug(
                String.format(
                    "Executed script '%s' for request: %s in %.2fms",
                    script,
                    LogUtil.describeRequestShort(httpExchange),
                    scriptDuration
                )
            )
            return responseBehaviour

        } catch (e: Exception) {
            throw RuntimeException(
                "Error executing script: '$script' for request: " +
                        LogUtil.describeRequestShort(httpExchange), e
            )
        }
    }

    @Throws(ExecutionException::class)
    private fun buildScriptLogger(scriptSource: String): Logger {
        val name: String?
        val dotIndex = scriptSource.lastIndexOf('.')
        name = if (dotIndex >= 1 && dotIndex < scriptSource.length - 1) {
            scriptSource.substring(0, dotIndex)
        } else {
            scriptSource
        }
        val loggerName = LogUtil.LOGGER_SCRIPT_PACKAGE + "." + name
        return loggerCache[loggerName, { LogManager.getLogger(loggerName) }]
    }

    private fun getAdditionalBindings(
        httpExchange: HttpExchange,
        executionContext: ExecutionContext
    ): Map<String, Any> {
        // fire pre-context build hooks
        if (!scriptLifecycle.isEmpty) {
            val additionalBindings = mutableMapOf<String, Any>()
            scriptLifecycle.forEach { listener ->
                listener.beforeBuildingRuntimeContext(
                    httpExchange,
                    additionalBindings,
                    executionContext
                )
            }
            if (additionalBindings.isNotEmpty()) {
                return additionalBindings
            }
        }
        return emptyMap()
    }

    companion object {
        private val LOGGER = LogManager.getLogger(ScriptedResponseServiceImpl::class.java)
        private const val METRIC_SCRIPT_EXECUTION_DURATION = "script.execution.duration"
    }
}
