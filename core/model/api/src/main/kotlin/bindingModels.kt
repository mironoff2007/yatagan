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

package com.yandex.yatagan.core.model

import com.yandex.yatagan.base.api.NullIfInvalid
import com.yandex.yatagan.base.api.StableForImplementation
import com.yandex.yatagan.lang.Annotation
import com.yandex.yatagan.lang.AnnotationDeclaration
import com.yandex.yatagan.lang.Method
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.validation.MayBeInvalid

/**
 * Declared in a [ModuleModel], and thus explicit, binding model.
 * Backed by a `lang`-level construct.
 */
public interface ModuleHostedBindingModel : MayBeInvalid {
    /**
     * Module the binding originates from.
     */
    public val originModule: ModuleModel

    /**
     * Parsed return value of the binding. See [BindingTargetModel] for the details.
     */
    public val target: BindingTargetModel

    /**
     * Declared scope annotations.
     */
    public val scopes: Set<ScopeModel>

    /**
     * Underlying method model.
     *
     * Use only for extracting the info not available via the API of the model.
     */
    public val method: Method

    /**
     * Binding target variants.
     */
    public sealed class BindingTargetModel {
        /**
         * A node corresponding to the return type of the binding model.
         * Doesn't always directly correspond to the effective binding target - multi-bindings can be in effect.
         */
        public abstract val node: NodeModel

        override fun toString(): String = node.toString(childContext = null).toString()

        /**
         * Binding for the plain [node].
         */
        public class Plain(
            override val node: NodeModel,
        ) : BindingTargetModel()

        /**
         * Single element list contribution to the List of [node]s.
         */
        public class DirectMultiContribution(
            override val node: NodeModel,
            public val kind: CollectionTargetKind,
        ) : BindingTargetModel()

        /**
         * Collection contribution to the List of [flattened], which is an unwrapped [node].
         */
        public class FlattenMultiContribution(
            override val node: NodeModel,

            /**
             * An unwrapped [node] (its type argument, given [node] is a collection).
             */
            public val flattened: NodeModel,

            /**
             * Collection kind.
             */
            public val kind: CollectionTargetKind,
        ) : BindingTargetModel()

        /**
         * Contribution of a single [node] as a value to a map with the [keyType] under [keyValue].
         */
        public class MappingContribution(
            /**
             * Mapping value type as a node.
             */
            override val node: NodeModel,

            /**
             * Mapping key type.
             */
            @NullIfInvalid
            public val keyType: Type?,

            /**
             * Mapping key value.
             */
            @NullIfInvalid
            public val keyValue: Annotation.Value?,

            /**
             * Annotation class of the Map-key annotation.
             */
            @NullIfInvalid
            public val mapKeyClass: AnnotationDeclaration?,
        ) : BindingTargetModel()
    }

    @StableForImplementation
    public interface Visitor<R> {
        public fun visitOther(model: ModuleHostedBindingModel): R
        public fun visitBinds(model: BindsBindingModel): R = visitOther(model)
        public fun visitProvides(model: ProvidesBindingModel): R = visitOther(model)
    }

    public fun <R> accept(visitor: Visitor<R>): R
}

/**
 * [com.yandex.yatagan.Binds] binding model.
 */
public interface BindsBindingModel : ModuleHostedBindingModel {
    public val sources: Sequence<NodeModel>
}

/**
 * [com.yandex.yatagan.Provides] binding model.
 */
public interface ProvidesBindingModel : ModuleHostedBindingModel, ConditionalHoldingModel {
    public val inputs: List<NodeDependency>
    public val requiresModuleInstance: Boolean
}

