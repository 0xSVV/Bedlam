package ru.shapovalov.bedlam

import android.app.Application
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.api.HysteriaClient

class BedlamApplication : Application() {
    val hysteriaClient: HysteriaClient by lazy { HysteriaClientImpl() }
}
