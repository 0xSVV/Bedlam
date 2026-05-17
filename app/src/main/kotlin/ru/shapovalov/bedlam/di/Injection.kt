package ru.shapovalov.bedlam.di

import android.content.Context
import ru.shapovalov.bedlam.BedlamApplication

val Context.appComponent: AppComponent
    get() = (applicationContext as BedlamApplication).component

inline fun <reified T : Any> Context.injected(
    crossinline get: AppComponent.() -> T,
): Lazy<T> {
    val context = this
    return lazy { context.appComponent.get() }
}
