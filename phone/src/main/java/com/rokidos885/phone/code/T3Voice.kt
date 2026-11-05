package com.rokidos885.phone.code

/** The voice that reads T3 to the user (on the phone, heard in the glasses). It never logs what it says. */
interface T3Voice {
    fun speak(text: String)

    /** Reads [parts] in order. [onPart] gets i as part i starts, then parts.size after the last one. */
    fun read(parts: List<String>, onPart: (Int) -> Unit)

    /** Stops what is being read and what waits: a dictation is about to listen. */
    fun stop()
}
