package com.linknav.voice

import android.media.MediaRecorder
import android.os.Build
import java.io.File

class VoiceMessageRecorder {
    private var recorder:MediaRecorder?=null
    fun start(file:File) {
        stop()
        val r=MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            if(Build.VERSION.SDK_INT>=29){
                setOutputFormat(MediaRecorder.OutputFormat.OGG); setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioEncodingBitRate(64000); setAudioSamplingRate(48000)
            } else {
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000); setAudioSamplingRate(48000)
            }
            setOutputFile(file.absolutePath); prepare(); start()
        }
        recorder=r
    }
    fun stop(){ runCatching{recorder?.stop()}; recorder?.release(); recorder=null }
}
