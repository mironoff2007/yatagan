package com.yandex.daggerlite.graph.impl

import com.yandex.daggerlite.core.BindsBindingModel
import com.yandex.daggerlite.core.ComponentDependencyInput
import com.yandex.daggerlite.core.ComponentFactoryModel
import com.yandex.daggerlite.core.ComponentModel
import com.yandex.daggerlite.core.DependencyKind
import com.yandex.daggerlite.core.InjectConstructorBindingModel
import com.yandex.daggerlite.core.InstanceInput
import com.yandex.daggerlite.core.ModuleHostedBindingModel
import com.yandex.daggerlite.core.ModuleModel
import com.yandex.daggerlite.core.NodeDependency
import com.yandex.daggerlite.core.NodeModel
import com.yandex.daggerlite.core.ProvidesBindingModel
import com.yandex.daggerlite.core.lang.AnnotationLangModel
import com.yandex.daggerlite.graph.AliasBinding
import com.yandex.daggerlite.graph.AlternativesBinding
import com.yandex.daggerlite.graph.BindingGraph
import com.yandex.daggerlite.graph.ComponentDependencyBinding
import com.yandex.daggerlite.graph.ComponentDependencyEntryPointBinding
import com.yandex.daggerlite.graph.ComponentInstanceBinding
import com.yandex.daggerlite.graph.ConditionScope
import com.yandex.daggerlite.graph.EmptyBinding
import com.yandex.daggerlite.graph.InstanceBinding
import com.yandex.daggerlite.graph.MultiBinding
import com.yandex.daggerlite.graph.ProvisionBinding
import com.yandex.daggerlite.graph.SubComponentFactoryBinding
import kotlin.LazyThreadSafetyMode.NONE

internal abstract class ModuleHostedBindingBase {
    protected abstract val impl: ModuleHostedBindingModel

    val originModule get() = impl.originModule

    val target: NodeModel by lazy(NONE) {
        with(impl) {
            if (isMultibinding)
                MultiBindingContributionNode(target)
            else target
        }
    }

    private class MultiBindingContributionNode(
        private val node: NodeModel,
    ) : NodeModel by node {
        override val implicitBinding: Nothing? get() = null
        override val bootstrapInterfaces: Collection<Nothing> get() = emptyList()
        override fun toString() = "$node [multi-binding contributor]"
    }
}

internal class ProvisionBindingImpl(
    override val impl: ProvidesBindingModel,
    override val owner: BindingGraph,
    override val conditionScope: ConditionScope,
) : ProvisionBinding, ModuleHostedBindingBase() {

    override val scope get() = impl.scope
    override val provision get() = impl.provision
    override val inputs get() = impl.inputs
    override val requiresModuleInstance get() = impl.requiresModuleInstance

    override fun dependencies(): Collection<NodeDependency> = inputs.toList()
    override fun toString() = "@Provides ${inputs.toList()} -> $target"
}

internal class InjectConstructorProvisionBindingImpl(
    private val impl: InjectConstructorBindingModel,
    override val owner: BindingGraph,
    override val conditionScope: ConditionScope,
) : ProvisionBinding {
    override val target get() = impl.target
    override val originModule: Nothing? get() = null
    override val scope: AnnotationLangModel? get() = impl.scope
    override val provision get() = impl.constructor
    override val inputs: Sequence<NodeDependency> get() = impl.inputs
    override val requiresModuleInstance: Boolean = false

    override fun dependencies(): Collection<NodeDependency> {
        return impl.inputs.toList()
    }
}

internal class AliasBindingImpl(
    override val impl: BindsBindingModel,
    override val owner: BindingGraph,
) : AliasBinding, ModuleHostedBindingBase() {
    init {
        require(impl.scope == null)
        require(impl.sources.count() == 1)
    }

    override val source get() = impl.sources.single()

    override fun equals(other: Any?): Boolean {
        return this === other || (other is AliasBindingImpl &&
                source == other.source && target == other.target)
    }

    override fun hashCode(): Int {
        var result = target.hashCode()
        result = 31 * result + source.hashCode()
        return result
    }

    override fun toString() = "@Binds (alias) $source -> $target"
}

internal class AlternativesBindingImpl(
    override val impl: BindsBindingModel,
    override val owner: BindingGraph,
) : AlternativesBinding, ModuleHostedBindingBase() {
    override val scope get() = impl.scope
    override val alternatives get() = impl.sources

    override val conditionScope: ConditionScope by lazy(NONE) {
        alternatives.fold(ConditionScope.NeverScoped) { acc, alternative ->
            val binding = owner.resolveBinding(alternative)
            acc or binding.conditionScope
        }
    }

    override fun dependencies() = alternatives.map(::NodeDependency).toList()

    override fun toString() = "@Binds (alternatives) [first present of $alternatives] -> $target"
}

