package com.yandex.daggerlite.lang.rt

import com.yandex.daggerlite.base.ObjectCache
import java.lang.reflect.Executable
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import kotlin.LazyThreadSafetyMode.NONE

private typealias ArraysReflectionUtils = java.lang.reflect.Array

internal fun Type.equivalence() = TypeEquivalenceWrapper(this)

internal val Member.isStatic get() = Modifier.isStatic(modifiers)

internal val Member.isAbstract get() = Modifier.isAbstract(modifiers)

internal val Member.isPrivate get() = Modifier.isPrivate(modifiers)

internal val Class<*>.isPrivate get() = Modifier.isPrivate(modifiers)

internal fun Type.asClass(): Class<*> = tryAsClass() ?: throw AssertionError("Not reached: not a class")

internal fun Type.tryAsClass(): Class<*>? = when (this) {
    is Class<*> -> this
    is ParameterizedType -> rawType.asClass()
    else -> null
}

internal fun Type.formatString(): String = when (this) {
    is Class<*> -> canonicalName ?: "<unnamed>"
    is ParameterizedType -> "${rawType.formatString()}<${actualTypeArguments.joinToString { it.formatString() }}>"
    is WildcardType -> when {
        lowerBounds.isNotEmpty() -> "? super ${lowerBounds.first().formatString()}"
        upperBounds.isNotEmpty() -> "? extends ${upperBounds.first().formatString()}"
        else -> "?"
    }
    is GenericArrayType -> "${genericComponentType.formatString()}[]"
    else -> toString()
}

internal fun Type.superTypesSequence(): Sequence<Type> = sequence {
    val queue = ArrayDeque<Type>()
    queue += this@superTypesSequence
    while(queue.isNotEmpty()) {
        val type = queue.removeFirst()
        yield(type)
        when(type) {
            is ParameterizedType -> {
                val resolvedTypeParameters = lazy(NONE, type::resolveTypeParameters)
                val clazz = type.rawType.asClass()
                clazz.genericSuperclass?.let { superClass ->
                    queue += superClass.resolveTypeVar(resolvedTypeParameters::value)
                }
                for (genericInterface in clazz.genericInterfaces) {
                    queue += genericInterface.resolveTypeVar(resolvedTypeParameters::value)
                }
            }
            is Class<*> -> {
                type.genericSuperclass?.let(queue::add)
                queue.addAll(type.genericInterfaces)
            }
            else -> { /* Do nothing */}
        }
    }
}

internal fun Type.resolve(
    member: Member,
    asMemberOf: Type,
): Type {
    val declaringClass = member.declaringClass
    return when (val refinedAsMemberOf = asMemberOf.superTypesSequence().find { it.asClass() == declaringClass }) {
        is ParameterizedType -> {
            val resolvedTypeParameters = lazy(NONE, refinedAsMemberOf::resolveTypeParameters)
            resolveTypeVar(withInfo = resolvedTypeParameters::value)
        }
        else -> this
    }
}

internal fun Executable.resolveParameters(
    asMemberOf: Type,
): List<RtParameterImpl> = when (asMemberOf) {
    is ParameterizedType -> {
        // Some type variables may be matched
        val resolvedTypeParameters = lazy(NONE, asMemberOf::resolveTypeParameters)
        parameters.map {
            RtParameterImpl(
                impl = it,
                refinedType = it.parameterizedType.resolveTypeVar(withInfo = resolvedTypeParameters::value)
            )
        }
    }
    else -> parameters.map { RtParameterImpl(impl = it) }
}

private fun Type.resolveTypeVar(withInfo: () -> Map<TypeVariable<*>, Type>): Type = when (this) {
    is TypeVariable<*> -> withInfo()[this] ?: this
    is ParameterizedType -> replaceTypeArguments { argument ->
        argument.resolveTypeVar(withInfo)
    }
    is WildcardType -> replaceBounds { bound ->
        bound.resolveTypeVar(withInfo)
    }
    is GenericArrayType -> replaceComponentType(
        componentType = genericComponentType.resolveTypeVar(withInfo)
    )
    else -> this
}

private fun ParameterizedType.resolveTypeParameters(): Map<TypeVariable<*>, Type> = buildMap(actualTypeArguments.size) {
    val typeParams = rawType.asClass().typeParameters
    val typeArgs = actualTypeArguments
    for (i in typeParams.indices) {
        put(typeParams[i], typeArgs[i])
    }
}

private fun GenericArrayType.replaceComponentType(
    componentType: Type,
): Type = when(componentType) {
    genericComponentType -> this
    is Class<*> -> ArraysReflectionUtils.newInstance(componentType, 0).javaClass
    else -> @Suppress("ObjectLiteralToLambda") object : GenericArrayType {
        override fun getGenericComponentType(): Type = componentType
    }
}

