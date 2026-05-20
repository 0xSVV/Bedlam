package ru.shapovalov.bedlam

import android.app.Application
import ru.shapovalov.bedlam.di.AppComponent
import ru.shapovalov.bedlam.di.create

class BedlamApplication : Application() {

    lateinit var component: AppComponent
        private set

    override fun onCreate() {
        super.onCreate()
        component = AppComponent::class.create(this)
        component.logBuffer
    }
}
