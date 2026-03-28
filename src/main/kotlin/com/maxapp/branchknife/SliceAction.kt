package com.maxapp.branchknife

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.IOException

/**
 * 按「目标功能分支」相对 **master / main** 的差异路径，在本地建多个**你命名的功能分支**，便于按模块提 PR。
 *
 * **路径从哪来**：`git diff --name-only <基准>...<目标分支>` —— 只用来**枚举**目标分支上相对基准有哪些文件不同，**不是**做 merge。
 *
 * **每个 PR 分支怎么建**（与 Smart PR Splitter 对话框中配置一致）：
 * 1. `git checkout <基准>`（如 master）
 * 2. `git checkout -b <你填的分支名> <基准>`
 * 3. `git checkout <目标分支> -- <路径…>` —— 把目标分支里这些路径的内容拿到当前分支
 * 4. `git commit -m <你填的说明>`
 *
 * 全部完成后 `git checkout` 回到**你点菜单前**所在的分支（不必事先切到目标分支上）。
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
                "从 master/main 派生 split/* 分支，再检出目标功能分支上按服务划分的路径并提交；用 diff 仅列出差异文件路径。"
            }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = primaryRepository(project)
        if (repo == null) {
            Messages.showErrorDialog(project, "未找到 Git 仓库。", "Branch-Knife")
            return
        }
        val root = repo.root
        val branchBeforeRun =
            repo.currentBranchName
                ?: run {
                    Messages.showErrorDialog(project, "当前为 detached HEAD，无法记录要切回的分支。", "Branch-Knife")
                    return
                }
        val targetBranch =
            resolveTargetFeatureBranch(project, repo, root)
                ?: return
        val preflight =
            try {
                runGitOffEdt(project, "Branch-Knife：读取差异与状态") {
                    val baseAndPaths = diffNameOnlyBaseToTarget(project, root, targetBranch)
                    val dirty = hasDirtyWorkingTree(project, root)
                    PreflightResult(baseAndPaths, dirty)
                }
            } catch (ex: Throwable) {
                Messages.showErrorDialog(project, ex.message ?: ex.toString(), "Branch-Knife")
                return
            }
        val (baseBranch, paths) = preflight.baseAndPaths
        if (targetBranch == baseBranch) {
            Messages.showInfoMessage(
                project,
                "目标分支不能与基准分支「$baseBranch」相同。",
                "Branch-Knife",
            )
            return
        }
        if (paths.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "「$baseBranch...$targetBranch」下没有文件路径差异，无需拆分。\n" +
                    "请确认目标分支上已有相对基准的提交。",
                "Branch-Knife",
            )
            return
        }
        if (preflight.dirty) {
            val ok = Messages.showOkCancelDialog(
                project,
                "工作区或暂存区尚有未提交修改，拆分过程会多次 checkout，可能产生冲突或丢失风险。\n建议先 commit / stash 再操作。是否仍要继续？",
                "Branch-Knife",
                Messages.getWarningIcon(),
            )
            if (ok != Messages.OK) return
        }
        val rules = SlicerService.loadPathRules(root.path)
        val grouped = SlicerService.groupPaths(paths, rules).filterValues { it.isNotEmpty() }
        if (grouped.isEmpty()) {
            Messages.showInfoMessage(project, "没有可分组的路径。", "Branch-Knife")
            return
        }
        val dialog =
            SmartPrSplitterDialog(
                project,
                paths,
                grouped,
                baseBranch,
                targetBranch,
            )
        if (!dialog.showAndGet()) return
        val targets = dialog.getConfirmedTargets()
        if (targets.isNullOrEmpty()) {
            Messages.showInfoMessage(project, "未配置任何 PR 目标。", "Branch-Knife")
            return
        }
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Branch-Knife：按服务拆分分支", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.isIndeterminate = false
                        val created =
                            runSliceFromBranch(
                                project,
                                repo,
                                baseBranch,
                                targetBranch,
                                branchBeforeRun,
                                targets,
                                indicator,
                            )
                        // 后台再跑一遍 branch：不指望 IDE「读终端」，但与手动执行类似，有时利于 Git/FS 状态对齐
                        nudgeGitBranchList(project, repo.root)
                        ApplicationManager.getApplication().invokeLater {
                            refreshGitAndVcsUi(project)
                            val branchList = created.joinToString("\n") { "• $it" }
                            Messages.showInfoMessage(
                                project,
                                """
                                已在本地创建 ${created.size} 个分支（每一步都是：切到「$baseBranch」→ 新建你填写的分支名 → 从「$targetBranch」按路径检出 → 提交）：
                                
                                $branchList
                                
                                已切回你操作前所在分支「$branchBeforeRun」。
                                请在 Git → Branches 中查看新建分支并分别 Push 提 PR。
                                """.trimIndent(),
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

    private fun resolveTargetFeatureBranch(project: Project, repo: GitRepository, root: VirtualFile): String? {
        val current = repo.currentBranchName ?: return null
        if (current !in BASE_BRANCH_NAMES) {
            return current
        }
        val locals =
            try {
                runGitOffEdt(project, "Branch-Knife：读取本地分支") {
                    listLocalBranchShortNames(project, root)
                }
            } catch (ex: Throwable) {
                Messages.showErrorDialog(project, ex.message ?: ex.toString(), "Branch-Knife")
                return null
            }
        val candidates = locals.filter { it !in BASE_BRANCH_NAMES }.sorted()
        if (candidates.isEmpty()) {
            Messages.showErrorDialog(
                project,
                "当前在基准分支「$current」，且没有其它本地分支可选。\n请先创建或拉取要拆分的功能分支。",
                "Branch-Knife",
            )
            return null
        }
        val idx =
            Messages.showChooseDialog(
                project,
                "Branch-Knife",
                "当前在「$current」。请选择**要拆分的功能分支**。\n" +
                    "插件会用 git diff 列出「master 或 main」与该分支之间的**差异文件路径**（不合并），再按目录拆成多个 split/* 分支。",
                Messages.getQuestionIcon(),
                candidates.toTypedArray(),
                candidates.first(),
            )
        if (idx < 0) return null
        return candidates[idx]
    }

    private fun listLocalBranchShortNames(project: Project, root: VirtualFile): List<String> {
        val h = GitLineHandler(project, root, GitCommand.BRANCH)
        h.addParameters("--list", "--format=%(refname:short)")
        var result = Git.getInstance().runCommand(h)
        if (!result.success()) {
            val h2 = GitLineHandler(project, root, GitCommand.BRANCH)
            h2.addParameters("--list")
            result = Git.getInstance().runCommand(h2)
        }
        if (!result.success()) {
            throw IOException(result.errorOutputAsJoinedString)
        }
        return result.output
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * `git diff --name-only <base>...<targetBranch>`，base 依次尝试 master、main。
     * 与当前 HEAD 检出在哪个分支无关，只要引用存在即可。
     */
    private fun diffNameOnlyBaseToTarget(
        project: Project,
        root: VirtualFile,
        targetBranch: String,
    ): Pair<String, List<String>> {
        var lastErr = ""
        for (base in BASE_BRANCH_TRY_ORDER) {
            val handler = GitLineHandler(project, root, GitCommand.DIFF)
            handler.addParameters("--name-only", "$base...$targetBranch")
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
            "无法用 master...$targetBranch 或 main...$targetBranch 列出差异路径，请确认本地存在 master 或 main。\n$lastErr",
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
        targetBranch: String,
        branchToRestore: String,
        targets: List<PrSplitTarget>,
        indicator: ProgressIndicator,
    ): List<String> {
        val root = repo.root
        val created = mutableListOf<String>()
        var sliceError: Throwable? = null
        try {
            val total = targets.size
            targets.forEachIndexed { index, target ->
                val relPaths = target.paths.map { it.replace('\\', '/') }.filter { it.isNotEmpty() }
                if (relPaths.isEmpty()) return@forEachIndexed
                indicator.fraction = (index + 1).toDouble() / total
                val newBranch = sanitizeUserBranchName(target.branchName)
                val label = newBranch.substringAfterLast('/').ifEmpty { newBranch }
                indicator.text = "[$label] checkout $baseBranch → 新建 $newBranch"
                runGitOrThrow(project, root, GitCommand.CHECKOUT, baseBranch)
                runGitOrThrow(project, root, GitCommand.CHECKOUT, "-b", newBranch, baseBranch)
                indicator.text = "[$label] git checkout $targetBranch -- (${relPaths.size} paths)"
                val co = GitLineHandler(project, root, GitCommand.CHECKOUT)
                co.addParameters(targetBranch, "--")
                relPaths.forEach { co.addParameters(it) }
                val coResult = Git.getInstance().runCommand(co)
                if (!coResult.success()) {
                    throw IOException(coResult.errorOutputAsJoinedString)
                }
                if (stagedDiffEmpty(project, root)) {
                    throw IOException("分支「$newBranch」在检出后没有可提交的暂存变更，请检查路径是否与分支差异一致。")
                }
                indicator.text = "[$label] commit on $newBranch"
                runGitOrThrow(project, root, GitCommand.COMMIT, "-m", target.commitMessage.trim())
                created.add(newBranch)
            }
        } catch (e: Throwable) {
            sliceError = e
        } finally {
            indicator.text = "切回 $branchToRestore…"
            try {
                runGitOrThrow(project, root, GitCommand.CHECKOUT, branchToRestore)
            } catch (restoreEx: Throwable) {
                if (sliceError != null) {
                    sliceError.addSuppressed(restoreEx)
                } else {
                    sliceError = restoreEx
                }
            }
        }
        sliceError?.let { throw it }
        return created
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

    private fun sanitizeUserBranchName(name: String): String {
        val t =
            name
                .trim()
                .replace('\\', '/')
                .trimEnd('/')
        if (t.isEmpty()) {
            throw IOException("分支名不能为空。")
        }
        if (t.contains("..") || t.startsWith("-") || t.endsWith(".lock")) {
            throw IOException("非法分支名：$name")
        }
        if (!t.matches(Regex("^[a-zA-Z0-9/._-]+$"))) {
            throw IOException("分支名仅允许字母、数字、/、.、_、-：$name")
        }
        return t
    }

    /** 拆分全部完成后在 **后台线程** 执行一次 `git branch --list`，与在终端手动执行类似（IDE 不解析输出，仅作状态对齐尝试）。 */
    private fun nudgeGitBranchList(project: Project, root: VirtualFile) {
        val h = GitLineHandler(project, root, GitCommand.BRANCH)
        h.addParameters("--list", "--no-color")
        Git.getInstance().runCommand(h)
    }

    /**
     * 在后台改完 Git 后，主动驱动 IDE 里 Git / VCS 相关 UI 刷新（分支列表、状态等）。
     * 先递归刷新仓库 `.git` 目录的 VFS（新分支在 `refs/heads/` 下），再 [GitRepository.update] 与 [GitRepository.GIT_REPO_CHANGE]。
     */
    private fun refreshGitAndVcsUi(project: Project) {
        val mgr = GitRepositoryManager.getInstance(project)
        for (repository in mgr.repositories) {
            try {
                repository.gitDir.refresh(false, true)
            } catch (_: Throwable) {
                // 个别主题/沙箱下 refresh 失败不阻断后续 update
            }
            repository.update()
            project.messageBus.syncPublisher(GitRepository.GIT_REPO_CHANGE).repositoryChanged(repository)
        }
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        VirtualFileManager.getInstance().refreshWithoutFileWatcher(false)
    }

    private fun primaryRepository(project: Project): GitRepository? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        if (repos.isEmpty()) return null
        if (repos.size == 1) return repos.single()
        val base = project.basePath ?: return repos.first()
        return repos.find { base.startsWith(it.root.path) } ?: repos.first()
    }

    private data class PreflightResult(
        val baseAndPaths: Pair<String, List<String>>,
        val dirty: Boolean,
    )

    /**
     * Git4Idea 在 EDT 上执行 [Git.runCommand] 可能触发 HTTP 认证并调用
     * [com.intellij.ide.BuiltInServerManagerImpl.waitForStart]，平台会断言失败。
     * 所有读 Git 的命令必须在 [Task.Modal] 的后台线程中执行。
     */
    private fun <T> runGitOffEdt(project: Project, title: String, block: () -> T): T {
        val holder = arrayOfNulls<Any>(1)
        var thrown: Throwable? = null
        ProgressManager.getInstance().run(
            object : Task.Modal(project, title, true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    try {
                        holder[0] = block()
                    } catch (t: Throwable) {
                        thrown = t
                    }
                }
            },
        )
        thrown?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    companion object {
        private val BASE_BRANCH_TRY_ORDER = listOf("master", "main")
        private val BASE_BRANCH_NAMES = BASE_BRANCH_TRY_ORDER.toSet()
    }
}
