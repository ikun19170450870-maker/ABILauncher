package com.abi.launcher.launch

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.io.path.readText

// ---------- Gson 取值小工具：容忍字段缺失与类型差异 ----------

internal fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.arr(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.takeIf { it.isNumber }?.asInt

internal fun JsonObject.long(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.takeIf { it.isNumber }?.asLong

/** 只接受字符串 */
internal fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.takeIf { it.isString }?.asString

/** 字符串或数字都当字符串读（assetIndex.id 在不同版本里类型不一致） */
internal fun JsonObject.scalar(name: String): String? {
    val primitive = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    return when {
        primitive.isString -> primitive.asString
        primitive.isNumber -> primitive.asNumber.toString()
        else -> null
    }
}

internal fun readJsonObject(path: Path): JsonObject? = runCatching {
    JsonParser.parseString(path.readText()).asJsonObject
}.getOrNull()