internal class ComponentDependencyEntryPointBindingImpl(
    override val owner: BindingGraph,
    override val input: ComponentDependencyInput,
    private val entryPoint: ComponentModel.EntryPoint,
) : ComponentDependencyEntryPointBinding {
    init {
        require(entryPoint.dependency.kind == DependencyKind.Direct) {
            "Only direct entry points constitute a binding that can be used in dependency components"
        }
    }

    override val originModule: Nothing? get() = null
    override val scope: Nothing? get() = null
    override val conditionScope get() = ConditionScope.Unscoped
    override val target get() = entryPoint.dependency.node
    override val getter get() = entryPoint.getter
    override fun dependencies() = listOf(NodeDependency(input.component.asNode()))

    override fun toString() = "$entryPoint from ${input.component} (intrinsic)"
}

internal class ComponentInstanceBindingImpl(
    graph: BindingGraph,
) : ComponentInstanceBinding {
    override val owner: BindingGraph = graph
    override val target get() = owner.model.asNode()
    override val scope: Nothing? get() = null
    override val conditionScope get() = ConditionScope.Unscoped
    override fun dependencies(): List<Nothing> = emptyList()
    override val originModule: Nothing? get() = null

    override fun toString() = "Component instance $target (intrinsic)"
}

internal class SubComponentFactoryBindingImpl(
    override val owner: BindingGraph,
    override val conditionScope: ConditionScope,
    private val factory: ComponentFactoryModel,
) : SubComponentFactoryBinding {
    override val target: NodeModel
        get() = factory.asNode()

    override val targetGraph: BindingGraph by lazy(NONE) {
        val targetComponent = factory.createdComponent
        checkNotNull(owner.children.find { it.model == targetComponent }) {
            "$this: Can't find child component $targetComponent among $owner's children."
        }
    }

    override val scope: Nothing? get() = null

    override val originModule: Nothing? get() = null
    override fun dependencies() = targetGraph.usedParents.map { NodeDependency(it.model.asNode()) }
    override fun toString() = "Subcomponent factory $factory (intrinsic)"
}

internal class BootstrapListBindingImpl(
    override val owner: BindingGraph,
    override val target: NodeModel,
    private val inputs: Collection<NodeModel>,
) : MultiBinding {
    override val scope: Nothing? get() = null
    override val conditionScope get() = ConditionScope.Unscoped
    override fun dependencies() = inputs.map(::NodeDependency)
    override val contributions: Collection<NodeModel> by lazy(NONE) {
        topologicalSort(nodes = inputs, inside = owner)
    }
    override val originModule: Nothing? get() = null

    override fun toString() = "Bootstrap $target of ${inputs.take(3)}${if (inputs.size > 3) "..." else ""} (intrinsic)"
}

internal class MultiBindingImpl(
    override val owner: BindingGraph,
    override val target: NodeModel,
    override val contributions: Set<NodeModel>,
) : MultiBinding {
    override val scope: Nothing? get() = null
    override val conditionScope get() = ConditionScope.Unscoped
    override fun dependencies() = contributions.map(::NodeDependency)
    override val originModule: Nothing? get() = null

    override fun toString() =
        "MultiBinding $target of ${contributions.take(3)}${if (contributions.size > 3) "..." else ""} (intrinsic)"
}

internal class ModuleHostedEmptyBindingImpl(
    override val impl: ModuleHostedBindingModel,
    override val owner: BindingGraph,
) : EmptyBinding, ModuleHostedBindingBase() {
    override val conditionScope get() = ConditionScope.NeverScoped
    override val scope: Nothing? get() = null
    override fun dependencies(): List<Nothing> = emptyList()
    override fun toString() = "Absent $target in $impl"
}

internal class ImplicitEmptyBindingImpl(
    override val owner: BindingGraph,
    override val target: NodeModel,
    override val originModule: ModuleModel? = null,
) : EmptyBinding {
    override val scope: Nothing? get() = null
    override val conditionScope get() = ConditionScope.NeverScoped
    override fun dependencies(): List<Nothing> = emptyList()
    override fun toString() = "Absent $target (intrinsic)"
}

internal class ComponentDependencyBindingImpl(
    override val input: ComponentDependencyInput,
    override val owner: BindingGraph,
) : ComponentDependencyBinding {
    override val target get() = input.component.asNode()
    override val conditionScope get() = ConditionScope.Unscoped
    override val scope: Nothing? get() = null
    override fun dependencies(): List<Nothing> = emptyList()
    override val originModule: Nothing? get() = null
}

internal class InstanceBindingImpl(
    override val input: InstanceInput,
    override val owner: BindingGraph,
) : InstanceBinding {
    override val conditionScope get() = ConditionScope.Unscoped
    override val scope: Nothing? get() = null
    override val target get() = input.node
    override fun dependencies(): List<Nothing> = emptyList()
    override val originModule: Nothing? get() = null
}