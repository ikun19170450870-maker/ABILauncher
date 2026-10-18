package com.abi.launcher.launch

/** 版本 JSON 里的一条规则：os / features 条件，action 为 allow 或 disallow */
data class RuleSpec(
    val action: String,
    val os: OsRule?,
    val features: Map<String, Boolean>,
)

data class OsRule(
    val name: String?,
    val version: String?,
    val arch: String?,
)

/** 判断规则时的运行环境 */
data class RuleContext(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val features: Map<String, Boolean> = emptyMap(),
) {
    companion object {
        fun current(features: Map<String, Boolean> = emptyMap()): RuleContext = RuleContext(
            osName = currentOsName(),
            osVersion = System.getProperty("os.version").orEmpty(),
            osArch = currentOsArch(),
            features = features,
        )

        fun currentOsName(): String {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                os.contains("win") -> "windows"
                os.contains("mac") || os.contains("darwin") -> "osx"
                else -> "linux"
            }
        }

        /** Java 的 os.arch 与 Minecraft 的写法不一样，这里做一次映射 */
        fun currentOsArch(): String = when (val arch = System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "arm64"
            "x86", "i386", "i486", "i586", "i686" -> "x86"
            else -> arch
        }
    }
}

/**
 * Minecraft 的规则语义：
 *  - 没有 rules 时默认允许；
 *  - 有 rules 时默认拒绝，然后按顺序判断，**最后一条匹配的规则**决定结果。
 */
object RuleEvaluator {

    fun isAllowed(rules: List<RuleSpec>?, context: RuleContext): Boolean {
        if (rules.isNullOrEmpty()) return true
        var allowed = false
        for (rule in rules) {
            if (matches(rule, context)) allowed = rule.action.equals("allow", ignoreCase = true)
        }
        return allowed
    }

    private fun matches(rule: RuleSpec, context: RuleContext): Boolean {
        rule.os?.let { os ->
            os.name?.let { if (!it.equals(context.osName, ignoreCase = true)) return false }
            os.arch?.let { if (!it.equals(context.osArch, ignoreCase = true)) return false }
            os.version?.let {
                val matched = runCatching { Regex(it).containsMatchIn(context.osVersion) }.getOrDefault(false)
                if (!matched) return false
            }
        }
        rule.features.forEach { (name, expected) ->
            // 未声明的特性一律按 false 处理
            if ((context.features[name] ?: false) != expected) return false
        }
        return true
    }
}
