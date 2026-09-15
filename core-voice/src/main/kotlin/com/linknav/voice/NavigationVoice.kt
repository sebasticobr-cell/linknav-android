package com.linknav.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class NavigationVoice(context:Context):TextToSpeech.OnInitListener {
    private val tts=TextToSpeech(context.applicationContext,this)
    override fun onInit(status:Int){ if(status==TextToSpeech.SUCCESS) tts.language=Locale("pt","BR") }
    fun speak(text:String){ tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"nav-${System.nanoTime()}") }
    fun close(){ tts.stop(); tts.shutdown() }
}
