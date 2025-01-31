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

package com.yandex.yatagan.core.model.impl

import com.yandex.yatagan.base.BiObjectCache
import com.yandex.yatagan.base.ObjectCache
import com.yandex.yatagan.core.model.ClassBackedModel
import com.yandex.yatagan.core.model.ConditionModel
import com.yandex.yatagan.core.model.ConditionScope
import com.yandex.yatagan.core.model.ConditionalHoldingModel
import com.yandex.yatagan.lang.BuiltinAnnotation
import com.yandex.yatagan.lang.Field
import com.yandex.yatagan.lang.Member
import com.yandex.yatagan.lang.Method
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.TypeDeclaration
import com.yandex.yatagan.lang.isKotlinObject
import com.yandex.yatagan.validation.MayBeInvalid
import com.yandex.yatagan.validation.Validator
import com.yandex.yatagan.validation.format.ErrorMessage
import com.yandex.yatagan.validation.format.Negation
import com.yandex.yatagan.validation.format.Strings
import com.yandex.yatagan.validation.format.TextColor
import com.yandex.yatagan.validation.format.WarningMessage
import com.yandex.yatagan.validation.format.append
import com.yandex.yatagan.validation.format.appendRichString
import com.yandex.yatagan.validation.format.buildRichString
import com.yandex.yatagan.validation.format.modelRepresentation
import com.yandex.yatagan.validation.format.reportError
import com.yandex.yatagan.validation.format.reportWarning
import com.yandex.yatagan.validation.format.toString

internal class FeatureModelImpl private constructor(
    private val impl: TypeDeclaration,
) : ConditionalHoldingModel.FeatureModel {
    override val conditionScope: ConditionScope by lazy {
        ConditionScope(impl.getAnnotations(BuiltinAnnotation.ConditionFamily).map { conditionModel ->
            when (conditionModel) {
                is BuiltinAnnotation.ConditionFamily.Any ->
                    conditionModel.conditions.map { ConditionLiteralImpl(it) }.toSet()

                is BuiltinAnnotation.ConditionFamily.One ->
                    setOf(ConditionLiteralImpl(conditionModel))
            }
        }.toSet())
    }

    override fun validate(validator: Validator) {
        if (impl.getAnnotations(BuiltinAnnotation.ConditionFamily).none()) {
            // TODO: Forbid Never-scope/Always-scope.
            validator.reportError(Strings.Errors.noConditionsOnFeature())
        }
        for (literal in conditionScope.expression.asSequence().flatten().toSet()) {
            validator.child(literal)
        }
    }

    override fun toString(childContext: MayBeInvalid?) = modelRepresentation(
        modelClassName = "feature",
        representation = {
            append(impl)
            append(' ')
            when (conditionScope) {
                ConditionScope.Unscoped -> appendRichString {
                    color = TextColor.Red
                    append("<no-conditions-declared>")
                }
                ConditionScope.NeverScoped -> appendRichString {
                    color = TextColor.Red
                    append("<illegal-never>")
                }
                else -> append(conditionScope.toString(childContext = childContext))
            }
        },
    )

    override val type: Type
        get() = impl.asType()

    companion object Factory : ObjectCache<TypeDeclaration, FeatureModelImpl>() {
        operator fun invoke(impl: TypeDeclaration) = createCached(impl, ::FeatureModelImpl)
    }
}

private class ConditionLiteralImpl private constructor(
    override val negated: Boolean,
    private val payload: LiteralPayload,
) : ConditionModel {

    override fun not(): ConditionModel = Factory(
        negated = !negated,
        payload = payload,
    )

    override val path
        get() = payload.path

    override val root
        get() = NodeModelImpl(
            type = payload.type,
            qualifier = null,
        )

    override val requiresInstance: Boolean
        get() = payload.nonStatic

    override fun validate(validator: Validator) {
        validator.inline(payload)
    }

    override fun toString(childContext: MayBeInvalid?) = buildRichString {
        if (negated) append(Negation)
        append(payload)
    }

    companion object Factory : BiObjectCache<Boolean, LiteralPayload, ConditionLiteralImpl>() {
        operator fun invoke(model: BuiltinAnnotation.ConditionFamily.One): ConditionModel {
            val condition = model.condition
            return ConditionRegex.matchEntire(condition)?.let { matched ->
                val (negate, names) = matched.destructured
                this(
                    negated = negate.isNotEmpty(),
                    payload = LiteralPayloadImpl(
                        type = model.target,
                        pathSource = names,
                    ),
                )
            } ?: this(
                negated = false,
                payload = object : LiteralPayload {
                    override val type: Type
                        get() = model.target
                    override val path: List<Member> get() = emptyList()
                    override fun validate(validator: Validator) {
                        // Always invalid
                        validator.reportError(Strings.Errors.invalidCondition(expression = condition))
                    }
                    override val nonStatic: Boolean get() = false

                    override fun toString(childContext: MayBeInvalid?) = buildRichString {
                        color = TextColor.Red
                        append("<invalid-condition>")
                    }
                }
            )
        }

        private operator fun invoke(
            negated: Boolean,
            payload: LiteralPayload,
        ) = createCached(negated, payload) {
            ConditionLiteralImpl(negated, payload)
        }

        private val ConditionRegex = "^(!?)((?:[A-Za-z][A-Za-z0-9_]*\\.)*[A-Za-z][A-Za-z0-9_]*)\$".toRegex()
    }
}

