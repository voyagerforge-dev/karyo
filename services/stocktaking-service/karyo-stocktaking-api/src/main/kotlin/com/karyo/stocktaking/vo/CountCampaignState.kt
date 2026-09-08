package com.karyo.stocktaking.vo

enum class CountCampaignState(val code: Int) {
    OPEN(100), CLOSED(700);
    fun canAdvanceTo(target: CountCampaignState): Boolean = target != this && target.code > code
    companion object {
        fun fromCode(code: Int): CountCampaignState =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown CountCampaignState code: $code")
    }
}
