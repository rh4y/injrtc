package com.ivianuu.injekt.sample

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.ivianuu.injekt.APPLICATION_SCOPE
import com.ivianuu.injekt.InjektTrait
import com.ivianuu.injekt.Provider
import com.ivianuu.injekt.annotations.Factory
import com.ivianuu.injekt.annotations.Name
import com.ivianuu.injekt.annotations.Raw
import com.ivianuu.injekt.annotations.Single


inline fun Any.d(m: () -> String) {
    Log.d(javaClass.simpleName, m())
}

/**
 * Used to allow fragment views to depend on the fragment component
 */
class InjektTraitContextWrapper(
    base: Context,
    injektTrait: InjektTrait
) : ContextWrapper(base), InjektTrait by injektTrait

@Single(APPLICATION_SCOPE, "name", true, true)
class DummyDep

@Factory
class DummyDep2

@Single(APPLICATION_SCOPE)
class DummyDep3(
    @Name("name") val dummyDep: DummyDep,
    val dummyDep2: DummyDep2
)

@Factory
class DummyDep4(
    @Raw val rawProvider: Provider<String>,
    @Name("name") @Raw val namedRawLazy: Lazy<Int>
)