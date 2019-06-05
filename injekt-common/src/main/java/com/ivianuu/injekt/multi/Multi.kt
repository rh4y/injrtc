/*
 * Copyright 2018 Manuel Wrage
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

package com.ivianuu.injekt.multi

import com.ivianuu.injekt.Binding
import com.ivianuu.injekt.Definition
import com.ivianuu.injekt.DefinitionBinding
import com.ivianuu.injekt.Linker
import com.ivianuu.injekt.ModuleBuilder
import com.ivianuu.injekt.ParametersDefinition
import com.ivianuu.injekt.Qualifier
import com.ivianuu.injekt.Type
import com.ivianuu.injekt.add
import com.ivianuu.injekt.typeOf
import kotlin.collections.set

inline fun <reified T> ModuleBuilder.multi(
    name: Qualifier? = null,
    override: Boolean = false,
    noinline definition: Definition<T>
): Binding<T> = multi(typeOf(), name, override, definition)

fun <T> ModuleBuilder.multi(
    type: Type<T>,
    name: Qualifier? = null,
    override: Boolean = false,
    definition: Definition<T>
): Binding<T> = add(MultiBinding(DefinitionBinding(definition)), type, name, override)

@Target(AnnotationTarget.CLASS)
annotation class Multi

private class MultiBinding<T>(private val binding: Binding<T>) : Binding<T> {

    private val values = mutableMapOf<Int, T>()

    override fun link(linker: Linker) {
        binding.link(linker)
    }

    override fun get(parameters: ParametersDefinition?): T {
        requireNotNull(parameters) { "Parameters cannot be null" }

        val params = parameters()

        val key = params.hashCode()

        var value = values[key]

        return if (value == null && !values.containsKey(key)) {
            value = binding.get(parameters)
            values[key] = value
            value
        } else {
            value as T
        }
    }

}