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

package com.yandex.yatagan.codegen.impl

import com.squareup.javapoet.CodeBlock
import com.yandex.yatagan.codegen.poetry.ExpressionBuilder
import com.yandex.yatagan.codegen.poetry.buildExpression
import com.yandex.yatagan.core.graph.BindingGraph
import com.yandex.yatagan.core.graph.bindings.AlternativesBinding
import com.yandex.yatagan.core.graph.bindings.AssistedInjectFactoryBinding
import com.yandex.yatagan.core.graph.bindings.Binding
import com.yandex.yatagan.core.graph.bindings.ComponentDependencyBinding
import com.yandex.yatagan.core.graph.bindings.ComponentDependencyEntryPointBinding
import com.yandex.yatagan.core.graph.bindings.ComponentInstanceBinding
import com.yandex.yatagan.core.graph.bindings.EmptyBinding
import com.yandex.yatagan.core.graph.bindings.InstanceBinding
import com.yandex.yatagan.core.graph.bindings.MapBinding
import com.yandex.yatagan.core.graph.bindings.MultiBinding
import com.yandex.yatagan.core.graph.bindings.ProvisionBinding
import com.yandex.yatagan.core.graph.bindings.SubComponentBinding
import com.yandex.yatagan.core.model.NodeModel
import com.yandex.yatagan.core.model.component1
import com.yandex.yatagan.core.model.component2
import com.yandex.yatagan.core.model.isAlways
import com.yandex.yatagan.core.model.isNever
import com.yandex.yatagan.lang.Callable
import com.yandex.yatagan.lang.Constructor
import com.yandex.yatagan.lang.Method
import com.yandex.yatagan.lang.TypeDeclarationKind

