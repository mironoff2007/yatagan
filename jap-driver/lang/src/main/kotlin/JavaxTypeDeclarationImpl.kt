package com.yandex.daggerlite.jap.lang

import com.google.auto.common.MoreTypes
import com.google.common.base.Equivalence
import com.yandex.daggerlite.base.ObjectCache
import com.yandex.daggerlite.base.memoize
import com.yandex.daggerlite.core.lang.ConstructorLangModel
import com.yandex.daggerlite.core.lang.FieldLangModel
import com.yandex.daggerlite.core.lang.FunctionLangModel
import com.yandex.daggerlite.core.lang.KotlinObjectKind
import com.yandex.daggerlite.core.lang.ParameterLangModel
import com.yandex.daggerlite.core.lang.TypeDeclarationLangModel
import com.yandex.daggerlite.core.lang.TypeLangModel
import com.yandex.daggerlite.generator.lang.CtTypeDeclarationLangModel
import kotlinx.metadata.KmClass
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.type.DeclaredType
import kotlin.LazyThreadSafetyMode.NONE

internal class JavaxTypeDeclarationImpl private constructor(
    val type: DeclaredType,
) : JavaxAnnotatedLangModel by JavaxAnnotatedImpl(type.asTypeElement()), CtTypeDeclarationLangModel() {
    private val impl = type.asTypeElement()

    private val kotlinClass: KmClass? by lazy(NONE) {
        impl.obtainKotlinClassIfApplicable()
    }

    override val isAbstract: Boolean
        get() = impl.isAbstract

    override val kotlinObjectKind: KotlinObjectKind?
        get() = kotlinClass?.let {
            when {
                it.isCompanionObject -> KotlinObjectKind.Companion
                it.isObject -> KotlinObjectKind.Object
                else -> null
            }
        }

    override val qualifiedName: String
        get() = impl.qualifiedName.toString()

    override val implementedInterfaces: Sequence<TypeLangModel> = impl.allImplementedInterfaces()
        .map { JavaxTypeImpl(it) }
        .memoize()

    override val constructors: Sequence<ConstructorLangModel> = impl.enclosedElements
        .asSequence()
        .filter { it.kind == ElementKind.CONSTRUCTOR }
        .map {
            ConstructorImpl(impl = it.asExecutableElement())
        }.memoize()

    override val allPublicFunctions: Sequence<FunctionLangModel> = sequence {
        val owner = this@JavaxTypeDeclarationImpl
        yieldAll(impl.allMethods(Utils.types, Utils.elements).map {
            JavaxFunctionImpl(
                owner = owner,
                impl = it,
            )
        })
        kotlinClass?.companionObject?.let { companionName: String ->
            val companionClass = checkNotNull(impl.enclosedElements.find {
                it.kind == ElementKind.CLASS && it.simpleName.contentEquals(companionName)
            }) { "Inconsistent metadata interpreting: No companion $companionName detected in $impl" }
            val companionType = JavaxTypeDeclarationImpl(companionClass.asType().asDeclaredType())
            companionClass.asTypeElement().allMethods(Utils.types, Utils.elements)
                .filter {
                    // Such methods already have a truly static counterpart so skip them.
                    !it.isAnnotatedWith<JvmStatic>()
                }.forEach {
                    yield(JavaxFunctionImpl(
                        owner = companionType,
                        impl = it,
                    ))
                }
        }
    }.memoize()

    override val allPublicFields: Sequence<FieldLangModel> = impl.enclosedElements.asSequence()
        .filter { it.kind == ElementKind.FIELD && it.isPublic }
        .map { JavaxFieldImpl(owner = this, impl = it.asVariableElement()) }
        .memoize()

    override val nestedInterfaces: Sequence<TypeDeclarationLangModel> = impl.enclosedElements
        .asSequence()
        .filter { it.kind == ElementKind.INTERFACE }
        .map { Factory(it.asType().asDeclaredType()) }
        .memoize()

    override fun asType(): TypeLangModel {
        return JavaxTypeImpl(type)
    }

    companion object Factory : ObjectCache<Equivalence.Wrapper<DeclaredType>, JavaxTypeDeclarationImpl>() {
        operator fun invoke(
            impl: DeclaredType,
        ): JavaxTypeDeclarationImpl {
            return createCached(MoreTypes.equivalence().wrap(impl)) {
                JavaxTypeDeclarationImpl(type = impl)
            }
        }
    }

    private inner class ConstructorImpl(
        impl: ExecutableElement,
    ) : ConstructorLangModel, JavaxAnnotatedImpl<ExecutableElement>(impl) {
        override val constructee: TypeDeclarationLangModel get() = this@JavaxTypeDeclarationImpl
        override val parameters: Sequence<ParameterLangModel> = parametersSequenceFor(impl, type)
    }
}