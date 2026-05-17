package ru.shapovalov.bedlam.di

import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import me.tatarka.inject.annotations.Provides

interface PresentationModule {

    @AppScope
    @Provides
    fun provideStoreFactory(): StoreFactory = DefaultStoreFactory()
}
