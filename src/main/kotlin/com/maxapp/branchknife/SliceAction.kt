package com.maxapp.branchknife

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 将**当前分支**相对 **master / main** 的已提交差异（`base...HEAD`）按目录规则拆成多个 `split/<服务>-<时间戳>` 分支。
 * 不依赖未提交的 Local Changes。
 */
class SliceAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val hasGit = GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
        if (!hasGit) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val ready = !DumbService.isDumb(project)
        e.presentation.isVisible = true
        e.presentation.isEnabled = ready
        e.presentation.description =
            if (!ready) {
                "项目索引更新中，请稍候再试。"
            } else {
                "将当前分支相对 master/main 上已提交的差异，按服务目录拆成多个从基准分支派生的 split 分支。"
            }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = primaryRepository(project)
        if (repo == null) {
            Messages.showErrorDialog(project, "未找到 Git 仓库。", "Branch-Knife")
            return
        }
        val sourceBranch = repo.currentBranchName
        if (sourceBranch == null) {
            Messages.showErrorDialog(project, "当前为 detached HEAD，请先切到要拆分的功能分支。", "Branch-Knife")
            return
        }
        val root = repo.root
        val (baseBranch, paths) = try {
            diffNameOnlyTripleDot(project, root)
        } catch (ex: IOException) {
            Messages.showErrorDialog(project, ex.message ?: ex.toString(), "Branch-Knife")
            return
        }
        if (sourceBranch == baseBranch) {
            Messages.showInfoMessage(
                project,
                "当前就在基准分支「$baseBranch」上。请先切到你的功能分支（上面已有要拆分的提交），再执行本操作。",
                "Branch-Knife",
            )
            return
        }
        if (paths.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "当前分支相对「$baseBranch」没有可拆分的文件差异（${baseBranch}...HEAD 为空）。\n" +
                    "请确认已在当前分支上提交过改动。",
                "Branch-Knife",
            )
            return
        }
        if (hasDirtyWorkingTree(project, root)) {
            val ok = Messages.showOkCancelDialog(
                project,
                "工作区或暂存区尚有未提交修改，拆分过程会多次 checkout，可能产生冲突或丢失风险。\n建议先 commit / stash 再操作。是否仍要继续？",
                "Branch-Knife",
                Messages.getWarningIcon(),
            )
            if (ok != Messages.OK) return
        }
        val grouped = SlicerService.groupPaths(paths).filterValues { it.isNotEmpty() }
        if (grouped.isEmpty()) {
            Messages.showInfoMessage(project, "没有可分组的路径。", "Branch-Knife")
            return
        }
        val dialog = SliceGroupsDialog(project, grouped, baseBranch, sourceBranch)
        if (!dialog.showAndGet()) return
        val selected = dialog.selectedEntries()
        if (selected.isEmpty()) {
            Messages.showInfoMessage(project, "未选择任何分组。", "Branch-Knife")
            return
        }
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Branch-Knife：按服务拆分分支", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.isIndeterminate = false
                        runSliceFromBranch(
                            project,
                            repo,
                            baseBranch,
                            sourceBranch,
                            selected,
                            indicator,
                        )
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(
                                project,
                                "已完成所选分组。已切回分支「$sourceBranch」。",
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

    /**
     * `git diff --name-only <base>...HEAD`，依次尝试 master、main。
     */
    private fun diffNameOnlyTripleDot(project: Project, root: VirtualFile): Pair<String, List<String>> {
        var lastErr = ""
        for (base in listOf("master", "main")) {
            val handler = GitLineHandler(project, root, GitCommand.DIFF)
            handler.addParameters("--name-only", "$base...HEAD")
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                val paths =
                    result.output
                        .map { it.trim().replace('\\', '/') }
                        .filter { it.isNotEmpty() }
                        .distinct()
                return base to paths
            }
            lastErr = result.errorOutputAsJoinedString
        }
        throw IOException(
            "无法用 master...HEAD 或 main...HEAD 读取差异。请确认仓库存在本地 master 或 main。\n$lastErr",
        )
    }

    private fun hasDirtyWorkingTree(project: Project, root: VirtualFile): Boolean {
        val handler = GitLineHandler(project, root, GitCommand.STATUS)
        handler.addParameters("--porcelain")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return true
        return result.output.any { it.isNotBlank() }
    }

    private fun runSliceFromBranch(
        project: Project,
        repo: GitRepository,
        baseBranch: String,
        sourceBranch: String,
        selected: List<Pair<String, List<String>>>,
        indicator: ProgressIndicator,
    ) {
        val root = repo.root
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        var sliceError: Throwable? = null
        try {
            val total = selected.size
            selected.forEachIndexed { index, (service, relPaths) ->
                if (relPaths.isEmpty()) return@forEachIndexed
                indicator.fraction = (index + 1).toDouble() / total
                indicator.text = "处理分组 $service (${index + 1}/$total)…"
                runGitOrThrow(project, root, GitCommand.CHECKOUT, baseBranch)
                val newBranch = "split/${sanitizeBranchSegment(service)}-$ts"
                runGitOrThrow(project, root, GitCommand.CHECKOUT, "-b", newBranch, baseBranch)
                val co = GitLineHandler(project, root, GitCommand.CHECKOUT)
                co.addParameters(sourceBranch, "--")
                relPaths.forEach { co.addParameters(it) }
                val coResult = Git.getInstance().runCommand(co)
                if (!coResult.success()) {
                    throw IOException(coResult.errorOutputAsJoinedString)
                }
                if (stagedDiffEmpty(project, root)) {
                    throw IOException("分组「$service」在检出后没有可提交的暂存变更，请检查路径是否与分支差异一致。")
                }
                runGitOrThrow(
                    project,
                    root,
                    GitCommand.COMMIT,
                    "-m",
                    "Branch-Knife: $service (from $sourceBranch)",
                )
            }
        } catch (e: Throwable) {
            sliceError = e
        } finally {
            indicator.text = "切回源分支 $sourceBranch…"
            try {
                runGitOrThrow(project, root, GitCommand.CHECKOUT, sourceBranch)
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

    private fun stagedDiffEmpty(project: Project, root: VirtualFile): Boolean {
        val handler = GitLineHandler(project, root, GitCommand.DIFF)
        handler.addParameters("--cached", "--name-only")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return true
        return result.output.none { it.isNotBlank() }
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

    private fun primaryRepository(project: Project): GitRepository? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        if (repos.isEmpty()) return null
        if (repos.size == 1) return repos.single()
        val base = project.basePath ?: return repos.first()
        return repos.find { base.startsWith(it.root.path) } ?: repos.first()
    }

    private class SliceGroupsDialog(
        project: Project,
        private val groups: Map<String, List<String>>,
        baseBranch: String,
        sourceBranch: String,
    ) : DialogWrapper(project) {
        private val checks = LinkedHashMap<String, JBCheckBox>()

        init {
            title = "按服务拆分（基准 $baseBranch ← 源分支 $sourceBranch）"
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
            scroll.preferredSize = Dimension(JBUI.scale(420), JBUI.scale(240))
            val panel = JPanel(BorderLayout())
            panel.add(scroll, BorderLayout.CENTER)
            return panel
        }

        fun selectedEntries(): List<Pair<String, List<String>>> =
            groups.entries
                .filter { checks[it.key]?.isSelected == true }
                .map { it.key to it.value }
    }
}
