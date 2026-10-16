package com.dividetask.audiobookreader.audible.download

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.dividetask.audiobookreader.debug.AppDebugLog
import java.io.File
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

    private val encoders = listOf(
        EncoderOption("AAC (strict -2)", "aac", "m4a", "-c:a aac -b:a 128k -strict -2"),
        EncoderOption("WAV (PCM)", "pcm", "wav", "-f wav -acodec pcm_s16le"),
        EncoderOption("AAC (resample)", "aac2", "m4a", "-c:a aac -b:a 128k -ar 44100 -ac 2 -strict -2"),
        EncoderOption("FLAC (resample)", "flac", "flac", "-c:a flac -ar 44100 -ac 2"),
        EncoderOption("Stream copy (M4A)", "copy_m4a", "m4a", "-c:a copy"),
        EncoderOption("Stream copy (M4B)", "copy_m4b", "m4b", "-c:a copy"),
    )

    fun convert(aaxFile: File, activationBytes: String): File? {
        debugLog.section("Convert: ${aaxFile.name}")
        debugLog.log("CONV", "Input: ${aaxFile.absolutePath} (${aaxFile.length() / 1_048_576}MB)")
        debugLog.log("CONV", "Activation bytes: $activationBytes")

        for (option in encoders) {
            val outFile = File(aaxFile.parent, "${aaxFile.nameWithoutExtension}.${option.ext}")
            val cmd = "-y -activation_bytes $activationBytes -i \"${aaxFile.absolutePath}\" -map 0:a ${option.args} \"${outFile.absolutePath}\""

            debugLog.log("CONV", "Trying ${option.name}...")

            try {
                val session = FFmpegKit.execute(cmd)
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

    fun convertWithKeyIv(aaxFile: File, key: String, iv: String): File? {
        val m4bFile = File(aaxFile.parent, aaxFile.nameWithoutExtension + ".m4b")

        debugLog.section("Convert with key/iv: ${aaxFile.name}")
        debugLog.log("CONV", "Input: ${aaxFile.absolutePath} (${aaxFile.length() / 1_048_576}MB)")
        debugLog.log("CONV", "Output: ${m4bFile.absolutePath}")

        val cmd = "-y -audible_key $key -audible_iv $iv -i \"${aaxFile.absolutePath}\" -map 0:a -c:a copy \"${m4bFile.absolutePath}\""
        debugLog.log("CONV", "Running FFmpeg with audible_key/iv...")

        return try {
            val session = FFmpegKit.execute(cmd)
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
