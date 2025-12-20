package common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

inline fun <reified T> T.toJsonString(): String = Json.encodeToString(this)

fun String.parseJsonObject(): JsonObject = Json.parseToJsonElement(this).jsonObject
