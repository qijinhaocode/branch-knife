package com.maxapp.branchknife

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object SlicerService {

    /**
     * 仓库根目录下的 `branch-knife.paths`：每行 `路径片段=服务显示名`（先匹配先生效，建议把更长的路径写前面）。
     * 若文件不存在或没有有效行，则使用内置的 sample.* 示例规则。
     */
    data class PathRules(
        val fragments: List<Pair<String, String>>,
        val useBuiltInFallback: Boolean,
    )

    fun loadPathRules(repoRootPath: String): PathRules {
        val f = Path.of(repoRootPath, "branch-knife.paths")
        if (!Files.isRegularFile(f)) {
            return PathRules(emptyList(), useBuiltInFallback = true)
        }
        val lines = Files.readAllLines(f, StandardCharsets.UTF_8)
        val rules =
            lines.mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
                val idx = t.indexOf('=')
                if (idx <= 0 || idx >= t.length - 1) return@mapNotNull null
                val frag = t.substring(0, idx).trim().replace('\\', '/')
                val name = t.substring(idx + 1).trim()
                if (frag.isEmpty() || name.isEmpty()) return@mapNotNull null
                frag to name
            }
        return PathRules(rules, useBuiltInFallback = rules.isEmpty())
    }

    /**
     * 仓库根目录可选文件 `branch-knife.base`：单独一行 `main` 或 `master`（可带注释行 `#`）。
     * 用于仓库里 **同时存在 main 与 master** 时，明确以哪一个作为 diff / checkout 的基准分支；
     * 未配置时插件仍按 **先 master、再 main** 依次尝试。
     */
    fun loadBaseBranchPreference(repoRootPath: String): String? {
        val f = Path.of(repoRootPath, "branch-knife.base")
        if (!Files.isRegularFile(f)) return null
        val line =
            Files.readAllLines(f, StandardCharsets.UTF_8)
                .firstOrNull { t ->
                    val s = t.trim()
                    s.isNotEmpty() && !s.startsWith("#")
                }
                ?.trim()
                ?: return null
        return when (line.lowercase()) {
            "master", "main" -> line.lowercase()
            else -> null
        }
    }

    fun serviceName(path: String, rules: PathRules): String {
        val p = path.replace('\\', '/').trim().removePrefix("./")
        for ((frag, name) in rules.fragments) {
            if (p.contains(frag)) return name
        }
        if (rules.useBuiltInFallback) {
            return builtInSampleServiceName(p)
        }
        return "Others"
    }

    private fun builtInSampleServiceName(p: String): String =
        when {
            p.contains("src/sample.svc.alpha") -> "Svc-Alpha"
            p.contains("src/sample.svc.beta") -> "Svc-Beta"
            p.contains("src/sample.lib.shared") -> "Lib-Shared"
            else -> "Others"
        }

    /** 按仓库相对路径（与 `git diff --name-only` 输出一致）分组 */
    fun groupPaths(paths: Iterable<String>, rules: PathRules): Map<String, List<String>> {
        val normalized =
            paths
                .map { it.replace('\\', '/').trim().removePrefix("./") }
                .filter { it.isNotEmpty() }
                .distinct()
        val raw = normalized.groupBy { serviceName(it, rules) }
        // 用户已有 .paths 配置时，漏网文件统一进单一 Others 卡，由用户手动决定归属；
        // 只有纯内置 fallback 时才按目录前缀二次拆分。
        return if (rules.useBuiltInFallback) splitOthersByDirectoryPrefix(raw) else raw
    }

    /**
     * 未命中 `branch-knife.paths`（且内置 sample 规则也不匹配）时，所有文件会先进同一个 **Others** 桶。
     * 若不做二次拆分，对话框里只会有一张 PR 卡片，`git checkout feature -- <全部文件>` 会把所有服务的改动都进一个分支。
     * 这里在 **Others** 内再按路径前缀（优先 `src/<下一级目录>`）拆成多组，与常见 monorepo 布局一致。
     */
    private fun splitOthersByDirectoryPrefix(grouped: Map<String, List<String>>): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for ((service, pathList) in grouped) {
            if (service != "Others" || pathList.size <= 1) {
                result.getOrPut(service) { mutableListOf() }.addAll(pathList)
                continue
            }
            val byPrefix = pathList.groupBy { othersModulePrefix(it) }
            if (byPrefix.size <= 1) {
                result.getOrPut(service) { mutableListOf() }.addAll(pathList)
            } else {
                for ((prefix, files) in byPrefix) {
                    val label = "$service · $prefix"
                    result.getOrPut(label) { mutableListOf() }.addAll(files)
                }
            }
        }
        return result.mapValues { (_, v) -> v.toList() }
    }

    /** Others 二次分组用的「模块前缀」：`src/foo/bar` → `src/foo`，否则取路径前两段。 */
    private fun othersModulePrefix(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "/"
        if (parts.size >= 2 && parts[0].equals("src", ignoreCase = true)) {
            return "${parts[0]}/${parts[1]}"
        }
        return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else parts[0]
    }
}
