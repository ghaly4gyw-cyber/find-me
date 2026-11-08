package com.ghaly.findme.mesh

/**
 * Tracks when the user last touched the app at all (any tap/scroll/keypress),
 * across the whole app lifetime. Used for the dead-man's-switch: if nobody has
 * touched the phone in a long time, something may be wrong.
 */
object ActivityWatcher {
    @Volatile
    var lastInteractionMs: Long = System.currentTimeMillis()
        private set

    fun poke() {
        lastInteractionMs = System.currentTimeMillis()
    }

    fun idleMs(): Long = System.currentTimeMillis() - lastInteractionMs
}
