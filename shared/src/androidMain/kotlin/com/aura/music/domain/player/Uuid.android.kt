package com.aura.music.domain.player

import java.util.UUID

actual fun generateUuid(): String = UUID.randomUUID().toString()
