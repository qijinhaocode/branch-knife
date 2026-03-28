package com.maxapp.branchknife

import com.intellij.openapi.vcs.changes.Change

object SlicerService {

    fun getServiceName(path: String): String {
        val p = path.replace('\\', '/')
        return when {
            p.contains("src/sample.svc.alpha") -> "Svc-Alpha"
            p.contains("src/sample.svc.beta") -> "Svc-Beta"
            p.contains("src/sample.lib.shared") -> "Lib-Shared"
            else -> "Others"
        }
    }

    /** 按仓库相对路径（与 `git diff --name-only` 输出一致）分组 */
    fun groupPaths(paths: Iterable<String>): Map<String, List<String>> =
        paths
            .map { it.replace('\\', '/').trim().removePrefix("./") }
            .filter { it.isNotEmpty() }
            .distinct()
            .groupBy { getServiceName(it) }

    fun groupChanges(changes: List<Change>): Map<String, List<Change>> =
        changes.groupBy { getServiceName(pathOf(it)) }

    fun pathOf(change: Change): String {
        change.afterRevision?.file?.path?.let { return it }
        change.beforeRevision?.file?.path?.let { return it }
        return ""
    }
}
