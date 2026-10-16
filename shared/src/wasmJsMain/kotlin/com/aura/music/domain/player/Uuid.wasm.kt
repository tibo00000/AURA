package com.aura.music.domain.player

private fun randomUuid(): String = js("crypto.randomUUID()")

actual fun generateUuid(): String = randomUuid()
