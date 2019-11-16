/*
 * Copyright 2019 Manuel Wrage
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

// todo clean multi binding api mess

data class BindingContext<T> internal constructor(
    val binding: Binding<T>,
    val key: Key,
    val override: Boolean,
    val module: Module
)

inline fun <reified T> BindingContext<*>.bindAlias(
    name: Any? = null,
    override: Boolean = this.override
) {
    bindAlias(typeOf<T>(), name, override)
}

fun BindingContext<*>.bindAlias(
    type: Type<*>,
    name: Any? = null,
    override: Boolean = this.override
) {
    module.bind(
        key = keyOf(type, name),
        binding = binding as Binding<Any?>,
        override = override
    )
}

inline fun <reified T> BindingContext<*>.bindType() {
    bindAlias(typeOf<T>())
}

infix fun <T> BindingContext<T>.bindType(type: Type<*>): BindingContext<T> {
    bindAlias(type)
    return this
}

infix fun <T> BindingContext<T>.bindName(name: Any): BindingContext<T> {
    bindAlias(key.type, name)
    return this
}

inline fun <reified T : V, reified K, reified V> BindingContext<T>.intoMap(
    entryKey: K,
    mapName: Any? = null,
    override: Boolean = false
): BindingContext<T> = intoMap(typeOf(), typeOf<V>(), entryKey, mapName, override)

fun <T : V, K, V> BindingContext<T>.intoMap(
    mapKeyType: Type<K>,
    mapValueType: Type<V>,
    entryKey: K,
    mapName: Any? = null,
    override: Boolean = false
): BindingContext<T> {
    module.map(mapKeyType, mapValueType, mapName) {
        put(entryKey, key.type as Type<T>, key.name, override)
    }
    return this
}

inline fun <reified T : E, reified E> BindingContext<T>.intoSet(
    setName: Any? = null,
    override: Boolean = false
): BindingContext<T> = intoSet(typeOf<E>(), setName, override)

fun <T : E, E> BindingContext<T>.intoSet(
    setElementType: Type<E>,
    setName: Any? = null,
    override: Boolean = false
): BindingContext<T> {
    module.set(setElementType, setName) {
        add(key.type as Type<T>, key.name, override)
    }
    return this
}