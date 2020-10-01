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

package com.ivianuu.injekt.samples.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.setContent
import androidx.lifecycle.ViewModel
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ivianuu.injekt.Assisted
import com.ivianuu.injekt.Binding
import com.ivianuu.injekt.ChildComponent
import com.ivianuu.injekt.Module
import com.ivianuu.injekt.android.ActivityContext
import com.ivianuu.injekt.android.ActivityModule
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val activityComponent = appComponent.mainActivityComponentFactory(this)
            with(activityComponent) {
                WithMainViewModel {
                    GlobalScope.launch {
                        enqueueWork()
                    }
                }
            }
        }
    }
}

@ChildComponent
abstract class MainActivityComponent(activity: MainActivity) {
    abstract val WithMainViewModel: WithMainViewModel
    abstract val enqueueWork: enqueueWork

    @Module
    protected val activityModule = ActivityModule(activity)
    @Module
    protected val dataModule = DataModule
}

@Binding
@Composable
fun WithMainViewModel(
    viewModelFactory: () -> MainViewModel,
    @Assisted children: @Composable (MainViewModel) -> Unit,
) {
    val viewModel = remember { viewModelFactory() }
    children(viewModel)
}

@Binding
fun enqueueWork(context: ActivityContext) {
    WorkManager.getInstance(context)
        .enqueue(
            OneTimeWorkRequestBuilder<TestWorker>()
                .build()
        )
}

@Binding
class MainViewModel : ViewModel() {
    init {
        println("init")
    }

    override fun onCleared() {
        println("on cleared")
        super.onCleared()
    }
}
