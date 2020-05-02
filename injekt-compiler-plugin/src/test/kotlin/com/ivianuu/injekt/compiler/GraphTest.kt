package com.ivianuu.injekt.compiler

import org.junit.Test

class GraphTest {

    @Test
    fun testMissingBindingFails() = codegen(
        """
        interface TestComponent {
            val bar: Bar
        }

        @Factory
        fun create(): TestComponent = createImplementation {
            transient { Bar(get()) }
        }
        """
    ) {
        assertInternalError("no binding")
    }

    @Test
    fun testDuplicatedBindingFails() = codegen(
        """
        interface TestComponent {
            val foo: Foo
        }

        @Factory
        fun create(): TestComponent = createImplementation {
            transient { Foo() }
            transient { Foo() }
        }
        """
    ) {
        assertInternalError("multiple")
    }

    @Test
    fun testScopeMismatch() = codegen(
        """
        interface TestComponent {
            val dep: Dep
        }
        
        @TestScope2
        class Dep

        @Factory
        fun create(): TestComponent = createImplementation {
            scope<TestScope>()
        }
        """
    ) {
        assertInternalError("scope mismatch")
    }

}
