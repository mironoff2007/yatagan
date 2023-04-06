/*
 * Copyright 2022 Yandex LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yandex.yatagan.rt.engine

import com.yandex.yatagan.AutoBuilder
import com.yandex.yatagan.Component
import com.yandex.yatagan.base.ObjectCacheRegistry
import com.yandex.yatagan.base.loadServices
import com.yandex.yatagan.core.graph.BindingGraph
import com.yandex.yatagan.core.graph.impl.BindingGraph
import com.yandex.yatagan.core.graph.impl.Options
import com.yandex.yatagan.core.model.impl.ComponentModel
import com.yandex.yatagan.lang.InternalLangApi
import com.yandex.yatagan.lang.LangModelFactory
import com.yandex.yatagan.lang.rt.RtModelFactoryImpl
import com.yandex.yatagan.lang.rt.TypeDeclaration
import com.yandex.yatagan.rt.support.DynamicValidationDelegate
import com.yandex.yatagan.rt.support.Logger
import com.yandex.yatagan.validation.LocatedMessage
import com.yandex.yatagan.validation.RichString
import com.yandex.yatagan.validation.ValidationMessage
import com.yandex.yatagan.validation.format.format
import com.yandex.yatagan.validation.impl.GraphValidationExtension
import com.yandex.yatagan.validation.impl.validate
import com.yandex.yatagan.validation.spi.ValidationPluginProvider
import java.lang.reflect.Proxy
import kotlin.system.measureTimeMillis

/**
 * Main entrypoint class for reflection backend implementation.
 */
class RuntimeEngine<P : RuntimeEngine.Params>(
    val params: P,
) {
    init {
        @OptIn(InternalLangApi::class)
        LangModelFactory.delegate = RtModelFactoryImpl(javaClass.classLoader)
    }

    interface Params {
        val validationDelegate: DynamicValidationDelegate?
        val maxIssueEncounterPaths: Int
        val isStrictMode: Boolean
        val logger: Logger?
        val reportDuplicateAliasesAsErrors: Boolean
    }

    fun destroy() {
        @OptIn(InternalLangApi::class)
        LangModelFactory.delegate = null
        ObjectCacheRegistry.close()
    }

    fun <T : Any> builder(builderClass: Class<T>): T {
        val builder: T
        val time = measureTimeMillis {
            require(builderClass.isAnnotationPresent(Component.Builder::class.java)) {
                "$builderClass is not a builder for a Yatagan component"
            }

            val builderDeclaration = TypeDeclaration(builderClass)
            val componentClass = requireNotNull(builderDeclaration.enclosingType) {
                "No enclosing component class found for $builderClass"
            }
            val componentModel = ComponentModel(componentClass)
            require(componentModel.isRoot) {
                "$componentClass is not a root Yatagan component"
            }
            val graph = BindingGraph(
                root = componentModel,
                options = createGraphOptions(),
            )
            val promise = doValidate(graph)
            val factory = promise.awaitOnError {
                RuntimeFactory(
                    graph = graph,
                    parent = null,
                    validationPromise = promise,
                    logger = params.logger,
                )
            }
            val proxy = Proxy.newProxyInstance(builderClass.classLoader, arrayOf(builderClass), factory)
            builder = builderClass.cast(proxy)
        }
        params.logger?.log("Dynamic builder creation for `$builderClass` took $time ms")
        return builder
    }

    fun <T : Any> autoBuilder(componentClass: Class<T>): AutoBuilder<T> {
        val builder: AutoBuilder<T>
        val time = measureTimeMillis {
            require(componentClass.getAnnotation(Component::class.java)?.isRoot == true) {
                "$componentClass is not a root Yatagan component"
            }

            val componentModel = ComponentModel(TypeDeclaration(componentClass))
            if (componentModel.factory != null) {
                throw IllegalArgumentException(
                    "Auto-builder can't be used for $componentClass, because it declares an explicit builder. " +
                            "Please use `Yatagan.builder()` instead"
                )
            }

            val graph = BindingGraph(
                root = componentModel,
                options = createGraphOptions(),
            )
            val promise = doValidate(graph)
            builder = RuntimeAutoBuilder(
                componentClass = componentClass,
                graph = graph,
                validationPromise = promise,
                logger = params.logger,
            )
        }
        params.logger?.log("Dynamic auto-builder creation for `$componentClass` took $time ms")
        return builder
    }

    private fun reportMessages(
        messages: Collection<LocatedMessage>,
        reporting: DynamicValidationDelegate.ReportingDelegate,
    ) {
        messages.forEach { locatedMessage ->
            val text: RichString = locatedMessage.format(
                maxEncounterPaths = params.maxIssueEncounterPaths,
            )
            when (locatedMessage.message.kind) {
                ValidationMessage.Kind.Error -> reporting.reportError(text)
                ValidationMessage.Kind.Warning -> reporting.reportWarning(text)
                ValidationMessage.Kind.MandatoryWarning -> if (params.isStrictMode) {
                    reporting.reportError(text)
                } else {
                    reporting.reportWarning(text)
                }
            }
        }
    }

    private fun doValidate(graph: BindingGraph) = params.validationDelegate?.let { delegate ->
        delegate.dispatchValidation(title = graph.model.type.toString()) { reporting ->
            reportMessages(messages = validate(graph), reporting = reporting)
            if (delegate.usePlugins) {
                val extension = GraphValidationExtension(
                    validationPluginProviders = pluginProviders,
                    graph = graph,
                )
                reportMessages(messages = validate(extension), reporting = reporting)
            }
        }
    }

    private fun createGraphOptions() = Options(
        reportDuplicateAliasesAsErrors = params.reportDuplicateAliasesAsErrors,
    )

    private companion object {
        val pluginProviders: List<ValidationPluginProvider> = loadServices()
    }
}