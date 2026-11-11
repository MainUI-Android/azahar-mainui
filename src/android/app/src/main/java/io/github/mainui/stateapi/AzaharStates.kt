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

    /** A game is starting (the activity created, or handed a new game): attach for it. */
    @JvmStatic
    fun launched() {
        waitForStop = NativeLibrary.isRunning()
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

    override fun save(slot: Int): String {
        val n = n(slot)
        val start = System.currentTimeMillis() / 1000 * 1000 // (the state's time is in whole seconds)
        NativeLibrary.saveState(n)
        if (!MainUIStateApi.await({ NativeLibrary.mainuiSlotTime(n) >= start }, 60_000)) {
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
