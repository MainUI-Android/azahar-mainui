package io.github.mainui.stateapi

import org.citra.citra_emu.NativeLibrary

/**
 * Azahar's side of the MainUI State API ([MainUIStateApi]). MainUI's slots 0-9 are Azahar's
 * slots 1-10 (0 is its quick save); MainUI's resume state is slot 99, which Azahar's menu doesn't
 * list. Saves and loads are signalled to the emulation thread like the menu's; a save is answered
 * once its file is written.
 */
object AzaharStates : MainUIStateApi.Emulator {
    private const val RESUME = 99

    /** The emulation screen is in front. */
    @Volatile private var resumed = false

    /** A game was just asked for: the one running before it doesn't count until it has stopped. */
    @Volatile private var waitForStop = false

    /**
     * The activity is up: attach. [newGame]: a game is starting, the one running before it
     * doesn't count until it has stopped (not when the activity is only recreated, which keeps
     * the game running).
     */
    @JvmStatic
    fun launched(newGame: Boolean) {
        if (newGame) waitForStop = NativeLibrary.isRunning()
        MainUIStateApi.attach(this)
    }

    @JvmStatic
    fun resumed(r: Boolean) { resumed = r }

    private fun n(slot: Int) = if (slot == MainUIStateApi.RESUME_SLOT) RESUME else slot + 1

    override fun isRunning(): Boolean {
        val running = NativeLibrary.isRunning() && NativeLibrary.getRunningTitleId() != 0L
        if (waitForStop) {
            if (running) return false
            waitForStop = false
        }
        return running
    }

    override fun isReady() = resumed && isRunning() && NativeLibrary.mainuiReady()

    override fun slotCount() = NativeLibrary.SAVESTATE_SLOT_COUNT - 1

    /** MainUI's pause took effect (not one Azahar's own menu holds). */
    @Volatile private var pausedByMainUi = false

    override fun pause(): Boolean {
        if (NativeLibrary.mainuiReady()) {
            NativeLibrary.pauseEmulation()
            pausedByMainUi = true
        }
        return true
    }

    override fun resume() {
        if (!pausedByMainUi) return
        pausedByMainUi = false
        NativeLibrary.unPauseEmulation()
    }

    override fun liftOwnPause(): Boolean {
        if (pausedByMainUi || !isRunning() || NativeLibrary.mainuiReady()) return false
        NativeLibrary.unPauseEmulation()
        return true
    }

    override fun restoreOwnPause() {
        if (NativeLibrary.mainuiReady()) NativeLibrary.pauseEmulation()
        pausedByMainUi = false // (the user's pause again, even if MainUI paused meanwhile)
    }

    override fun save(slot: Int): String {
        val n = n(slot)
        val start = System.currentTimeMillis() / 1000 * 1000 // (the state's time is in whole seconds)
        NativeLibrary.saveState(n)
        // The header is written first, the state after it: done once the file stops growing.
        var size = -1L
        var same = 0
        if (!MainUIStateApi.await({
                if (NativeLibrary.mainuiSlotTime(n) < start) return@await false
                val now = NativeLibrary.mainuiSlotSize(n)
                same = if (now == size && now > 256) same + 1 else 0
                size = now
                Thread.sleep(100)
                same >= 3
            }, 60_000)) {
            throw IllegalStateException("Azahar didn't finish saving the state")
        }
        return "Azahar slot $n"
    }

    override fun load(slot: Int) {
        val n = n(slot)
        if (NativeLibrary.mainuiSlotTime(n) == 0L) throw IllegalStateException("slot $slot is empty")
        NativeLibrary.loadState(n)
        // (Carried out by the emulation thread at its next frame.)
        Thread.sleep(500)
    }

    override fun slotTime(slot: Int) = NativeLibrary.mainuiSlotTime(n(slot))
}
