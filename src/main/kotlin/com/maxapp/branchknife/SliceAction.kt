package com.maxapp.branchknife

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import git4idea.GitBranch
import git4idea.actions.branch.GitBranchActionsUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.IOException
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Git Log「分支」面板向 DataContext 注入的选中分支列表；与 git4idea 内部 `GIT_BRANCHES` 同源（键名 `GitBranchKey`），该符号在 SDK 中为 internal，故用同名 [DataKey] 读取。
 */
private val GIT_BRANCH_KEY: DataKey<List<*>> = DataKey.create("GitBranchKey")

/** 复用对话框选择的语言，让 Action 内的提示消息与 UI 保持一致。 */
private fun t(zh: String, en: String) = if (dialogLang == DialogLang.ZH) zh else en

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
 *
 * **从 Git 分支弹窗子菜单**进入时，使用 [GitBranchActionsUtil.BRANCHES_KEY] 解析选中分支；
 * **Git Log 左侧分支树**：其右键菜单无法扩展，可在树中选中分支后通过快捷键 / Tools 菜单触发，此时通过 `GIT_BRANCH_KEY`（`GitBranchKey`）读取选中项。
 * 其它入口仍按原逻辑（当前分支或手动选择）。
 *
 * **基准分支**：`git diff` 默认依次尝试 `master`、`main`；若仓库根存在 **branch-knife.base**（单独一行 `main` 或 `master`），则优先使用该基准。
 */
/**
 * GitBranchActionsUtil.BRANCHES_KEY / SELECTED_REPO_KEY 在 IDEA 2025.1 中被移除。
 * 通过反射按需获取，旧版本正常使用，新版本退化为 null（插件仍可运行，只是无法从弹窗读取分支）。
 */
@Suppress("UNCHECKED_CAST")
private val GIT_BRANCHES_KEY: DataKey<List<GitBranch>>? = runCatching {
    GitBranchActionsUtil::class.java.getDeclaredField("BRANCHES_KEY")
        .also { it.isAccessible = true }
        .get(null) as? DataKey<List<GitBranch>>
}.getOrNull()

