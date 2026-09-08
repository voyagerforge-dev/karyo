package com.karyo.crossdock.vo

/** Forward-only, myWMS-style spaced codes. */
enum class CrossDockState(val code: Int) {
    CREATED(100), MATCHED(200), STAGED(400), COMPLETED(700), EXPIRED(800), CANCELLED(900);
    companion object { fun fromCode(code: Int): CrossDockState = entries.first { it.code == code } }
}
