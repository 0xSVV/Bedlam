package ru.shapovalov.bedlam.di

import me.tatarka.inject.annotations.Scope

/**
 * Singleton scope for the application's root [AppComponent]. Anything annotated
 * with [AppScope] is constructed once and cached for the process lifetime.
 */
@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class AppScope
