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

package com.ivianuu.injekt.android

import android.content.ContentProvider
import com.ivianuu.injekt.*
import com.ivianuu.injekt.constant.constantBuilder

/**
 * Content provider name
 */
object ForContentProvider

/**
 * Returns a [Component] with convenient configurations
 */
fun <T : ContentProvider> T.contentProviderComponent(
    block: (ComponentBuilder.() -> Unit)? = null
): Component = component {
    getClosestComponentOrNull()?.let { dependencies(it) }
    modules(contentProviderModule())
    block?.invoke(this)
}

/**
 * Returns the closest [Component] or null
 */
fun ContentProvider.getClosestComponentOrNull(): Component? =
    getApplicationComponentOrNull()

/**
 * Returns the closest [Component]
 */
fun ContentProvider.getClosestComponent(): Component =
    getClosestComponentOrNull() ?: error("No close component found for $this")

/**
 * Returns the parent [Component] if available or null
 */
fun ContentProvider.getApplicationComponentOrNull(): Component? =
    (context?.applicationContext as? InjektTrait)?.component

/**
 * Returns the parent [Component] or throws
 */
fun ContentProvider.getApplicationComponent(): Component =
    getApplicationComponentOrNull() ?: error("No application component found for $this")

/**
 * Returns a [Module] with convenient bindings
 */
fun <T : ContentProvider> T.contentProviderModule(): Module = module {
    constantBuilder(this@contentProviderModule) {
        bindType<ContentProvider>()
    }
}