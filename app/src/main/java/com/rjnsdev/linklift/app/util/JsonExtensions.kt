package com.rjnsdev.linklift.app

import org.json.JSONObject

internal fun JSONObject.optIntOrNull(name: String): Int? {
    val value = opt(name) ?: return null
    return when (value) {
        is Number -> value.toInt().takeIf { it != 0 || value.toDouble() != 0.0 }
        is String -> value.toIntOrNull()
        else -> null
    }
}

internal fun JSONObject.optLongOrNull(name: String): Long? {
    val value = opt(name) ?: return null
    return when (value) {
        is Number -> value.toLong().takeIf { it > 0L }
        is String -> value.toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }
}
