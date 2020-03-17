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

/**
 * The heart of the library which provides instances
 * Dependencies can be requested by calling [get]
 * Use [ComponentBuilder] to construct [Component] instances
 *
 * Typical usage of a [Component] looks like this:
 *
 * ´´´
 * val component = Component {
 *     single { Api(get()) }
 *     single { Database(get(), get()) }
 * }
 *
 * val api = component.get<Api>()
 * val database = component.get<Database>()
 * ´´´
 *
 * @see get
 * @see getLazy
 * @see ComponentBuilder
 */
class Component internal constructor(
    val scopes: List<Scope>,
    val dependencies: List<Component>,
    val justInTimeBindingFactories: List<JustInTimeBindingFactory>,
    bindings: MutableMap<Key<*>, Binding<*>>
) {

    private val _bindings = bindings
    val bindings: Map<Key<*>, Binding<*>> get() = _bindings

    private var initializedBindings: MutableList<Binding<*>>? = mutableListOf()

    init {
        for (binding in _bindings.values) {
            val initializedBindings = initializedBindings!!
            if (binding !in initializedBindings) {
                initializedBindings += binding
                (binding.provider as? ComponentInitObserver)?.onInit(this)
            }
        }
        initializedBindings = null // Don't needed anymore
    }

    inline fun <reified T> get(
        qualifier: Qualifier = Qualifier.None,
        parameters: Parameters = emptyParameters()
    ): T = get(key = keyOf(qualifier = qualifier), parameters = parameters)

    /**
     * Retrieve a instance of type [T] for [key]
     */
    fun <T> get(key: Key<T>, parameters: Parameters = emptyParameters()): T {
        findExplicitBinding(key)?.let { return it.provider(this, parameters) }
        findJustInTimeBinding(key)?.let { return it.provider(this, parameters) }
        if (key.isNullable) return null as T
        error("Couldn't get instance for $key")
    }

    /**
     * Returns the [Component] for [scope] or throws
     */
    fun getComponent(scope: Scope): Component =
        findComponent(scope) ?: error("Couldn't find component for scope $scope")

    private fun findComponent(scope: Scope): Component? {
        if (scope in scopes) return this

        dependencies.fastForEach { dependency ->
            dependency.findComponent(scope)?.let { return it }
        }

        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> findExplicitBinding(key: Key<T>): Binding<T>? {
        var binding = synchronized(_bindings) { _bindings[key] } as? Binding<T>
        if (binding != null && !key.isNullable && binding.key.isNullable) {
            binding = null
        }
        if (binding != null) {
            initializedBindings?.let {
                // we currently initialize bindings
                // make sure that the requested binding gets also initialized
                if (binding!! !in it) {
                    it += binding!!
                    (binding!!.provider as? ComponentInitObserver)?.onInit(this)
                }
            }
            return binding
        }

        for (index in dependencies.size - 1 downTo 0) {
            binding = dependencies[index].findExplicitBinding(key)
            if (binding != null) return binding
        }

        return null
    }

    private fun <T> findJustInTimeBinding(key: Key<T>): Binding<T>? {
        for (index in justInTimeBindingFactories.size - 1 downTo 0) {
            val binding = justInTimeBindingFactories[index].create(key, this)
            if (binding != null) {
                // todo finding the right component is relatively small maybe
                //  we can cache the scope bound of the behaviors when combining them
                var boundBehavior: BoundBehavior? = null
                binding.behavior.foldOut(null) { behavior, element ->
                    if (boundBehavior == null && behavior is BoundBehavior)
                        boundBehavior = behavior
                    element
                }
                val component = if (boundBehavior != null && boundBehavior!!.scope != null) {
                    getComponent(boundBehavior!!.scope!!)
                } else {
                    this
                }
                synchronized(component._bindings) { component._bindings[key] = binding }
                initializedBindings?.let { it += binding }
                (binding.provider as? ComponentInitObserver)?.onInit(component)
                return binding
            }
        }

        return null
    }
}
