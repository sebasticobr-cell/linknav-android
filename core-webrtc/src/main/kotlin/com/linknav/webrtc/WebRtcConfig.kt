package com.linknav.webrtc

data class IceServerConfig(val urls:List<String>,val username:String?=null,val credential:String?=null)
data class CallConfig(val iceServers:List<IceServerConfig>,val audioEnabled:Boolean=true,val videoEnabled:Boolean=true)

enum class CallState { IDLE, RINGING, CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED }
