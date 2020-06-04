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

import com.ivianuu.injekt.test.codegen
import com.ivianuu.injekt.test.invokeSingleFile
import com.ivianuu.injekt.test.multiCodegen
import com.ivianuu.injekt.test.source
import org.junit.Test

class AssistedTest {

    @Test
    fun testAssistedWithAnnotations() = codegen(
        """
        @Transient
        class Dep(
            @Assisted val assisted: String,
            val foo: Foo
        )
        
        @InstanceFactory
        fun factory(): @Provider (String) -> Dep {
            transient<Foo>()
            return create()
        }
        
        fun invoke() {
            val depFactory = factory()
            val result: Dep = depFactory("hello world")
        }
    """
    ) {
        invokeSingleFile()
    }

    @Test
    fun testAssistedInDsl() = codegen(
        """ 
        @InstanceFactory
        fun factory(): @Provider (Foo) -> Bar {
            transient { foo: @Assisted Foo -> Bar(foo) }
            return create()
        }
        
        fun invoke() {
            val barFactory = factory()
            val bar: Bar = barFactory(Foo())
        }
    """
    ) {
        invokeSingleFile()
    }

    @Test
    fun testMultiCompileAssistedWithAnnotations() = multiCodegen(
        listOf(
            source(
                """
                @Transient 
                class Dep(
                    @Assisted val assisted: String,
                    val foo: Foo
                )
                """
            )
        ),
        listOf(
            source(
                """
                    @InstanceFactory 
                    fun factory(): @Provider (String) -> Dep { 
                        instance(Foo())
                        return create()
                    }
                    
                    fun invoke() {
                        val depFactory = factory()
                        val result = depFactory("hello world")
                    }
                """,
                name = "File.kt"
            )
        )
    ) {
        it.last().invokeSingleFile()
    }

}
