package com.maxapp.branchknife

import com.intellij.openapi.vcs.changes.Change

object SlicerService {

    fun getServiceName(path: String): String {
        val p = path.replace('\\', '/')
        return when {
            p.contains("src/TOCA.Bff.Pos") -> "Bff-Pos"
            p.contains("src/TOCA.Ops.Schools") -> "Ops-Schools"
            p.contains("src/TOCA.Common") -> "Common"
            else -> "Others"
        }
    }

    fun groupChanges(changes: List<Change>): Map<String, List<Change>> =
        changes.groupBy { getServiceName(pathOf(it)) }

    fun pathOf(change: Change): String {
        change.afterRevision?.file?.path?.let { return it }
        change.beforeRevision?.file?.path?.let { return it }
        return ""
    }
}
