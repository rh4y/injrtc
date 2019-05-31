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

package com.ivianuu.injekt.compiler

import com.ivianuu.injekt.*
import com.ivianuu.injekt.eager.Eager
import com.ivianuu.injekt.multi.Multi
import com.ivianuu.injekt.multibinding.BindingMap
import com.ivianuu.injekt.multibinding.BindingSet
import com.ivianuu.injekt.weak.Weak
import me.eugeniomarletti.kotlin.metadata.KotlinClassMetadata
import me.eugeniomarletti.kotlin.metadata.kotlinMetadata
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.deserialization.Flags
import javax.lang.model.element.Element

val kindAnnotations = setOf(
    Eager::class,
    Factory::class,
    Multi::class,
    Single::class,
    Weak::class
)

val paramAnnotations = setOf(
    BindingMap::class, BindingSet::class, Name::class, Param::class, Raw::class
)

val Element.isObject: Boolean
    get() {
        return Flags.CLASS_KIND.get(
            (kotlinMetadata as? KotlinClassMetadata)
                ?.data?.classProto?.flags ?: 0
        ) == ProtoBuf.Class.Kind.OBJECT
    }