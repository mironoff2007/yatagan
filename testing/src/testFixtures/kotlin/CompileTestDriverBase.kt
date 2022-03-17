package com.yandex.daggerlite.testing

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.yandex.daggerlite.base.memoize
import com.yandex.daggerlite.generated.CompiledApiClasspath
import com.yandex.daggerlite.generated.DynamicApiClasspath
import com.yandex.daggerlite.process.LoggerDecorator
import java.io.File
import java.net.URLClassLoader
import kotlin.test.assertContains
import kotlin.test.assertEquals

abstract class CompileTestDriverBase protected constructor(
    private val apiType: ApiType = ApiType.Compiled,
) : CompileTestDriver {
    private val sourceSet = SourceSetImpl()
    private var precompileSourceSet: SourceSetImpl? = null

    final override val sourceFiles: List<SourceFile>
        get() = sourceSet.sourceFiles

    final override fun givenJavaSource(name: String, source: String) {
        sourceSet.givenJavaSource(name, source)
    }

    final override fun givenKotlinSource(name: String, source: String) {
        sourceSet.givenKotlinSource(name, source)
    }

    final override fun givenSourceSet(block: SourceSet.() -> Unit): SourceSet {
        return SourceSetImpl().apply(block)
    }

    final override fun useSourceSet(sources: SourceSet) {
        sourceSet.sourceFiles += sources.sourceFiles
    }

    final override fun precompile(sources: SourceSet) {
        var sourceSet = precompileSourceSet
        if (sourceSet == null) {
            sourceSet = SourceSetImpl()
            precompileSourceSet = sourceSet
        }
        sourceSet.sourceFiles += sources.sourceFiles
    }

    protected fun KotlinCompilation.basicKotlinCompilationSetup() {
        verbose = false
        inheritClassPath = false
        javacArguments += "-Xdiags:verbose"
        classpaths = classpaths + when (apiType) {
            ApiType.Compiled -> CompiledApiClasspath
            ApiType.Dynamic -> DynamicApiClasspath
        }.split(':').map(::File).toList()
    }

    protected fun makeClassLoader(compilation: KotlinCompilation): ClassLoader {
        return URLClassLoader(
            Array(compilation.classpaths.size + 1) { i ->
                when (i) {
                    0 -> compilation.classesDir.toURI().toURL()
                    else -> compilation.classpaths[i - 1].toURI().toURL()
                }
            },
            this.javaClass.classLoader,
        )
    }

    protected fun precompileIfNeeded(): File? {
        val sourcesToPrecompile = precompileSourceSet?.sourceFiles
        if (sourcesToPrecompile.isNullOrEmpty()) {
            return null
        }
        val compilation = KotlinCompilation().apply {
            basicKotlinCompilationSetup()
            sources = sourcesToPrecompile
        }
        val result = compilation.compile()
        if (result.exitCode != KotlinCompilation.ExitCode.OK) {
            throw RuntimeException("Pre-compilation failed, check the code")
        }
        return result.outputDirectory
    }

    protected enum class ApiType {
        Compiled,
        Dynamic,
    }

    protected abstract class CompilationResultClauseBase(
        private val result: KotlinCompilation.Result,
        private val compiledClassesLoader: ClassLoader?,
    ) : CompileTestDriver.CompilationResultClause {
        private val messages by lazy {
            populateMessages().toMutableList()
        }

        open val checkMessageText: Boolean get() = true

        open fun populateMessages(): Sequence<Message> {
            return result.parsedMessages()
        }

        override fun withError(message: String) {
            if (!checkMessageText) return
            assertContains(messages.filter { it.kind == Message.Kind.Error }.map(Message::text), message)
            messages.removeIf { it.kind == Message.Kind.Error && it.text == message }
        }

        override fun withWarning(message: String) {
            if (!checkMessageText) return
            assertContains(messages.filter { it.kind == Message.Kind.Warning }.map(Message::text), message)
            messages.removeIf { it.kind == Message.Kind.Warning && it.text == message }
        }

        override fun withNoWarnings() {
            assertEquals(emptyList(), messages.filter { it.kind == Message.Kind.Warning }.toList())
        }

        override fun withNoErrors() {
            assertEquals(emptyList(), messages.filter { it.kind == Message.Kind.Error }.toList())
        }

        override fun inspectGeneratedClass(name: String, callback: (Class<*>) -> Unit) {
            if (result.exitCode == KotlinCompilation.ExitCode.OK) {
                compiledClassesLoader?.let { callback(it.loadClass(name)) }
            }
        }
    }

    protected data class Message(
        val kind: Kind,
        val text: String,
    ) {
        enum class Kind {
            Error,
            Warning,
        }

        override fun toString() = "$kind: $text"
    }

    companion object {

        private fun KotlinCompilation.Result.parsedMessages(): Sequence<Message> {
            return LoggerDecorator.MessageRegex.findAll(messages).map { messageMatch ->
                val (kind, message) = messageMatch.destructured
                Message(
                    kind = when (kind) {
                        "error" -> Message.Kind.Error
                        "warning" -> Message.Kind.Warning
                        else -> throw AssertionError()
                    },
                    text = message.trimIndent(),
                )
            }.memoize()
        }
    }
}
