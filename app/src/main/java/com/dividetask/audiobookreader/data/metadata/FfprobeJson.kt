package com.dividetask.audiobookreader.data.metadata

import com.arthenica.ffmpegkit.FFprobeKit
import org.json.JSONObject

/**
 * Runs ffprobe with JSON output and parses it. ffmpeg-kit mixes log lines such as
 * "[aac @ 0x…] Number of bands…" into the same output, so those are stripped first.
 */
fun ffprobeJson(vararg args: String): JSONObject? {
    val session = FFprobeKit.executeWithArguments(
        arrayOf("-v", "error", "-hide_banner", "-print_format", "json", *args)
    )
    val output = session.output ?: return null
    val logLine = Regex("""^\s*\[[^\]]+ @ 0x[0-9a-fA-F]+].*$""")
    val cleaned = output.lineSequence().filterNot { logLine.matches(it) }.joinToString("\n")
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return JSONObject(cleaned.substring(start, end + 1))
}
