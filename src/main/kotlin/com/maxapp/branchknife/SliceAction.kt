package com.maxapp.branchknife

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class SliceAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val clm = ChangeListManager.getInstance(project)
        val hasChanges = clm.allChanges.isNotEmpty()
        val hasGit = GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
        e.presentation.isEnabledAndVisible = hasChanges && hasGit
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val changes = ChangeListManager.getInstance(project).allChanges.toList()
        if (changes.isEmpty()) {
            Messages.showInfoMessage(project, "当前没有未提交的改动。", "Branch-Knife")
            return
        }
        val repo = resolveRepository(project, changes)
        if (repo == null) {
            Messages.showErrorDialog(
                project,
                "未找到唯一的 Git 仓库根目录，或改动分散在多个仓库中。",
                "Branch-Knife",
            )
            return
        }
        val grouped = SlicerService.groupChanges(changes).filterValues { it.isNotEmpty() }
        if (grouped.isEmpty()) {
            Messages.showInfoMessage(project, "没有可分组的有效文件路径。", "Branch-Knife")
            return
        }
        val dialog = SliceGroupsDialog(project, grouped)
        if (!dialog.showAndGet()) return
        val selected = dialog.selectedEntries()
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project, "未选择任何分组。", "Branch-Knife")
            return
        }
        val originalBranch = repo.currentBranchName
            ?: run {
                Messages.showErrorDialog(project, "当前不在任何分支上（detached HEAD）。", "Branch-Knife")
                return
            }
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Branch-Knife：按服务拆分分支", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.isIndeterminate = false
                        runSlice(project, repo, originalBranch, selected, indicator)
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(
                                project,
                                "已完成所选分组的拆分提交。",
                                "Branch-Knife",
                            )
                        }
                    } catch (ex: Throwable) {
                        val msg = ex.message ?: ex.toString()
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, msg, "Branch-Knife")
                        }
                    }
                }
            },
        )
    }

    private fun runSlice(
        project: Project,
        repo: GitRepository,
        originalBranch: String,
        selected: List<Pair<String, List<Change>>>,
        indicator: ProgressIndicator,
    ) {
        val root = repo.root
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        indicator.text = "Stash 本地改动…"
        runGitOrThrow(project, root, GitCommand.STASH, "push", "-u", "-m", "Branch-Knife slice")
        var sliceError: Throwable? = null
        try {
            var step = 0
            val total = selected.size
            for ((service, groupChanges) in selected) {
                step++
                indicator.fraction = step.toDouble() / total
                val relPaths = relativeRepoPaths(repo, groupChanges)
                if (relPaths.isEmpty()) continue
                indicator.text = "处理分组 $service ($step/$total)…"
                runGitOrThrow(project, root, GitCommand.CHECKOUT, "master")
                val branch = "split/${sanitizeBranchSegment(service)}-$ts"
                runGitOrThrow(project, root, GitCommand.CHECKOUT, "-b", branch, "master")
                val checkoutHandler = GitLineHandler(project, root, GitCommand.CHECKOUT)
                checkoutHandler.addParameters("stash@{0}", "--")
                relPaths.forEach { checkoutHandler.addParameters(it) }
                val co = Git.getInstance().runCommand(checkoutHandler)
                if (!co.success()) {
                    throw IOException(co.errorOutputAsJoinedString)
                }
                val addHandler = GitLineHandler(project, root, GitCommand.ADD)
                addHandler.addParameters("--")
                relPaths.forEach { addHandler.addParameters(it) }
                val ad = Git.getInstance().runCommand(addHandler)
                if (!ad.success()) {
                    throw IOException(ad.errorOutputAsJoinedString)
                }
                runGitOrThrow(
                    project,
                    root,
                    GitCommand.COMMIT,
                    "-m",
                    "Branch-Knife: $service",
                )
            }
        } catch (e: Throwable) {
            sliceError = e
        } finally {
            indicator.text = "切回原分支并恢复工作区…"
            try {
                runGitOrThrow(project, root, GitCommand.CHECKOUT, originalBranch)
                val popHandler = GitLineHandler(project, root, GitCommand.STASH)
                popHandler.addParameters("pop")
                val pop = Git.getInstance().runCommand(popHandler)
                if (!pop.success()) {
                    throw IOException(
                        "stash pop 失败，请手动处理 stash。\n${pop.errorOutputAsJoinedString}",
                    )
                }
            } catch (restoreEx: Throwable) {
                if (sliceError != null) {
                    sliceError.addSuppressed(restoreEx)
                } else {
                    sliceError = restoreEx
                }
            }
        }
        sliceError?.let { throw it }
    }

    private fun runGitOrThrow(project: Project, root: VirtualFile, cmd: GitCommand, vararg args: String) {
        val handler = GitLineHandler(project, root, cmd)
        handler.addParameters(*args)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            throw IOException(result.errorOutputAsJoinedString)
        }
    }

    private fun sanitizeBranchSegment(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]"), "-")

    private fun relativeRepoPaths(repo: GitRepository, changes: List<Change>): List<String> {
        val rootPath = Path.of(repo.root.path).normalize()
        return changes.mapNotNull { ch ->
            val abs = SlicerService.pathOf(ch).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val p = Path.of(abs).normalize()
            val rel = try {
                rootPath.relativize(p)
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            if (rel.isEmpty || rel.toString().contains("..")) return@mapNotNull null
            rel.joinToString("/").replace('\\', '/')
        }.distinct()
    }

    private fun resolveRepository(project: Project, changes: Collection<Change>): GitRepository? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        if (repos.isEmpty()) return null
        if (repos.size == 1) return repos.single()
        val hits = changes.mapNotNull { ch ->
            val path = SlicerService.pathOf(ch).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            repos.find { path.startsWith(it.root.path) }
        }.distinct()
        return hits.singleOrNull()
    }

    private class SliceGroupsDialog(
        project: Project,
        private val groups: Map<String, List<Change>>,
    ) : DialogWrapper(project) {
        private val checks = LinkedHashMap<String, JBCheckBox>()

        init {
            title = "选择要拆分的改动分组"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val inner = JPanel()
            inner.layout = BoxLayout(inner, BoxLayout.Y_AXIS)
            for (name in groups.keys.sorted()) {
                val count = groups[name]?.size ?: 0
                val cb = JBCheckBox("$name [$count files]", true)
                checks[name] = cb
                inner.add(cb)
                inner.add(Box.createVerticalStrut(JBUI.scale(4)))
            }
            val scroll = JBScrollPane(inner)
            scroll.preferredSize = Dimension(JBUI.scale(360), JBUI.scale(220))
            val panel = JPanel(BorderLayout())
            panel.add(scroll, BorderLayout.CENTER)
            return panel
        }

        fun selectedEntries(): List<Pair<String, List<Change>>> =
            groups.entries
                .filter { checks[it.key]?.isSelected == true }
                .map { it.key to it.value }
    }
}
