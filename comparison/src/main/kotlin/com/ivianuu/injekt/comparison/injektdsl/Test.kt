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

package com.ivianuu.injekt.comparison.injektdsl

import com.ivianuu.injekt.Component
import com.ivianuu.injekt.comparison.Fib8
import com.ivianuu.injekt.comparison.InjectionTest

object InjektDslTest : InjectionTest {
    override val name = "Injekt Dsl"

    private var component: Component? = null

    override fun moduleCreation() {
    }

    override fun setup() {
        component = Component { fibonacci() }
    }

    override fun inject() {
        component!!.get<Fib8>()
    }

    override fun shutdown() {
        component = null
    }
}
