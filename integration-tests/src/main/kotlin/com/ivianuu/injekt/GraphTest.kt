/*
 * Copyright 2020 Manuel Wrage
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

package com.ivianuu.injekt

import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNotSame
import junit.framework.Assert.assertNull
import org.junit.Test

class GraphTest {

    @Test
    fun testMissingBindingFails() = codegen(
        """
        @Transient class Dep(bar: Bar)
        @InstanceFactory fun createDep(): Dep = create()
        """
    ) {
        assertInternalError("no binding")
    }

    @Test
    fun testDuplicatedBindingFails() = codegen(
        """
        @InstanceFactory
        fun createFoo(): Foo {
            transient { Foo() }
            transient { Foo() }
            return create()
        }
        """
    ) {
        assertInternalError("multiple")
    }

    @Test
    fun testCircularDependency() = codegen(
        """
        @Transient class A(b: B)
        @Transient class B(a: A)
        @InstanceFactory fun createA(): A = create()
    """
    ) {
        assertInternalError("circular")
    }

    @Test
    fun testScopeMismatch() = codegen(
        """
        @TestScope2 class Dep

        @InstanceFactory
        fun createDep(): Dep {
            scope<TestScope>()
            return create()
        }
        """
    ) {
        assertInternalError("scope mismatch")
    }

    @Test
    fun testQualified() = codegen(
        """
        @Target(AnnotationTarget.EXPRESSION, AnnotationTarget.TYPE) 
        @Qualifier 
        annotation class TestQualifier1
        @Target(AnnotationTarget.EXPRESSION, AnnotationTarget.TYPE) 
        @Qualifier 
        annotation class TestQualifier2
        interface TestComponent { 
            val foo1: @TestQualifier1 Foo 
            val foo2: @TestQualifier2 Foo
        }
        
        @Factory
        fun createComponent(): TestComponent {
            @TestQualifier1 scoped { Foo() }
            @TestQualifier2 scoped { Foo() }
            return create()
        }
        
        fun invoke(): Pair<Foo, Foo> { 
            val component = createComponent()
            return component.foo1 to component.foo2
        }
    """
    ) {
        val (foo1, foo2) = invokeSingleFile<Pair<Foo, Foo>>()
        assertNotSame(foo1, foo2)
    }

    @Test
    fun testQualifiedWithValues() = codegen(
        """
            @Target(AnnotationTarget.EXPRESSION, AnnotationTarget.TYPE)
            @Qualifier
            annotation class QualifierWithValue(val value: String)
            
        interface TestComponent {
            val foo1: @QualifierWithValue("A") Foo
            val foo2: @QualifierWithValue("B") Foo
        }
        
        @Factory
        fun createComponent(): TestComponent {
            @QualifierWithValue("A") scoped { Foo() }
            @QualifierWithValue("B") scoped { Foo() }
            return create()
        }
        
        fun invoke(): Pair<Foo, Foo> { 
            val component = createComponent()
            return component.foo1 to component.foo2
        }
    """
    ) {
        val (foo1, foo2) = invokeSingleFile<Pair<Foo, Foo>>()
        assertNotSame(foo1, foo2)
    }

    @Test
    fun testIgnoresNullability() = codegen(
        """
        @InstanceFactory
        fun createFoo(): Foo {
            transient<Foo> { Foo() }
            transient<Foo?> { null }
            return create()
        }
    """
    ) {
        assertInternalError("multiple")
    }

    @Test
    fun testReturnsInstanceForNullableBinidng() = codegen(
        """
        @InstanceFactory
        fun invoke(): Foo? {
            transient<Foo?>()
            return create()
        }
    """
    ) {
        assertNotNull(invokeSingleFile())
    }

    @Test
    fun testReturnsNullOnMissingNullableBinding() = codegen(
        """
        @InstanceFactory
        fun invoke(): Foo? {
            return create()
        }
        """
    ) {
        assertNull(invokeSingleFile())
    }

}
