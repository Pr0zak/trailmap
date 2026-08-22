package com.trailmap.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small in-memory ring of diagnostic lines, shown on the Diagnostics screen and shareable
 * from there.
 *
 * trailmap has no backend to ship logs to, and a public repo can't carry an upload token, so
 * the log lives on the device and the user sends it on deliberately. What goes in is the
 * answer to "why did that take so long": where each fetch was served from, which Overpass
 * mirror answered, how big the response was, and how long each stage took.
 */
object DiagLog {
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val line = "${stamp.format(Date())}  ${tag.padEnd(8)} $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** Newest first, which is what you want when something just went wrong. */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList().asReversed() }

    fun dump(): String = snapshot().joinToString("\n")

    fun clear() = synchronized(lines) { lines.clear() }
}
