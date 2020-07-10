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

import com.ivianuu.injekt.test.Command
import com.ivianuu.injekt.test.CommandA
import com.ivianuu.injekt.test.CommandB
import com.ivianuu.injekt.test.CommandC
import com.ivianuu.injekt.test.assertInternalError
import com.ivianuu.injekt.test.assertOk
import com.ivianuu.injekt.test.codegen
import com.ivianuu.injekt.test.invokeSingleFile
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class MapTest {

    @Test
    fun testMapOfValueInstance() = codegen(
        """
        @Given @Reader fun commandA() = CommandA()
        @MapEntries(TestComponent::class) @Reader
        fun commandAIntoMap(): Map<KClass<out Command>, Command> = mapOf(CommandA::class to given<CommandA>())
        @Given @Reader fun commandB() = CommandB()
        @MapEntries(TestComponent::class) @Reader
        fun commandBIntoMap(): Map<KClass<out Command>, Command> = mapOf(CommandB::class to given<CommandB>())
        @Given @Reader fun commandC() = CommandC()
        @MapEntries(TestComponent::class) @Reader
        fun commandCIntoMap(): Map<KClass<out Command>, Command> = mapOf(CommandC::class to given<CommandC>())
        
        fun invoke(): Map<KClass<out Command>, Command> {
            initializeComponents()
            val component = componentFactory<TestComponent.Factory>().create()
            return component.runReader { given<Map<KClass<out Command>, Command>>() }
        }
        """
    ) {
        val map =
            invokeSingleFile<Map<KClass<out Command>, Command>>()
        assertEquals(3, map.size)
        assertTrue(map[CommandA::class] is CommandA)
        assertTrue(map[CommandB::class] is CommandB)
        assertTrue(map[CommandC::class] is CommandC)
    }

    @Test
    fun testMapOfValueProvider() = codegen(
        """
        @Factory
        fun factory(): TestComponent1<@Provider () -> Map<KClass<out Command>, Command>> {
            unscoped { CommandA() }
            unscoped { CommandB() }
            unscoped { CommandC() }
            map<KClass<out Command>, Command> {
                put<CommandA>(CommandA::class)
                put<CommandB>(CommandB::class)
                put<CommandC>(CommandC::class)
            }
            return create()
        }
        
        fun invoke() = factory().a
        """
    ) {
        val map =
            invokeSingleFile<() -> Map<KClass<out Command>, Command>>()()
        assertEquals(3, map.size)
        assertTrue(map[CommandA::class] is CommandA)
        assertTrue(map[CommandB::class] is CommandB)
        assertTrue(map[CommandC::class] is CommandC)
    }

    @Test
    fun testMapOfProviderInstance() = codegen(
        """
        @Factory
        fun factory(): TestComponent1<Map<KClass<out Command>, () -> Command>> {
            unscoped { CommandA() }
            unscoped { CommandB() }
            unscoped { CommandC() }
            map<KClass<out Command>, Command> {
                put<CommandA>(CommandA::class)
                put<CommandB>(CommandB::class)
                put<CommandC>(CommandC::class)
            }
            return create()
        }
        
        fun invoke() = factory().a
    """
    ) {
        val map =
            invokeSingleFile<Map<KClass<out Command>, () -> Command>>()
        assertEquals(3, map.size)
        assertTrue(map[CommandA::class]!!() is CommandA)
        assertTrue(map[CommandB::class]!!() is CommandB)
        assertTrue(map[CommandC::class]!!() is CommandC)
    }

    @Test
    fun testMapOfProviderProvider() = codegen(
        """
        @Factory
        fun factory(): TestComponent1<() -> Map<KClass<out Command>, () -> Command>> {
            unscoped { CommandA() }
            unscoped { CommandB() }
            unscoped { CommandC() }
            map<KClass<out Command>, Command> {
                put<CommandA>(CommandA::class)
                put<CommandB>(CommandB::class)
                put<CommandC>(CommandC::class)
            }
            return create()
        }
        
        fun invoke() = factory().a
    """
    ) {
        val map =
            invokeSingleFile<() -> Map<KClass<out Command>, () -> Command>>()()
        assertEquals(3, map.size)
        assertTrue(map[CommandA::class]!!() is CommandA)
        assertTrue(map[CommandB::class]!!() is CommandB)
        assertTrue(map[CommandC::class]!!() is CommandC)
    }

    @Test
    fun testMapOfAssistedProvider() = codegen(
        """
        @Factory
        fun factory(): TestComponent1<Map<KClass<out Command>, (String) -> Command>> {
            unscoped { arg: String -> CommandA() }
            unscoped { arg: String -> CommandB() }
            unscoped { arg: String -> CommandC() }
            map<KClass<out Command>, (String) -> Command> {
                put<(String) -> CommandA>(CommandA::class)
                put<(String) -> CommandB>(CommandB::class)
                put<(String) -> CommandC>(CommandC::class)
            }
            return create()
        }
        
        fun invoke() = factory().a
        """
    ) {
        val map =
            invokeSingleFile<Map<KClass<out Command>, (String) -> Command>>()
        assertEquals(3, map.size)
        assertTrue(map[CommandA::class]!!("a") is CommandA)
        assertTrue(map[CommandB::class]!!("b") is CommandB)
        assertTrue(map[CommandC::class]!!("c") is CommandC)
    }

    @Test
    fun testMapOfAssistedProviderProvider() = codegen(
        """
        @Factory
        fun factory(): TestComponent1<() -> Map<KClass<out Command>, (String) -> Command>> {
            unscoped { arg: String -> CommandA() }
            unscoped { arg: String -> CommandB() }
            unscoped { arg: String -> CommandC() }
            map<KClass<out Command>, (String) -> Command> {
                put<(String) -> CommandA>(CommandA::class)
                put<(String) -> CommandB>(CommandB::class)
                put<(String) -> CommandC>(CommandC::class)
            }
            return create()
        }
        
        fun invoke() = factory().a
        """
    ) {
        val map =
            invokeSingleFile<() -> Map<KClass<out Command>, (String) -> Command>>()()
        assertEquals(3, map.size)
        assertTrue(map[CommandA::class]!!("a") is CommandA)
        assertTrue(map[CommandB::class]!!("b") is CommandB)
        assertTrue(map[CommandC::class]!!("c") is CommandC)
    }

    @Test
    fun testEmptyMap() = codegen(
        """
        @Factory
        fun factory(): TestComponent1<Map<KClass<out Command>, Command>> {
            map<KClass<out Command>, Command>()
            return create()
        }
        
        fun invoke() = factory().a
         """
    ) {
        val map =
            invokeSingleFile<Map<KClass<out Command>, Command>>()
        assertEquals(0, map.size)
    }

    @Test
    fun testUndeclaredMap() = codegen(
        """
        @Factory
        fun factory(): TestComponent1<Map<String, Command>> {
            return create()
        }
        
        fun invoke() = factory().a
        """
    ) {
        assertInternalError("no binding found")
    }

    @Test
    fun testNestedMap() = codegen(
        """
        interface ParentComponent {
            val map: Map<KClass<out Command>, Command>
            val childFactory: @ChildFactory () -> ChildComponent
        }
        
        interface ChildComponent {
            val map: Map<KClass<out Command>, Command>
        }
        
        @Factory
        fun createParent(): ParentComponent {
            unscoped { CommandA() }
            map<KClass<out Command>, Command> {
                put<CommandA>(CommandA::class)
            }
            childFactory(::createChild)
            return create()
        }
        
        @ChildFactory
        fun createChild(): ChildComponent {
            unscoped { CommandB() }
            map<KClass<out Command>, Command> {
                put<CommandB>(CommandB::class)
            }
            return create()
        }
        
        fun invoke() = createParent().childFactory().map
    """
    ) {
        val map =
            invokeSingleFile<Map<KClass<out Command>, Command>>()
        assertEquals(2, map.size)
        assertTrue(map[CommandA::class] is CommandA)
        assertTrue(map[CommandB::class] is CommandB)
    }

    @Test
    fun testQualifiedMapOfValue() = codegen(
        """
        @Factory
        fun factory(): TestComponent1<@TestQualifier1 Map<KClass<*>, Any>> {
            map<@TestQualifier1 Map<KClass<*>, Any>, KClass<*>, Any>()
            return create()
        }
        
        fun invoke() = factory().a
        """
    ) {
        assertOk()
    }

    @Test
    fun testQualifiedMapOfProvider() = codegen(
        """
        @Factory
        fun factory(): TestComponent1<@TestQualifier1 Map<KClass<*>, () -> Any>> {
            map<@TestQualifier1 Map<KClass<*>, Any>, KClass<*>, Any>()
            return create()
        }
        
        fun invoke() = factory().a
        """
    ) {
        assertOk()
    }

}