private interface LiteralPayload : ClassBackedModel {
    val path: List<Member>
    val nonStatic: Boolean
}

private object MemberTypeVisitor : Member.Visitor<Type> {
    override fun visitOther(model: Member) = throw AssertionError()
    override fun visitMethod(model: Method) = model.returnType
    override fun visitField(model: Field) = model.type
}

private typealias ValidationReport = (Validator) -> Unit

private class LiteralPayloadImpl private constructor(
    override val type: Type,
    private val pathSource: String,
) : LiteralPayload {
    private var validationReport: ValidationReport? = null
    private var _nonStatic = false

    override val nonStatic: Boolean
        get() {
            path  // Ensure path is parsed
            return _nonStatic
        }

    override fun validate(validator: Validator) {
        path  // Ensure path is parsed
        validationReport?.invoke(validator)
    }

    override fun toString(childContext: MayBeInvalid?) = buildRichString {
        appendRichString {
            color = TextColor.BrightYellow
            append(type)
        }
        append(".$pathSource")
    }

    override val path: List<Member> by lazy {
        buildList {
            var currentType = type
            var finished = false

            var isFirst = true
            pathSource.split('.').forEach { name ->
                if (finished) {
                    validationReport = SimpleErrorReport(Strings.Errors.invalidConditionNoBoolean())
                    return@forEach
                }

                val currentDeclaration = currentType.declaration
                if (!currentDeclaration.isEffectivelyPublic) {
                    validationReport = SimpleErrorReport(
                        Strings.Errors.invalidAccessForConditionClass(`class` = currentDeclaration))
                    return@buildList
                }

                val member = findAccessor(currentDeclaration, name)
                if (member == null) {
                    validationReport = SimpleErrorReport(
                        Strings.Errors.invalidConditionMissingMember(name = name, type = currentType))
                    return@buildList
                }
                if (isFirst) {
                    if (!member.isStatic) {
                        if (currentType.declaration.isKotlinObject) {
                            // Issue warning
                            validationReport = SimpleWarningReport(Strings.Warnings.nonStaticConditionOnKotlinObject())
                        }
                        _nonStatic = true
                    }
                    isFirst = false
                }
                if (!member.isEffectivelyPublic) {
                    validationReport = SimpleErrorReport(
                        Strings.Errors.invalidAccessForConditionMember(member = member))
                    return@buildList
                }
                add(member)

                val type = member.accept(MemberTypeVisitor)
                if (type.asBoxed().declaration.qualifiedName == "java.lang.Boolean") {
                    finished = true
                } else {
                    currentType = type
                }
            }
            if (!finished) {
                validationReport = CompositeErrorReport(
                    base = validationReport,
                    added = SimpleErrorReport(Strings.Errors.invalidConditionNoBoolean()),
                )
            }
        }
    }

    companion object Factory : BiObjectCache<Type, String, LiteralPayload>() {
        operator fun invoke(type: Type, pathSource: String): LiteralPayload {
            return createCached(type, pathSource) {
                LiteralPayloadImpl(type, pathSource)
            }
        }

        private fun findAccessor(type: TypeDeclaration, name: String): Member? {
            val field = type.fields.find { it.name == name }
            if (field != null) {
                return field
            }

            val method = type.methods.find { method ->
                method.name == name
            }
            if (method != null) {
                return method
            }
            return null
        }

        class SimpleErrorReport(val error: ErrorMessage): ValidationReport {
            override fun invoke(validator: Validator) = validator.reportError(error)
        }

        class SimpleWarningReport(val warning: WarningMessage): ValidationReport {
            override fun invoke(validator: Validator) = validator.reportWarning(warning)
        }

        class CompositeErrorReport(
            val base: ValidationReport?,
            val added: ValidationReport,
        ) : ValidationReport {
            override fun invoke(validator: Validator) {
                base?.invoke(validator)
                added.invoke(validator)
            }
        }
    }
}