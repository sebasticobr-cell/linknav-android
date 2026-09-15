package com.linknav.navigation

import android.app.*
import android.content.Intent
import android.os.IBinder

class NavigationForegroundService: Service() {
    override fun onCreate(){ super.onCreate(); val ch=NotificationChannel("nav","Navegação",NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager::class.java).createNotificationChannel(ch) }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int { val n=Notification.Builder(this,"nav").setContentTitle("LINKNAV").setContentText("Navegação ativa").setSmallIcon(android.R.drawable.ic_dialog_map).build(); startForeground(3001,n); return START_STICKY }
    override fun onBind(intent:Intent?):IBinder?=null
}
