package com.linknav.network

data class RealtimeEvent(val type:String,val sessionId:String,val senderId:String,val payload:String,val timestampMs:Long=System.currentTimeMillis())
object EventTypes { const val PRESENCE="presence"; const val LOCATION="location"; const val DESTINATION="destination"; const val MESSAGE="message"; const val CALL_OFFER="call_offer"; const val CALL_ANSWER="call_answer"; const val CALL_ICE="call_ice"; const val CALL_END="call_end"; const val WARNING="warning" }
