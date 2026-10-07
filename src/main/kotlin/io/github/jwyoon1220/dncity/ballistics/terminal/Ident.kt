// AGENT-DONE(claude): terminal-ballistics-traversal
package io.github.jwyoon1220.dncity.ballistics.terminal

/**
 * Id of a data entry (`namespace:path`). The ballistics core uses this instead of Minecraft's ResourceLocation so that
 * it, and its unit tests, run on a plain JVM; the Minecraft boundary (data reload) converts, see TerminalBallistics.kt.
 */
data class Ident(val namespace: String, val path: String) {
    init {
        require(namespace.isNotEmpty() && path.isNotEmpty()) { "empty id part: '$namespace:$path'" }
    }

    override fun toString(): String = "$namespace:$path"

    companion object {
        fun parse(text: String): Ident {
            val i = text.indexOf(':')
            return if (i < 0) Ident("minecraft", text) else Ident(text.substring(0, i), text.substring(i + 1))
        }
    }
}
