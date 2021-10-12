package com.yandex.dagger3.generator

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.yandex.dagger3.core.CallableNameModel
import com.yandex.dagger3.core.ConstructorNameModel
import com.yandex.dagger3.core.FunctionNameModel
import com.yandex.dagger3.core.MemberCallableNameModel
import com.yandex.dagger3.core.NameModel
import com.yandex.dagger3.core.NodeDependency
import com.yandex.dagger3.core.NodeModel
import com.yandex.dagger3.core.PropertyNameModel
import com.yandex.dagger3.generator.poetry.ExpressionBuilder
import com.yandex.dagger3.generator.poetry.Names


internal inline fun NameModel.asClassName(
    transformName: (String) -> String,
): ClassName {
    require(typeArguments.isEmpty())
    return ClassName.get(packageName, transformName(simpleName))
}

internal fun NameModel.asTypeName(): TypeName {
    val className = ClassName.get(packageName, simpleName)
    return if (typeArguments.isNotEmpty()) {
        ParameterizedTypeName.get(className, *typeArguments.map(NameModel::asTypeName).toTypedArray())
    } else className
}

internal fun NodeDependency.asTypeName(): TypeName {
    val typeName = node.name.asTypeName()
    return when (kind) {
        NodeDependency.Kind.Normal -> typeName
        NodeDependency.Kind.Lazy -> ParameterizedTypeName.get(Names.Lazy, typeName)
        NodeDependency.Kind.Provider -> ParameterizedTypeName.get(Names.Provider, typeName)
    }
}

internal fun NodeModel.asIdentifier() = name.qualifiedName.replace('.', '_') + (qualifier ?: "")

internal fun MemberCallableNameModel.functionName() = when (this) {
    is FunctionNameModel -> function
    is PropertyNameModel -> "get${property.capitalize()}"
}

internal fun ExpressionBuilder.call(name: CallableNameModel, arguments: Sequence<Any>) {
    when (name) {
        is ConstructorNameModel -> +"new %T(".formatCode(name.type.asTypeName())
        is MemberCallableNameModel -> +"%T.%N(".formatCode(
            name.ownerName.asTypeName(),
            name.functionName(),
        )
    }
    join(arguments.asSequence()) { arg ->
        +"%L".formatCode(arg)
    }
    +")"
}