private fun ParameterizedType.replaceTypeArguments(
    actualTypeArguments: Array<Type>,
) = object : ParameterizedType by this {
    override fun getActualTypeArguments() = actualTypeArguments
}

private fun WildcardType.replaceBounds(
    lowerBounds: Array<Type>,
    upperBounds: Array<Type>,
) = object : WildcardType by this {
    override fun getLowerBounds(): Array<Type> = lowerBounds
    override fun getUpperBounds(): Array<Type> = upperBounds
}

private inline fun ParameterizedType.replaceTypeArguments(transform: (Type) -> Type): ParameterizedType {
    var changed = false
    val new = Array(actualTypeArguments.size) { i ->
        val old = actualTypeArguments[i]
        val new = transform(old)
        changed = changed || old !== new
        new
    }
    return if (changed) replaceTypeArguments(new) else this
}

private inline fun WildcardType.replaceBounds(transform: (Type) -> Type): WildcardType {
    var changed = false
    val lowerBounds = lowerBounds
    val upperBounds = upperBounds
    val newLowerBounds = Array(lowerBounds.size) { i ->
        val old = lowerBounds[i]
        val new = transform(old)
        changed = changed || old !== new
        new
    }
    val newUpperBounds = Array(upperBounds.size) { i ->
        val old = upperBounds[i]
        val new = transform(old)
        changed = changed || old !== new
        new
    }
    return if (changed) replaceBounds(
        lowerBounds = newLowerBounds,
        upperBounds = newUpperBounds,
    ) else this
}

internal fun Class<*>.boxed(): Type {
    @Suppress("RemoveRedundantQualifierName")
    return if (!this.isPrimitive) this else when (this) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> throw AssertionError("Not reached")
    }
}

internal fun Class<*>.getMethodsOverrideAware(): List<Method> = buildList {
    val overrideControl = hashSetOf<MethodSignatureEquivalenceWrapper>()
    fun handleClass(clazz: Class<*>) {
        for (declaredMethod in clazz.declaredMethods) {
            if (declaredMethod.isPrivate || declaredMethod.isSynthetic) {
                // Skip private/synthetic methods
                continue
            }
            if (declaredMethod.isStatic || overrideControl.add(declaredMethod.signatureEquivalence())) {
                add(declaredMethod)
            }
        }
        clazz.superclass?.let(::handleClass)
        clazz.interfaces.forEach(::handleClass)
    }
    handleClass(this@getMethodsOverrideAware)
    sortWith(ExecutableSignatureComparator)
}

private fun arrayHashCode(types: Array<Type>): Int = types.fold(1) { hash, type -> 31 * hash + hashCode(type) }

private fun hashCode(type: Type): Int = when (type) {
    is ParameterizedType -> 31 * type.rawType.hashCode() + arrayHashCode(type.actualTypeArguments)
    is WildcardType -> 31 * arrayHashCode(type.lowerBounds) + arrayHashCode(type.upperBounds)
    is GenericArrayType -> hashCode(type.genericComponentType)
    else -> type.hashCode()
}

private fun arrayEquals(one: Array<Type>, other: Array<Type>): Boolean {
    if (one.size != other.size) return false
    for (i in one.indices) {
        if (!equals(one[i], other[i]))
            return false
    }
    return true
}

private fun equals(one: Type, other: Type): Boolean = with(one) {
    when (this) {
        is ParameterizedType -> {
            // Take synthetic parameterized types into account.
            other is ParameterizedType &&
                    rawType == other.rawType && arrayEquals(actualTypeArguments, other.actualTypeArguments)
        }
        is WildcardType -> {
            // Take synthetic wildcard types into account
            other is WildcardType && arrayEquals(upperBounds, other.upperBounds) &&
                    arrayEquals(lowerBounds, other.lowerBounds)
        }
        is GenericArrayType -> {
            other is GenericArrayType && equals(genericComponentType, other.genericComponentType)
        }
        other -> true
        else -> false
    }
}

/**
 * This is required as [Type.equals] works only with its internal implementations on some platforms.
 */
internal class TypeEquivalenceWrapper private constructor(private val type: Type) {
    override fun hashCode(): Int = hashCode(type)
    override fun equals(other: Any?): Boolean {
        if (other !is TypeEquivalenceWrapper) return false
        return equals(type, other.type)
    }

    companion object Cache : ObjectCache<Type, TypeEquivalenceWrapper>() {
        operator fun invoke(type: Type) = createCached(type, ::TypeEquivalenceWrapper)
    }
}
