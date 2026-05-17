package ru.shapovalov.bedlam.di

import android.app.Application
import android.content.Context
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.api.HysteriaClient

/**
 * Root dependency graph for the process. Built once in `BedlamApplication.onCreate`
 * and held for the lifetime of the application. Feature/screen-level graphs
 * should be modeled as child @Component classes that take this one as a parent.
 */
@AppScope
@Component
abstract class AppComponent(@get:Provides val application: Application) {

    abstract val hysteriaClient: HysteriaClient

    @get:Provides
    val context: Context
        get() = application

    @AppScope
    @Provides
    fun provideHysteriaClient(): HysteriaClient = HysteriaClientImpl()
}
