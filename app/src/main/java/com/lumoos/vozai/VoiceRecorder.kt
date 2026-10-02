package com.lumoos.vozai

import android.content.Context
import android.media.MediaRecorder
import java.io.File

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    fun start(): File {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
        r.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = r
        currentFile = file
        return file
    }

    fun stop(): File? = try {
        recorder?.stop()
        currentFile
    } finally {
        recorder?.release()
        recorder = null
    }

    fun cancel() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        currentFile?.delete()
        currentFile = null
    }
}