@Suppress("UNCHECKED_CAST")
private val GIT_SELECTED_REPO_KEY: DataKey<GitRepository>? = runCatching {
    GitBranchActionsUtil::class.java.getDeclaredField("SELECTED_REPO_KEY")
        .also { it.isAccessible = true }
        .get(null) as? DataKey<GitRepository>
}.getOrNull()

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
        val logBranchName = gitLogDashboardSelectionBranchName(e)
        e.presentation.description =
            when {
                !ready -> "项目索引更新中，请稍候再试。"
                logBranchName != null ->
                    "以 Log 分支树选中项「$logBranchName」为目标拆分（Git Log 分支树右键菜单无法扩展，请用选中 + 快捷键或菜单）。"
                else ->
                    "从 master/main 派生新分支，再检出目标功能分支上按服务划分的路径并提交；用 diff 仅列出差异文件路径。"
            }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val branchUiCtx = branchContextFromGitUi(e)
        val repo =
            GIT_SELECTED_REPO_KEY?.let { e.getData(it) }
                ?: branchUiCtx?.preferredRepo
                ?: primaryRepository(project)
        if (repo == null) {
            Messages.showErrorDialog(project, t("未找到 Git 仓库。", "No Git repository found."), "Branch Knife")
            return
        }
        val root = repo.root
        val branchBeforeRun =
            repo.currentBranchName
                ?: run {
                    Messages.showErrorDialog(project, t("当前为 detached HEAD，无法记录要切回的分支。", "Cannot proceed: repository is in detached HEAD state."), "Branch Knife")
                    return
                }
        val targetBranch =
            resolveTargetFeatureBranch(project, repo, root, branchUiCtx?.refForGit)
                ?: return
        val preflight =
            try {
                runGitOffEdt(project, "Branch Knife：读取差异与状态") {
                    val baseAndPaths = diffNameOnlyBaseToTarget(project, root, targetBranch)
                    val dirty = hasDirtyWorkingTree(project, root)
                    PreflightResult(baseAndPaths, dirty)
                }
            } catch (ex: Throwable) {
                Messages.showErrorDialog(project, ex.message ?: ex.toString(), "Branch Knife")
                return
            }
        val (baseBranch, paths) = preflight.baseAndPaths
        if (targetBranch == baseBranch) {
            Messages.showInfoMessage(
                project,
                t(
                    "目标分支不能与基准分支「$baseBranch」相同。",
                    "Target branch cannot be the same as the base branch \"$baseBranch\".",
                ),
                "Branch Knife",
            )
            return
        }
        if (paths.isEmpty()) {
            Messages.showInfoMessage(
                project,
                t(
                    "「$baseBranch...$targetBranch」之间没有文件差异，无需拆分。\n请确认目标分支上已有相对基准的提交。",
                    "No file differences found between \"$baseBranch\" and \"$targetBranch\".\nMake sure the target branch has commits relative to the base.",
                ),
                "Branch Knife",
            )
            return
        }
        if (preflight.dirty) {
            val proceed =
                MessageDialogBuilder.okCancel(
                    "Branch Knife",
                    t(
                        "工作区或暂存区有未提交修改，拆分过程会多次切换分支，可能产生冲突或丢失风险。\n建议先 commit / stash 再操作。是否仍要继续？",
                        "You have uncommitted changes. The split process will switch branches multiple times, which may cause conflicts.\nIt is recommended to commit or stash first. Continue anyway?",
                    ),
                ).icon(Messages.getWarningIcon())
                    .ask(project)
            if (!proceed) return
        }
        val rules = SlicerService.loadPathRules(root.path)
        val grouped = SlicerService.groupPaths(paths, rules).filterValues { it.isNotEmpty() }
        if (grouped.isEmpty()) {
            Messages.showInfoMessage(project, t("没有可分组的路径。", "No paths to group."), "Branch Knife")
            return
        }
        val dialog =
            SmartPrSplitterDialog(
                project,
                paths,
                grouped,
                baseBranch,
                targetBranch,
                targetBranchFullName = branchUiCtx?.fullName,
                targetBranchIsRemote = branchUiCtx?.isRemote ?: false,
            )
        if (!dialog.showAndGet()) return
        val targets = dialog.getConfirmedTargets()
        if (targets.isNullOrEmpty()) {
            Messages.showInfoMessage(project, t("未配置任何分支目标。", "No branch targets configured."), "Branch Knife")
            return
        }
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Branch Knife：按服务拆分分支", true) {
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
                        // GitRepository.update() 禁止在 EDT 调用（IJ 平台线程模型），必须在当前 BGT 完成
                        updateAllGitRepositories(project)
                        ApplicationManager.getApplication().invokeLater {
                            publishGitChangeAndRefreshVcsUiOnEdt(project)
                            val branchList = created.joinToString("\n") { "• $it" }
                            Messages.showInfoMessage(
                                project,
                                t(
                                    "已成功创建 ${created.size} 个分支：\n\n$branchList\n\n已切回「$branchBeforeRun」。",
                                    "Successfully created ${created.size} branch(es):\n\n$branchList\n\nSwitched back to \"$branchBeforeRun\".",
                                ),
                                "Branch Knife",
                            )
                        }
                    } catch (ex: Throwable) {
                        val msg = ex.message ?: ex.toString()
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(project, msg, "Branch Knife")
                        }
                    }
                }
            },
        )
    }

    private data class GitPopupBranchContext(
        /** `git diff` / `git checkout` 使用的 ref 短名，如 `feature/x`、`origin/feature/x` */
        val refForGit: String,
        /** 完整引用，如 `refs/heads/feature/x`、`refs/remotes/origin/x` */
        val fullName: String,
        val isRemote: Boolean,
        /** Git Log「分支」面板选中项对应的仓库（多根时优先） */
        val preferredRepo: GitRepository? = null,
    )

    /**
     * 解析「当前要选作拆分目标」的分支：
     * 1. 状态栏/Git 大分支弹窗里子菜单注入的 [GitBranchActionsUtil.BRANCHES_KEY]；
     * 2. **Git Log 左侧分支树**焦点上下文中的 `GIT_BRANCH_KEY`（与平台 `GitBranchKey` 一致；该树右键菜单无法注册第三方项）。
     */
    private fun branchContextFromGitUi(e: AnActionEvent): GitPopupBranchContext? {
        val fromPopup = GIT_BRANCHES_KEY?.let { e.getData(it) }
        if (!fromPopup.isNullOrEmpty()) {
            val b: GitBranch = fromPopup.first()
            return GitPopupBranchContext(
                refForGit = b.name.trim(),
                fullName = b.fullName,
                isRemote = b.isRemote,
                preferredRepo = null,
            )
        }
        val fromLogDashboard = e.getData(GIT_BRANCH_KEY) ?: return null
        if (fromLogDashboard.isEmpty()) return null
        return gitPopupContextFromDashboardBranchInfo(fromLogDashboard.first())
    }

    /** 供 [update] 展示：从 Log 分支面板选中项读取短名（不依赖 internal `git4idea.ui.branch.dashboard`）。 */
    private fun gitLogDashboardSelectionBranchName(e: AnActionEvent): String? {
        val list = e.getData(GIT_BRANCH_KEY) ?: return null
        if (list.isEmpty()) return null
        return dashboardBranchInfoBranchName(list.first())
    }

    private fun dashboardBranchInfoBranchName(item: Any?): String? {
        if (item == null) return null
        return try {
            val c = item.javaClass
            if (c.name != "git4idea.ui.branch.dashboard.BranchInfo") return null
            (c.getMethod("getBranchName").invoke(item) as String).trim()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 通过反射构造上下文：`BranchInfo` 在 vcs-git 中为 internal，无法直接引用类型。
     */
    private fun gitPopupContextFromDashboardBranchInfo(item: Any?): GitPopupBranchContext? {
        if (item == null) return null
        return try {
            val c = item.javaClass
            if (c.name != "git4idea.ui.branch.dashboard.BranchInfo") return null
            val name = (c.getMethod("getBranchName").invoke(item) as String).trim()
            val isLocal = c.getMethod("isLocal").invoke(item) as Boolean
            @Suppress("UNCHECKED_CAST")
            val repos = c.getMethod("getRepositories").invoke(item) as List<*>
            val preferredRepo = repos.firstOrNull() as? GitRepository
            val full =
                if (isLocal) {
                    "refs/heads/$name"
                } else {
                    "refs/remotes/$name"
                }
            GitPopupBranchContext(
                refForGit = name,
                fullName = full,
                isRemote = !isLocal,
                preferredRepo = preferredRepo,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * @param branchFromPopup 非空时直接作为要拆分的功能分支（相对基准分支做 diff）；否则沿用「当前分支或弹窗选择」。
     */
    private fun resolveTargetFeatureBranch(
        project: Project,
        repo: GitRepository,
        root: VirtualFile,
        branchFromPopup: String?,
    ): String? {
        if (!branchFromPopup.isNullOrBlank()) {
            return branchFromPopup.trim()
        }
        val current = repo.currentBranchName ?: return null
        if (current !in BASE_BRANCH_NAMES) {
            return current
        }
        val locals =
            try {
                runGitOffEdt(project, "Branch Knife：读取本地分支") {
                    listLocalBranchShortNames(project, root)
                }
            } catch (ex: Throwable) {
                Messages.showErrorDialog(project, ex.message ?: ex.toString(), "Branch Knife")
                return null
            }
        val candidates = locals.filter { it !in BASE_BRANCH_NAMES }.sorted()
        if (candidates.isEmpty()) {
            Messages.showErrorDialog(
                project,
                t(
                    "当前在基准分支「$current」，且没有其它本地分支可选。\n请先创建或拉取要拆分的功能分支。",
                    "You are on the base branch \"$current\" and there are no other local branches.\nPlease create or pull the feature branch you want to split.",
                ),
                "Branch Knife",
            )
            return null
        }
        return ChooseFeatureBranchDialog(project, current, candidates).open()
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
     * `git diff --name-only <base>...<targetBranch>`。
     * 基准分支顺序：若存在 `branch-knife.base` 则优先；否则依次 **master → main**（先成功的为准）。
     */
    private fun diffNameOnlyBaseToTarget(
        project: Project,
        root: VirtualFile,
        targetBranch: String,
    ): Pair<String, List<String>> {
        var lastErr = ""
        val bases = baseBranchCandidates(root)
        for (base in bases) {
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
        val tried = bases.joinToString("、") { "$it...$targetBranch" }
        throw IOException(
            "无法用 $tried 列出差异路径。\n" +
                "请确认本地存在 main 或 master；若两者都有且需固定其一，可在仓库根添加文件 branch-knife.base（单独一行 main 或 master）。\n" +
                lastErr,
        )
    }

    private fun baseBranchCandidates(root: VirtualFile): List<String> {
        val pref = SlicerService.loadBaseBranchPreference(root.path)
        return if (pref != null) {
            listOf(pref) + BASE_BRANCH_TRY_ORDER.filter { it != pref }
        } else {
            BASE_BRANCH_TRY_ORDER.toList()
        }
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

    /** 在 **后台线程** 同步 Git4Idea 仓库元数据（[GitRepository.update] 不允许在 EDT 调用）。 */
    private fun updateAllGitRepositories(project: Project) {
        GitRepositoryManager.getInstance(project).repositories.forEach { it.update() }
    }

    /**
     * 在 **EDT** 上刷新 `.git` 的 VFS、广播 [GitRepository.GIT_REPO_CHANGE]、标记 VCS 脏范围。
     * 须在 [updateAllGitRepositories] 于 BGT 执行完毕之后调用。
     */
    @Suppress("DEPRECATION")
    private fun publishGitChangeAndRefreshVcsUiOnEdt(project: Project) {
        val mgr = GitRepositoryManager.getInstance(project)
        for (repository in mgr.repositories) {
            try {
                repository.root.refresh(false, true)
            } catch (_: Throwable) {
                // 个别环境下 refresh 失败不阻断后续通知
            }
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

/**
 * 替代已弃用的 [Messages.showChooseDialog]：模态列表选择本地功能分支。
 */
private class ChooseFeatureBranchDialog(
    project: Project,
    private val currentBranch: String,
    candidates: List<String>,
) : DialogWrapper(project) {
    private val model =
        DefaultListModel<String>().apply {
            candidates.forEach { addElement(it) }
        }
    private val list = JBList(model)

    init {
        title = "Branch Knife"
        init()
        if (candidates.isNotEmpty()) {
            list.selectedIndex = 0
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(
            JLabel(
                "<html><body width='${JBUI.scale(380)}'>" +
                    "当前在「${escapeHtmlForChooseDialog(currentBranch)}」。请选择<strong>要拆分的功能分支</strong>。<br/><br/>" +
                    "插件会用 git diff 列出「master / main」与该分支之间的<strong>差异文件路径</strong>（不合并），再按目录拆分。" +
                    "</body></html>",
            ),
            BorderLayout.NORTH,
        )
        list.visibleRowCount = 12
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.preferredSize = Dimension(JBUI.scale(420), JBUI.scale(320))
        return panel
    }

    fun open(): String? {
        if (!showAndGet()) return null
        return list.selectedValue
    }
}

private fun escapeHtmlForChooseDialog(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
