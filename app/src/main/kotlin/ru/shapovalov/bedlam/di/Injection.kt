package ru.shapovalov.bedlam.di

import android.content.Context
import ru.shapovalov.bedlam.BedlamApplication

/**
 * The process-wide [AppComponent]. Available from any [Context] (Activity,
 * Service, Application) since [BedlamApplication] is the resolved
 * application instance for the whole process.
 */
val Context.appComponent: AppComponent
    get() = (applicationContext as BedlamApplication).component

/**
 * Lazy property delegate that pulls a single binding out of [appComponent].
 *
 * ```
 * class MainActivity : ComponentActivity() {
 *     private val client by injected { hysteriaClient }
 * }
 * ```
 *
 * The lambda runs on [AppComponent] as receiver, so the component's accessors
 * are statically resolved. No reflection, no runtime lookup table.
 */
inline fun <reified T : Any> Context.injected(
    crossinline get: AppComponent.() -> T,
): Lazy<T> {
    val context = this
    return lazy { context.appComponent.get() }
}
