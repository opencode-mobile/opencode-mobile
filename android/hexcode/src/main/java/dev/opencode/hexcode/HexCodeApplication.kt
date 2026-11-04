package dev.opencode.hexcode

import android.app.Application
import dev.opencode.hexcode.app.HexServices
import dev.opencode.hexcode.theme.HexTheme

class HexCodeApplication : Application() {
    val services: HexServices by lazy { HexServices(this) }

    override fun onCreate() {
        super.onCreate()
        HexTheme.install(this)
    }
}
