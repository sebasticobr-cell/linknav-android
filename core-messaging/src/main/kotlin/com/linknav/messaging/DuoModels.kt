package com.linknav.messaging

import com.linknav.location.GeoPoint

data class DuoMember(val id:String,val name:String,val location:GeoPoint?=null,val status:String="SEM SINAL",val lastUpdateMs:Long=0)
data class DuoSession(val id:String,val code:String,val self:DuoMember,val partner:DuoMember?=null,val sharedDestination:GeoPoint?=null,val sharingLocation:Boolean=false)
data class ChatMessage(val id:String,val senderId:String,val text:String,val sentAtMs:Long=System.currentTimeMillis(),val location:GeoPoint?=null)
