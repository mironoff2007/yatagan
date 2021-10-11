package com.yandex.dagger3.compiler

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.yandex.dagger3.core.ComponentModel
import com.yandex.dagger3.core.EntryPointModel
import com.yandex.dagger3.core.NameModel
import com.yandex.dagger3.core.NodeDependency
import dagger.Component

data class KspComponentModel(
    val componentDeclaration: KSClassDeclaration,
) : ComponentModel {
    override val name: NameModel = NameModel(componentDeclaration)

    private val impl = requireNotNull(componentDeclaration.getAnnotation<Component>()) {
        "declaration $componentDeclaration can't be represented by ComponentModel"
    }

    @Suppress("UNCHECKED_CAST")
    override val modules: Set<KspModuleModel> by lazy {
        val list = impl["modules"] as? List<KSType> ?: return@lazy emptySet()
        list.mapTo(hashSetOf(), ::KspModuleModel)
    }

    @Suppress("UNCHECKED_CAST")
    override val dependencies: Set<KspComponentModel> by lazy {
        val list = impl["dependencies"] as? List<KSType> ?: return@lazy emptySet()
        list.mapTo(hashSetOf()) { KspComponentModel(it.declaration as KSClassDeclaration) }
    }

    override val entryPoints: Set<EntryPointModel> by lazy {
        buildSet {
            for (function in componentDeclaration.getAllFunctions().filter { it.isAbstract }) {
                this += EntryPointModel(
                    dep = NodeDependency.resolveFromType(
                        type = function.returnType?.resolve() ?: continue,
                        forQualifier = function,
                    ),
                    getter = FunctionNameModel(componentDeclaration, function),
                )
            }
            for (prop in componentDeclaration.getAllProperties().filter { it.isAbstract() && !it.isMutable }) {
                this += EntryPointModel(
                    dep = NodeDependency.resolveFromType(type = prop.type.resolve(), forQualifier = prop),
                    getter = PropertyNameModel(componentDeclaration, prop),
                )
            }
        }
    }
}