package com.linknav.vision

data class VisionObservation(val label:String,val confidence:Float,val normalizedX:Float?=null,val normalizedY:Float?=null)
data class VisionAnswer(val text:String,val confidence:Float,val observations:List<VisionObservation> = emptyList())
interface VisionProvider { suspend fun analyzeJpeg(bytes:ByteArray,prompt:String):VisionAnswer }
class NoGuessVisionProvider:VisionProvider { override suspend fun analyzeJpeg(bytes:ByteArray,prompt:String)=VisionAnswer("Não consigo confirmar visualmente com confiança suficiente; use a rota e a orientação do mapa.",0f) }
