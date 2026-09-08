package com.karyo.monitors.vo

/**
 * FIRING → (ACK) → RESOLVED. ACK suppresses re-notify but keeps the alert open
 * (re-notify/escalation itself is not implemented; knobs removed 2026-08-15, re-add with a real design).
 */
enum class AlertStatus { FIRING, ACK, RESOLVED }
