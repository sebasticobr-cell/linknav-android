package com.linknav.security

import java.security.SecureRandom
import java.util.Base64

object SessionSecurity {
    private val random=SecureRandom()
    fun randomToken(bytes:Int=24):String { val b=ByteArray(bytes); random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b) }
    fun displayCode():String = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("")
}