private class CreationGeneratorVisitor(
    private val builder: ExpressionBuilder,
    private val inside: BindingGraph,
    private val isInsideInnerClass: Boolean,
) : Binding.Visitor<Unit> {
    override fun visitOther(binding: Binding) = throw AssertionError()

    override fun visitProvision(binding: ProvisionBinding) {
        with(builder) {
            val instance = if (binding.requiresModuleInstance) {
                "%L.%N".formatCode(
                    componentForBinding(binding),
                    binding.owner[GeneratorComponent].componentFactoryGenerator.fieldNameFor(binding.originModule!!),
                )
            } else null
            binding.provision.accept(object : Callable.Visitor<Unit> {
                fun genArgs() {
                    join(seq = binding.inputs.asIterable()) { (node, kind) ->
                        inside.resolveBinding(node).generateAccess(
                            builder = this,
                            inside = inside,
                            kind = kind,
                            isInsideInnerClass = isInsideInnerClass,
                        )
                    }
                }

                override fun visitMethod(method: Method) {
                    val enableNullChecks = inside[GeneratorComponent].options.enableProvisionNullChecks
                    if (enableNullChecks) {
                        +"%T.checkProvisionNotNull(".formatCode(Names.Checks)
                    }
                    if (instance != null) {
                        +"%L.%N(".formatCode(instance, method.name)
                    } else {
                        val ownerObject = when (method.owner.kind) {
                            TypeDeclarationKind.KotlinObject -> ".INSTANCE"
                            else -> ""
                        }
                        +"%T%L.%L(".formatCode(method.ownerName.asTypeName(), ownerObject, method.name)
                    }
                    genArgs()
                    +")"
                    if (enableNullChecks) {
                        +")"
                    }
                }

                override fun visitConstructor(constructor: Constructor) {
                    +"new %T(".formatCode(constructor.constructee.asType().typeName().asRawType())
                    genArgs()
                    +")"
                }

                override fun visitOther(callable: Callable) = throw AssertionError()
            })
        }
    }

    override fun visitAssistedInjectFactory(binding: AssistedInjectFactoryBinding) {
        binding.owner[GeneratorComponent].assistedInjectFactoryGenerator.generateCreation(
            builder = builder,
            binding = binding,
            inside = inside,
            isInsideInnerClass = isInsideInnerClass,
        )
    }

    override fun visitInstance(binding: InstanceBinding) {
        with(builder) {
            +"%L.%N".formatCode(
                componentForBinding(binding),
                binding.owner[GeneratorComponent].componentFactoryGenerator.fieldNameFor(binding.target),
            )
        }
    }

    override fun visitAlternatives(binding: AlternativesBinding) {
        with(builder) {
            var exhaustive = false
            for (alternative: NodeModel in binding.alternatives) {
                val altBinding = inside.resolveBinding(alternative)
                if (!altBinding.conditionScope.isAlways) {
                    if (altBinding.conditionScope.isNever) {
                        // Never scoped is, by definition, unreached, so just skip it.
                        continue
                    }
                    val expression = buildExpression {
                        val gen = inside[GeneratorComponent].conditionGenerator
                        gen.expression(
                            builder = this,
                            conditionScope = altBinding.conditionScope,
                            inside = inside,
                            isInsideInnerClass = isInsideInnerClass,
                        )
                    }
                    +"%L ? ".formatCode(expression)
                    altBinding.generateAccess(
                        builder = builder,
                        inside = inside,
                        isInsideInnerClass = isInsideInnerClass,
                    )
                    +" : "
                } else {
                    altBinding.generateAccess(
                        builder = builder,
                        inside = inside,
                        isInsideInnerClass = isInsideInnerClass,
                    )
                    exhaustive = true
                    break  // no further generation, the rest are (if any) unreachable.
                }
            }
            if (!exhaustive) {
                +"null /*empty*/"
            }
        }
    }

    override fun visitSubComponent(binding: SubComponentBinding) {
        with(builder) {
            +"new %T(".formatCode(binding.targetGraph[GeneratorComponent].componentFactoryGenerator.implName)
            join(binding.targetGraph.usedParents) { parentGraph ->
                +buildExpression {
                    +"%L".formatCode(componentInstance(
                        inside = inside,
                        graph = parentGraph,
                        isInsideInnerClass = isInsideInnerClass,
                    ))
                }
            }
            +")"
        }
    }

    override fun visitComponentDependency(binding: ComponentDependencyBinding) {
        with(builder) {
            +"%L.%N".formatCode(
                componentForBinding(binding),
                binding.owner[GeneratorComponent].componentFactoryGenerator.fieldNameFor(binding.dependency),
            )
        }
    }

    override fun visitComponentInstance(binding: ComponentInstanceBinding) {
        with(builder) {
            +componentForBinding(binding)
        }
    }

    override fun visitComponentDependencyEntryPoint(binding: ComponentDependencyEntryPointBinding) {
        with(builder) {
            +"%L.%N.%N()".formatCode(
                componentForBinding(binding),
                binding.owner[GeneratorComponent].componentFactoryGenerator.fieldNameFor(binding.dependency),
                binding.getter.name,
            )
        }
    }

    override fun visitMulti(binding: MultiBinding) {
        binding.owner[GeneratorComponent].collectionBindingGenerator.generateCreation(
            builder = builder,
            binding = binding,
            inside = inside,
            isInsideInnerClass = isInsideInnerClass,
        )
    }

    override fun visitMap(binding: MapBinding) {
        binding.owner[GeneratorComponent].mapBindingGenerator.generateCreation(
            builder = builder,
            binding = binding,
            inside = inside,
            isInsideInnerClass = isInsideInnerClass,
        )
    }

    override fun visitEmpty(binding: EmptyBinding) {
        throw AssertionError("Not reached: unreported empty/missing binding: `$binding`")
    }

    private fun componentForBinding(binding: Binding): CodeBlock {
        return componentForBinding(
            inside = inside,
            binding = binding,
            isInsideInnerClass = isInsideInnerClass,
        )
    }
}

internal fun Binding.generateCreation(
    builder: ExpressionBuilder,
    inside: BindingGraph,
    isInsideInnerClass: Boolean,
) {
    accept(CreationGeneratorVisitor(
        builder = builder,
        inside = inside,
        isInsideInnerClass = isInsideInnerClass,
    ))
}