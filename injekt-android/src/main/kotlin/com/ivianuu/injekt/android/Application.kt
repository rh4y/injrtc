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

package com.ivianuu.injekt.android

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.ivianuu.injekt.CompositionFactory
import com.ivianuu.injekt.Module
import com.ivianuu.injekt.Qualifier
import com.ivianuu.injekt.Scope
import com.ivianuu.injekt.compositionFactoryOf
import com.ivianuu.injekt.createImpl
import com.ivianuu.injekt.instance
import com.ivianuu.injekt.scope

@Scope
annotation class ApplicationScoped

@Target(AnnotationTarget.EXPRESSION, AnnotationTarget.TYPE)
@Qualifier
annotation class ForApplication

interface ApplicationComponent

val Application.applicationComponent: ApplicationComponent
    get() = ProcessLifecycleOwner.get().lifecycle.singleton {
        compositionFactoryOf<ApplicationComponent,
                @CompositionFactory (Application) -> ApplicationComponent>()
            .invoke(this)
    }

@CompositionFactory
fun createApplicationComponent(instance: Application): ApplicationComponent {
    scope<ApplicationScoped>()
    instance(instance)
    return createImpl()
}

@Module
fun createApplicationComponentModule(instance: Application) {
    scope<ApplicationScoped>()
    instance(instance)
}
