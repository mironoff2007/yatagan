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

import com.yandex.yatagan.lang.Method
import com.yandex.yatagan.validation.MayBeInvalid

/**
 * Component dependency.
 * Every "getter" is exposed as a graph-level binding.
 *
 * @see com.yandex.yatagan.Component.dependencies
 */
public interface ComponentDependencyModel : MayBeInvalid, ClassBackedModel {
    public val exposedDependencies: Map<NodeModel, Method>

    public fun asNode(): NodeModel
}