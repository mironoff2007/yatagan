package com.yandex.daggerlite.testing

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import javax.inject.Provider

@RunWith(Parameterized::class)
class RuntimeTest(
    driverProvider: Provider<CompileTestDriverBase>
) : CompileTestDriver by driverProvider.get() {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = compileTestDrivers()
    }

    @Test
    fun `check qualified providings are correct`() {
        givenKotlinSource("test.TestCase", """
            class Simple(val value: String)

            @Module
            object MyModule {
                @Provides fun simple() = Simple("simple")
                @Provides @Named("one") fun one() = Simple("one")
                @Provides @Named("two") fun two() = Simple("two")                
            }

            @Component(modules = [MyModule::class])
            interface TestComponent {
                fun simple(): Simple
                @Named("one") fun one(): Simple
                @Named("two") fun two(): Simple
            }

            fun test() {
                val c = DaggerTestComponent()
                assert(c.simple().value == "simple")
                assert(c.one().value == "one")
                assert(c.two().value == "two")
            }
        """
        )

        compilesSuccessfully {
            generatesJavaSources("test.DaggerTestComponent")
            withNoWarnings()
            inspectGeneratedClass("test.TestCaseKt") { tc ->
                tc["test"](null)
            }
        }
    }

    @Test
    fun `check @Provides used instead of @Inject constructor if exists`() {
        givenKotlinSource("test.TestCase", """
            class Impl(val value: Int)
            class Wrapper @Inject constructor(val i: Impl)

            @Module
            object MyModule {
                @Provides fun impl() = Impl(1)
                @Provides fun wrapper() = Wrapper(Impl(2))          
            }

            @Component(modules = [MyModule::class])
            interface TestComponent {
                fun impl(): Impl
                fun wrapper(): Wrapper
            }

            fun test() {
                val c = DaggerTestComponent()
                assert(c.impl().value == 1)
                assert(c.wrapper().i.value == 2)
            }
        """
        )

        compilesSuccessfully {
            generatesJavaSources("test.DaggerTestComponent")
            withNoWarnings()
            inspectGeneratedClass("test.TestCaseKt") { tc ->
                tc["test"](null)
            }
        }
    }
}