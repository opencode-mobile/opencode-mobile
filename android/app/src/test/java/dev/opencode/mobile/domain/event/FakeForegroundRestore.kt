package dev.opencode.mobile.domain.event

class FakeForegroundRestore : ForegroundRestore {
    var restoreCount = 0
        private set
    var backgroundCount = 0
        private set
    var backgroundStopCount = 0
        private set
    var openSessionId: String? = null
        private set
    var homeVisible = false
        private set

    override fun restore() {
        restoreCount += 1
    }

    override fun noteBackground() {
        backgroundCount += 1
    }

    override fun noteBackgroundStop() {
        backgroundStopCount += 1
    }

    override fun setOpenSession(
        sessionId: String?,
        directory: String?,
    ) {
        openSessionId = sessionId
    }

    override fun setHomeVisible(visible: Boolean) {
        homeVisible = visible
    }
}
