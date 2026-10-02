package com.lumoos.vozai

import android.media.MediaPlayer
import java.io.File

class AudioPlayer {
    private var player: MediaPlayer? = null

    fun play(file: File, onDone: (() -> Unit)? = null) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                onDone?.invoke()
                stop()
            }
            prepare()
            start()
        }
    }

    fun stop() {
        player?.release()
        player = null
    }
}
