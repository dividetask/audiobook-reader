package com.dividetask.audiobookreader.audible.download

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.StatisticsCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.data.metadata.ffprobeJson
import java.io.File
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AaxConverter @Inject constructor(
    private val debugLog: AppDebugLog
) {
    private data class EncoderOption(
        val name: String,
        val codec: String,
        val ext: String,
        val args: String
    )

    // Stream copy first: it only remuxes, so it takes about as long as copying the
    // file and keeps the original quality. Re-encoding is the fallback.
    private val encoders = listOf(
        EncoderOption("Stream copy (M4B)", "copy_m4b", "m4b", "-c:a copy"),
        EncoderOption("Stream copy (M4A)", "copy_m4a", "m4a", "-c:a copy"),
        EncoderOption("AAC (strict -2)", "aac", "m4a", "-c:a aac -b:a 128k -strict -2"),
        EncoderOption("AAC (resample)", "aac2", "m4a", "-c:a aac -b:a 128k -ar 44100 -ac 2 -strict -2"),
        EncoderOption("FLAC (resample)", "flac", "flac", "-c:a flac -ar 44100 -ac 2"),
        EncoderOption("WAV (PCM)", "pcm", "wav", "-f wav -acodec pcm_s16le"),
    )

    /**
     * Runs FFmpeg and blocks until it finishes, reporting progress (0..1) from
     * FFmpeg's statistics against the input's duration.
     */
    private fun run(cmd: String, durationMs: Long?, onProgress: ((Float) -> Unit)?): FFmpegSession {
        val done = CountDownLatch(1)
        val session = FFmpegKit.executeAsync(
            cmd,
            FFmpegSessionCompleteCallback { done.countDown() },
            null as LogCallback?,
            StatisticsCallback { stats ->
                if (onProgress != null && durationMs != null && durationMs > 0) {
                    onProgress((stats.time / durationMs).toFloat().coerceIn(0f, 1f))
                }
            }
        )
        done.await()
        return session
    }

    /** AAX metadata (including duration) is readable without decrypting. */
    private fun durationMs(file: File): Long? = try {
        ffprobeJson("-show_format", file.absolutePath)?.optJSONObject("format")
            ?.optString("duration")?.toDoubleOrNull()?.times(1000)?.toLong()
    } catch (e: Exception) {
        null
    }

    fun convert(aaxFile: File, activationBytes: String, onProgress: ((Float) -> Unit)? = null): File? {
        debugLog.section("Convert: ${aaxFile.name}")
        debugLog.log("CONV", "Input: ${aaxFile.absolutePath} (${aaxFile.length() / 1_048_576}MB)")
        debugLog.log("CONV", "Activation bytes: $activationBytes")
        val duration = if (onProgress != null) durationMs(aaxFile) else null

        for (option in encoders) {
            val outFile = File(aaxFile.parent, "${aaxFile.nameWithoutExtension}.${option.ext}")
            val cmd = "-y -activation_bytes $activationBytes -i \"${aaxFile.absolutePath}\" -map 0:a ${option.args} \"${outFile.absolutePath}\""

            debugLog.log("CONV", "Trying ${option.name}...")

            try {
                onProgress?.invoke(0f)
                val session = run(cmd, duration, onProgress)
                val returnCode = session.returnCode

                if (ReturnCode.isSuccess(returnCode) && outFile.exists() && outFile.length() > 1000) {
                    debugLog.log("CONV", "Success with ${option.name}: ${outFile.length() / 1_048_576}MB")
                    return outFile
                } else {
                    val output = session.allLogsAsString ?: "no output"
                    val errorLine = output.lines().lastOrNull { it.contains("Error") || it.contains("Unknown") || it.contains("error") }
                        ?: output.lines().takeLast(3).joinToString(" | ")
                    debugLog.log("CONV", "${option.name} failed: $errorLine")
                    outFile.delete()
                }
            } catch (e: Exception) {
                debugLog.log("CONV", "${option.name} exception: ${e.message}")
                outFile.delete()
            }
        }

        debugLog.log("CONV", "All encoders failed")
        return null
    }

    /** Joins already-converted parts of one book into [outFile] without re-encoding. */
    fun concat(parts: List<File>, outFile: File, durationMs: Long?, onProgress: ((Float) -> Unit)? = null): Boolean {
        debugLog.section("Join ${parts.size} parts: ${outFile.name}")
        val list = File(outFile.parentFile, "concat.txt")
        list.writeText(parts.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" })
        // Chapters come from each part and are stored in the database instead
        val cmd = "-y -f concat -safe 0 -i \"${list.absolutePath}\" -map 0:a -map_chapters -1 -c copy -f mp4 \"${outFile.absolutePath}\""
        return try {
            val session = run(cmd, durationMs, onProgress)
            val ok = ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 1000
            if (ok) {
                debugLog.log("CONV", "Joined: ${outFile.length() / 1_048_576}MB")
            } else {
                debugLog.log("CONV", "Join failed: ${(session.allLogsAsString ?: "").lines().takeLast(5).joinToString(" | ")}")
                outFile.delete()
            }
            ok
        } catch (e: Exception) {
            debugLog.log("CONV", "Join exception: ${e.message}")
            outFile.delete()
            false
        } finally {
            list.delete()
        }
    }

    /**
     * Joins audio files of one book (any format, e.g. one MP3 per chapter) into one AAC
     * .m4b at [bitrate] bits/s, with the tags and chapters in [metadata] (an FFMETADATA file).
     */
    fun joinToM4b(
        parts: List<File>,
        metadata: File,
        outFile: File,
        bitrate: Int,
        durationMs: Long?,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        debugLog.section("Join ${parts.size} files: ${outFile.name}")
        val list = File(outFile.parentFile, "concat.txt")
        list.writeText(parts.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" })
        val cmd = "-y -f concat -safe 0 -i \"${list.absolutePath}\" -i \"${metadata.absolutePath}\" " +
            "-map 0:a -map_metadata 1 -map_chapters 1 -c:a aac -b:a $bitrate -f mp4 \"${outFile.absolutePath}\""
        debugLog.log("CONV", "Encoding to AAC at ${bitrate / 1000}k")
        return try {
            val session = run(cmd, durationMs, onProgress)
            val ok = ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 1000
            if (ok) {
                debugLog.log("CONV", "Joined: ${outFile.length() / 1_048_576}MB")
            } else {
                debugLog.log("CONV", "Join failed: ${(session.allLogsAsString ?: "").lines().takeLast(5).joinToString(" | ")}")
                outFile.delete()
            }
            ok
        } catch (e: Exception) {
            debugLog.log("CONV", "Join exception: ${e.message}")
            outFile.delete()
            false
        } finally {
            list.delete()
        }
    }

    fun convertWithKeyIv(aaxFile: File, key: String, iv: String, onProgress: ((Float) -> Unit)? = null): File? {
        val m4bFile = File(aaxFile.parent, aaxFile.nameWithoutExtension + ".m4b")

        debugLog.section("Convert with key/iv: ${aaxFile.name}")
        debugLog.log("CONV", "Input: ${aaxFile.absolutePath} (${aaxFile.length() / 1_048_576}MB)")
        debugLog.log("CONV", "Output: ${m4bFile.absolutePath}")

        val cmd = "-y -audible_key $key -audible_iv $iv -i \"${aaxFile.absolutePath}\" -map 0:a -c:a copy \"${m4bFile.absolutePath}\""
        debugLog.log("CONV", "Running FFmpeg with audible_key/iv...")

        return try {
            val session = run(cmd, if (onProgress != null) durationMs(aaxFile) else null, onProgress)
            val returnCode = session.returnCode

            if (ReturnCode.isSuccess(returnCode) && m4bFile.exists() && m4bFile.length() > 1000) {
                debugLog.log("CONV", "Success: ${m4bFile.length() / 1_048_576}MB")
                m4bFile
            } else {
                val output = session.allLogsAsString ?: "no output"
                val lastLines = output.lines().takeLast(10).joinToString("\n")
                debugLog.log("CONV", "FAILED: $lastLines")
                m4bFile.delete()
                null
            }
        } catch (e: Exception) {
            debugLog.log("CONV", "EXCEPTION: ${e::class.simpleName}: ${e.message}")
            m4bFile.delete()
            null
        }
    }
}
