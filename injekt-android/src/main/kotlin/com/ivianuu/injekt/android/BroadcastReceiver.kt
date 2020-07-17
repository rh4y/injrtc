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
import android.content.BroadcastReceiver
import android.content.Context
import com.ivianuu.injekt.Component
import com.ivianuu.injekt.Distinct
import com.ivianuu.injekt.childComponent
import com.ivianuu.injekt.runReader

@Component
interface ReceiverComponent

@Distinct
typealias ReceiverContext = Context

fun BroadcastReceiver.newReceiverComponent(context: Context): ReceiverComponent {
    return (context.applicationContext as Application).applicationComponent.runReader {
        childComponent(this, context as ReceiverContext)
    }
}
