package dev.opencode.hexcode

import android.app.Application
import dev.opencode.hexcode.app.HexServices

class HexCodeApplication : Application() {
    val services: HexServices by lazy { HexServices(this) }
}
