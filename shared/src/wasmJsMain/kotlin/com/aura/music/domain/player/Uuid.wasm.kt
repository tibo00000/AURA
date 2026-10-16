package com.aura.music.domain.player

private fun randomUuid(): String = js("""
    (function() {
        if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
            return crypto.randomUUID();
        }
        if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
            var buf = new Uint8Array(16);
            crypto.getRandomValues(buf);
            buf[6] = (buf[6] & 0x0f) | 0x40;
            buf[8] = (buf[8] & 0x3f) | 0x80;
            var hex = [];
            for (var i = 0; i < 16; i++) {
                var v = buf[i].toString(16);
                hex.push(v.length === 1 ? '0' + v : v);
            }
            return hex.slice(0, 4).join('') + '-' +
                   hex.slice(4, 6).join('') + '-' +
                   hex.slice(6, 8).join('') + '-' +
                   hex.slice(8, 10).join('') + '-' +
                   hex.slice(10, 16).join('');
        }
        throw new Error("Crypto API not available for UUID generation.");
    })()
""")

actual fun generateUuid(): String = randomUuid()
