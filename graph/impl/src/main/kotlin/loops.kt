package com.yandex.daggerlite.graph.impl

import com.yandex.daggerlite.base.ifOrElseNull
import com.yandex.daggerlite.base.traverseDepthFirstWithPath
import com.yandex.daggerlite.core.NodeDependency
import com.yandex.daggerlite.core.component1
import com.yandex.daggerlite.core.component2
import com.yandex.daggerlite.core.isEager
import com.yandex.daggerlite.graph.AliasBinding
import com.yandex.daggerlite.graph.BaseBinding
import com.yandex.daggerlite.graph.Binding
import com.yandex.daggerlite.graph.BindingGraph
import com.yandex.daggerlite.graph.component1
import com.yandex.daggerlite.graph.component2
import com.yandex.daggerlite.validation.Validator
import com.yandex.daggerlite.validation.format.Strings
import com.yandex.daggerlite.validation.format.reportError

internal fun validateNoLoops(graph: BindingGraph, validator: Validator) {
    traverseDepthFirstWithPath(
        roots = buildList<BaseBinding> {
            graph.entryPoints.forEach { (_, dependency) ->
                add(graph.resolveBindingRaw(dependency.node))
            }
            graph.memberInjectors.forEach {
                it.membersToInject.forEach { (_, dependency) ->
                    add(graph.resolveBindingRaw(dependency.node))
                }
            }
        },
        childrenOf = { binding ->
            class DependenciesVisitor : BaseBinding.Visitor<Sequence<NodeDependency>> {
                override fun visitAlias(alias: AliasBinding) = sequenceOf(alias.source)
                override fun visitBinding(binding: Binding) =
                    binding.dependencies + binding.nonStaticConditionProviders
            }
            binding.accept(DependenciesVisitor()).mapNotNull { (node, kind) ->
                ifOrElseNull(kind.isEager) { binding.owner.resolveBindingRaw(node) }
            }.asIterable()
        },
        onLoop = { bindingLoop ->
            validator.reportError(Strings.Errors.dependencyLoop(chain = bindingLoop.toList()))
        }
    )
}