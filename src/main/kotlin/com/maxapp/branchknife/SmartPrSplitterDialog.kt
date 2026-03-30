package com.maxapp.branchknife

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.Action
import javax.swing.JEditorPane
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.border.TitledBorder
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/** 每个目标分支：本地分支名 + 提交说明 + 要从目标功能分支检出的相对路径 */
data class PrSplitTarget(
    val branchName: String,
    val commitMessage: String,
    val paths: List<String>,
)

// ── Language ──────────────────────────────────────────────────────────────────

internal enum class DialogLang { ZH, EN }

/** 会话级语言偏好；切换后下次打开对话框仍延续，SliceAction 中的提示也读此状态。 */
internal var dialogLang: DialogLang = DialogLang.ZH

/** 根据当前语言返回对应字符串。 */
private fun t(zh: String, en: String): String =
    if (dialogLang == DialogLang.ZH) zh else en

private const val CARD_BRANCHES = "branches"
private const val CARD_EMPTY = "empty"

// ── Dialog ────────────────────────────────────────────────────────────────────

/**
 * 左侧：差异路径树（含文件类型图标）；右侧：可编辑分支目标卡片。
 * 右上角 [?] 展示使用说明，[EN/中文] 切换界面语言，Ctrl+Enter (⌘↩) 确认执行。
 */
class SmartPrSplitterDialog(
    private val project: Project,
    private val allPaths: List<String>,
    groupedPaths: Map<String, List<String>>,
    private val baseBranch: String,
    /** 传给 `git diff` / `git checkout` 的分支或远程 ref */
    private val targetBranch: String,
    private val targetBranchFullName: String? = null,
    private val targetBranchIsRemote: Boolean = false,
) : DialogWrapper(project) {

    private val sortedGroups = groupedPaths.entries.sortedBy { it.key }
    private val tree: JTree = buildPathsTree(allPaths)
    private val targetsPanel = JPanel()
    private val cards = mutableListOf<BranchTargetCard>()
    private val cardRows = mutableListOf<JPanel>()
    private val statusLabel = JLabel(" ")
    private var confirmedTargets: List<PrSplitTarget>? = null
    private val shortcutStr = if (SystemInfo.isMac) "⌘↩" else "Ctrl+Enter"

    // Empty state
    private val emptyStateLabel = JLabel("").apply {
        horizontalAlignment = JLabel.CENTER
        verticalAlignment = JLabel.CENTER
    }
    private val targetsCardLayout = CardLayout()
    private val targetsContainer = JPanel(targetsCardLayout)

    // Refs for language refresh
    private lateinit var localChangesLabel: JLabel
    private lateinit var targetBranchesTitleLabel: JLabel
    private lateinit var addBranchTargetBtn: JButton
    private lateinit var helpBtn: JButton
    private lateinit var langToggleBtn: JButton

    init {
        title = buildTitle()
        setOKButtonText(t("执行拆分", "Execute Split"))
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        targetsPanel.layout = BoxLayout(targetsPanel, BoxLayout.Y_AXIS)
        targetsPanel.border = JBUI.Borders.empty(8)

        if (sortedGroups.isNotEmpty()) {
            sortedGroups.forEach { (service, paths) ->
                addBranchCard(
                    suggestBranchName(service),
                    suggestCommitMessage(service),
                    paths.map { it.replace('\\', '/') }.toMutableList(),
                )
            }
        } else {
            addBranchCard(
                "feature/split-${System.currentTimeMillis() % 100000}",
                "feat: split changes",
                allPaths.map { it.replace('\\', '/') }.toMutableList(),
            )
        }

        init()
        refreshOkButtonTooltip()
        updateStatus()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(920), JBUI.scale(560))

    fun getConfirmedTargets(): List<PrSplitTarget>? = confirmedTargets

    override fun doOKAction() {
        confirmedTargets = validateAndBuildTargets()
        if (confirmedTargets == null) return
        super.doOKAction()
    }

    override fun createCenterPanel(): JComponent {
        // ── Left: file tree ───────────────────────────────────────────────────
        localChangesLabel = JLabel(localChangesText())
        val leftWrap =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 0, 0, 4)
                add(localChangesLabel, BorderLayout.NORTH)
                add(JBScrollPane(tree), BorderLayout.CENTER)
                preferredSize = Dimension(JBUI.scale(300), JBUI.scale(420))
            }

        // ── Right: header ─────────────────────────────────────────────────────
        targetBranchesTitleLabel = JLabel(t("目标分支", "TARGET BRANCHES"))

        helpBtn =
            JButton("?").apply {
                isContentAreaFilled = false
                isBorderPainted = true
                font = font.deriveFont(Font.BOLD)
                preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
                maximumSize = Dimension(JBUI.scale(24), JBUI.scale(24))
                toolTipText = t("查看使用说明", "View help")
                addActionListener { showHelpDialog() }
            }

        langToggleBtn =
            JButton(langToggleText()).apply {
                toolTipText = t("Switch to English", "切换到中文")
                addActionListener { toggleLang() }
            }

        addBranchTargetBtn =
            JButton(t("+ 添加目标分支", "+ Add Branch Target")).apply {
                addActionListener {
                    addBranchCard("feature/new-branch-${cards.size + 1}", "feat: ", mutableListOf())
                    targetsPanel.revalidate()
                    targetsPanel.repaint()
                    updateStatus()
                }
            }

        val rightTitleRow =
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
                add(targetBranchesTitleLabel)
            }
        val rightHeader =
            JPanel(BorderLayout()).apply {
                add(rightTitleRow, BorderLayout.CENTER)
                add(addBranchTargetBtn, BorderLayout.EAST)
            }

        // ── Empty state ───────────────────────────────────────────────────────
        emptyStateLabel.foreground = UIUtil.getInactiveTextColor()
        emptyStateLabel.font = emptyStateLabel.font.deriveFont(JBUI.scaleFontSize(13f))
        val emptyStatePanel =
            JPanel(BorderLayout()).apply {
                add(emptyStateLabel, BorderLayout.CENTER)
            }
        targetsContainer.add(JBScrollPane(targetsPanel), CARD_BRANCHES)
        targetsContainer.add(emptyStatePanel, CARD_EMPTY)

        val rightBody =
            JPanel(BorderLayout()).apply {
                add(rightHeader, BorderLayout.NORTH)
                add(targetsContainer, BorderLayout.CENTER)
            }

        val splitter =
            Splitter(false, 0.36f).apply {
                firstComponent = leftWrap
                secondComponent = rightBody
            }

        // ── 右上角工具按钮 ────────────────────────────────────────────────────
        val topRightButtons =
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(4))).apply {
                add(langToggleBtn)
                add(helpBtn)
            }
        val topBar =
            JPanel(BorderLayout()).apply {
                add(topRightButtons, BorderLayout.EAST)
            }

        // ── Main panel + Ctrl/Cmd+Enter shortcut ──────────────────────────────
        val shortcutMask =
            if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        val okStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, shortcutMask)
        return JPanel(BorderLayout()).apply {
            add(topBar, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
            registerKeyboardAction({ doOKAction() }, okStroke, JComponent.WHEN_IN_FOCUSED_WINDOW)
        }
    }

    // ── Language ──────────────────────────────────────────────────────────────

    private fun toggleLang() {
        dialogLang = if (dialogLang == DialogLang.ZH) DialogLang.EN else DialogLang.ZH
        refreshLang()
    }

    private fun refreshLang() {
        title = buildTitle()
        setOKButtonText(t("执行拆分", "Execute Split"))
        refreshOkButtonTooltip()
        localChangesLabel.text = localChangesText()
        targetBranchesTitleLabel.text = t("目标分支", "TARGET BRANCHES")
        helpBtn.toolTipText = t("查看使用说明", "View help")
        langToggleBtn.text = langToggleText()
        langToggleBtn.toolTipText = t("Switch to English", "切换到中文")
        addBranchTargetBtn.text = t("+ 添加目标分支", "+ Add Branch Target")
        updateStatus()
        cards.forEach { it.refreshLang() }
    }

    private fun refreshOkButtonTooltip() {
        try {
            getButton(myOKAction)?.toolTipText =
                t("执行拆分 ($shortcutStr)", "Execute Split ($shortcutStr)")
        } catch (_: Exception) {
            // myOKAction may not be accessible in all SDK versions; silently skip
        }
    }

    private fun buildTitle(): String =
        "Smart PR Splitter — $targetBranch (${t("相对", "vs")} $baseBranch)"

    private fun localChangesText(): String =
        t("本地变更（${allPaths.size} 个文件）", "LOCAL CHANGES (${allPaths.size} FILES)")

    private fun langToggleText(): String =
        if (dialogLang == DialogLang.ZH) "EN" else "中文"

    private fun emptyStateText(): String =
        t(
            "<html><center>点击「+ 添加目标分支」开始拆分</center></html>",
            "<html><center>Click '+ Add Branch Target' to start slicing</center></html>",
        )

    // ── Help dialog ───────────────────────────────────────────────────────────

    private fun showHelpDialog() {
        BkHelpDialog(
            project = project,
            dialogTitle = t("Branch-Knife 使用说明", "Branch-Knife Help"),
            bodyHtml = if (dialogLang == DialogLang.ZH) helpBodyZh() else helpBodyEn(),
        ).show()
    }

    private fun helpBodyZh() =
        """
        <b>🚀 快速开始</b><br/><br/>
        <b>核对目标</b>：右侧卡片展示了即将创建的各个分支。<br/>
        <b>分配文件</b>：在左侧树中勾选文件/文件夹，点击卡片上的「← 从树添加」将其分配到该分支。<br/>
        <b>执行拆分</b>：确认无误后，点击底部「执行拆分」或按 <b>$shortcutStr</b> 即可自动建分支并提交。<br/>
        <br/>
        <b>⚙️ 自动分组（推荐）</b><br/><br/>
        在项目根目录创建 <code>branch-knife.paths</code> 配置文件，定义你的路径匹配规则。<br/>
        点击卡片上的「自动检测」，插件将根据规则自动提取并填充对应的文件。<br/>
        <br/>
        <b>🕹️ 操作指南</b><br/><br/>
        <b>← 从树添加</b>：将左侧勾选的文件分配到当前卡片。<br/>
        <b>自动检测</b>：根据配置文件，重新为当前卡片提取路径。<br/>
        <b>移除路径</b>：从列表中删除选中的路径。<br/>
        <b>删除此目标</b>：移除整张分支卡片。
        """.trimIndent()

    private fun helpBodyEn() =
        """
        <b>🚀 Quick Start</b><br/><br/>
        <b>Review targets</b>: The right panel shows the branches that will be created.<br/>
        <b>Assign files</b>: Select files/folders in the left tree, then click <i>← Add from tree</i> to assign them to a specific branch.<br/>
        <b>Execute</b>: Click <i>Execute Split</i> or press <b>$shortcutStr</b> to create branches and commit the files.<br/>
        <br/>
        <b>⚙️ Auto-Grouping (Recommended)</b><br/><br/>
        Create a <code>branch-knife.paths</code> file in your project root to define routing rules.<br/>
        Click <i>Auto-detect</i> on any card to automatically fetch files matching your rules.<br/>
        <br/>
        <b>🕹️ Action Buttons</b><br/><br/>
        <b>← Add from tree</b>: Assign selected files from the left tree to this card.<br/>
        <b>Auto-detect</b>: Refill paths based on your config file.<br/>
        <b>Remove paths</b>: Delete the selected paths from the list.<br/>
        <b>Remove this branch</b>: Delete this entire target card.
        """.trimIndent()

    // ── Validation ────────────────────────────────────────────────────────────

    private fun validateAndBuildTargets(): List<PrSplitTarget>? {
        if (cards.isEmpty()) {
            Messages.showErrorDialog(
                project,
                t(
                    "请至少保留一张分支目标，或使用「+ 添加目标分支」创建。",
                    "Please keep at least one Branch Target.",
                ),
                "Branch-Knife",
            )
            return null
        }
        val result = mutableListOf<PrSplitTarget>()
        val usedPaths = mutableSetOf<String>()
        for (card in cards) {
            val branch = card.branchField.text.trim()
            val msg = card.commitField.text.trim()
            val paths = listModelStrings(card.pathsListModel).map { it.replace('\\', '/') }
            if (branch.isEmpty() || msg.isEmpty()) {
                Messages.showErrorDialog(
                    project,
                    t(
                        "每个分支目标都需要填写分支名与提交说明。",
                        "Branch name and commit message are required for each target.",
                    ),
                    "Branch-Knife",
                )
                return null
            }
            if (paths.isEmpty()) {
                Messages.showErrorDialog(
                    project,
                    t("分支「$branch」未包含任何路径。", "Branch \"$branch\" has no paths assigned."),
                    "Branch-Knife",
                )
                return null
            }
            val dup = paths.filter { !usedPaths.add(it) }
            if (dup.isNotEmpty()) {
                Messages.showErrorDialog(
                    project,
                    t(
                        "以下路径被重复分配到多个分支：\n${dup.take(6).joinToString("\n")}",
                        "Paths assigned to multiple branches:\n${dup.take(6).joinToString("\n")}",
                    ),
                    "Branch-Knife",
                )
                return null
            }
            result.add(PrSplitTarget(branch, msg, paths))
        }
        val missing = allPaths.map { it.replace('\\', '/') }.toSet() - usedPaths
        if (missing.isNotEmpty()) {
            val proceed =
                MessageDialogBuilder
                    .okCancel(
                        "Branch-Knife",
                        t(
                            "还有 ${missing.size} 个文件未分配，是否仍只提交已分配的路径？\n${missing.take(10).joinToString("\n")}${if (missing.size > 10) "\n…" else ""}",
                            "${missing.size} file(s) not assigned. Continue with only the assigned paths?\n${missing.take(10).joinToString("\n")}${if (missing.size > 10) "\n…" else ""}",
                        ),
                    ).icon(Messages.getWarningIcon())
                    .ask(project)
            if (!proceed) return null
        }
        return result
    }

    // ── Card management ───────────────────────────────────────────────────────

    private fun addBranchCard(
        defaultBranch: String,
        defaultMessage: String,
        initialPaths: MutableList<String>,
    ) {
        lateinit var card: BranchTargetCard
        card =
            BranchTargetCard(
                defaultBranch = defaultBranch,
                defaultMessage = defaultMessage,
                initialPaths = initialPaths,
                onPathsChanged = { updateStatus() },
                onAddFromTree = { addSelectedPathsFromTree(it) },
                onAutoDetect = { c -> autoDetectForCard(c) },
                onRemoveThisTarget = { removeCard(card) },
            )
        val row =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 0, JBUI.scale(8), 0)
                add(card.panel, BorderLayout.CENTER)
            }
        cards.add(card)
        cardRows.add(row)
        targetsPanel.add(row)
    }

    private fun removeCard(card: BranchTargetCard) {
        val i = cards.indexOf(card)
        if (i < 0) return
        cards.removeAt(i)
        val row = cardRows.removeAt(i)
        targetsPanel.remove(row)
        targetsPanel.revalidate()
        targetsPanel.repaint()
        updateStatus()
    }

    private fun addSelectedPathsFromTree(card: BranchTargetCard) {
        val paths = collectSelectedFilePaths()
        if (paths.isEmpty()) {
            Messages.showInfoMessage(
                project,
                t("请先在左侧树中选择文件或文件夹。", "Select files or folders in the left tree first."),
                "Branch-Knife",
            )
            return
        }
        for (p in paths) card.addPathIfAbsent(p)
        updateStatus()
    }

    private fun autoDetectForCard(card: BranchTargetCard) {
        val idx = cards.indexOf(card)
        if (idx in sortedGroups.indices) {
            card.setPaths(sortedGroups[idx].value.map { it.replace('\\', '/') }.toMutableList())
            updateStatus()
        } else {
            Messages.showInfoMessage(
                project,
                t("没有与当前卡片顺序对应的自动分组规则。", "No grouping rule matches this card's position."),
                "Branch-Knife",
            )
        }
    }

    private fun collectSelectedFilePaths(): List<String> {
        val paths = mutableListOf<String>()
        val sel = tree.selectionPaths ?: return paths
        for (tp in sel) {
            val node = tp.lastPathComponent as? DefaultMutableTreeNode ?: continue
            when (val u = node.userObject) {
                is PathTrie.Leaf -> paths.add(u.fullPath.replace('\\', '/'))
                else -> collectLeaves(node, paths)
            }
        }
        return paths.distinct()
    }

    private fun collectLeaves(node: DefaultMutableTreeNode, out: MutableList<String>) {
        if (node.userObject is PathTrie.Leaf) {
            out.add((node.userObject as PathTrie.Leaf).fullPath.replace('\\', '/'))
            return
        }
        for (i in 0 until node.childCount) {
            collectLeaves(node.getChildAt(i) as DefaultMutableTreeNode, out)
        }
    }

    private fun updateStatus() {
        val assigned =
            cards
                .flatMap { listModelStrings(it.pathsListModel) }
                .map { it.replace('\\', '/') }
                .toSet()
        val total = allPaths.size
        val n = assigned.size
        statusLabel.text =
            if (n >= total) {
                t(
                    "就绪：$total 个文件 → ${cards.size} 个分支",
                    "Ready to split $total files into ${cards.size} branch(es).",
                )
            } else {
                t(
                    "已分配 $n / $total 个文件，${total - n} 个未分配（仍可执行，仅提交已分配路径）",
                    "$n / $total files assigned, ${total - n} unassigned (can still execute)",
                )
            }
        emptyStateLabel.text = emptyStateText()
        targetsCardLayout.show(
            targetsContainer,
            if (cards.isEmpty()) CARD_EMPTY else CARD_BRANCHES,
        )
    }

    // ── Branch Target Card ────────────────────────────────────────────────────

    private inner class BranchTargetCard(
        defaultBranch: String,
        defaultMessage: String,
        initialPaths: MutableList<String>,
        private val onPathsChanged: () -> Unit,
        private val onAddFromTree: (BranchTargetCard) -> Unit,
        private val onAutoDetect: (BranchTargetCard) -> Unit,
        private val onRemoveThisTarget: () -> Unit,
    ) {
        val branchField = JBTextField(defaultBranch)
        val commitField = JBTextField(defaultMessage)
        val pathsListModel = DefaultListModel<String>()
        private val pathsList =
            JBList(pathsListModel).apply {
                visibleRowCount = 4
                selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            }

        private val titledBorder: TitledBorder =
            BorderFactory.createTitledBorder(t("分支目标", "Branch Target"))
        val panel: JPanel =
            JPanel(BorderLayout()).apply {
                border = BorderFactory.createCompoundBorder(titledBorder, JBUI.Borders.empty(8))
            }

        private val branchLabel = JLabel(t("分支名", "Branch name"))
        private val commitLabel = JLabel(t("提交说明", "Commit message"))
        private val includedPathsLabel = JLabel(t("包含路径", "Included paths"))
        private val addFromTreeBtn = JButton(t("← 从树添加", "← Add from tree"))
        private val autoDetectBtn = JButton(t("自动检测", "Auto-detect"))
        private val removePathsBtn = JButton(t("移除路径", "Remove paths"))
        private val removeBranchBtn = JButton(t("删除此目标", "Remove this branch"))

        init {
            initialPaths.sorted().forEach { pathsListModel.addElement(it.replace('\\', '/')) }

            val north =
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(labeled(branchLabel, branchField))
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(labeled(commitLabel, commitField))
                }
            panel.add(north, BorderLayout.NORTH)

            addFromTreeBtn.addActionListener { onAddFromTree(this@BranchTargetCard) }
            autoDetectBtn.addActionListener { onAutoDetect(this@BranchTargetCard) }
            removePathsBtn.addActionListener {
                pathsList.selectedIndices.sortedDescending().forEach { pathsListModel.remove(it) }
                onPathsChanged()
            }
            removeBranchBtn.addActionListener { onRemoveThisTarget() }

            val pathBar =
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                    add(addFromTreeBtn)
                    add(autoDetectBtn)
                    add(removePathsBtn)
                    add(removeBranchBtn)
                }
            val pathWrap =
                JPanel(BorderLayout()).apply {
                    add(includedPathsLabel, BorderLayout.NORTH)
                    add(JBScrollPane(pathsList), BorderLayout.CENTER)
                    add(pathBar, BorderLayout.SOUTH)
                }
            panel.add(pathWrap, BorderLayout.CENTER)

            val docL =
                object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) = onPathsChanged()
                }
            branchField.document.addDocumentListener(docL)
            commitField.document.addDocumentListener(docL)
        }

        fun refreshLang() {
            titledBorder.title = t("分支目标", "Branch Target")
            panel.revalidate()
            panel.repaint()
            branchLabel.text = t("分支名", "Branch name")
            commitLabel.text = t("提交说明", "Commit message")
            includedPathsLabel.text = t("包含路径", "Included paths")
            addFromTreeBtn.text = t("← 从树添加", "← Add from tree")
            autoDetectBtn.text = t("自动检测", "Auto-detect")
            removePathsBtn.text = t("移除路径", "Remove paths")
            removeBranchBtn.text = t("删除此目标", "Remove this branch")
        }

        fun addPathIfAbsent(p: String) {
            val n = p.replace('\\', '/')
            if (listModelStrings(pathsListModel).none { it.replace('\\', '/') == n }) {
                pathsListModel.addElement(n)
                onPathsChanged()
            }
        }

        fun setPaths(paths: MutableList<String>) {
            pathsListModel.clear()
            paths.sorted().map { it.replace('\\', '/') }.forEach { pathsListModel.addElement(it) }
            onPathsChanged()
        }
    }

    private fun labeled(label: JLabel, field: JBTextField): JPanel =
        JPanel(BorderLayout()).apply {
            add(label, BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
        }
}

// ── Help dialog (proper HTML renderer) ───────────────────────────────────────

/**
 * 使用 [JEditorPane] 渲染 HTML 内容，自动适配 IDE 深色/浅色主题。
 * 相比 [Messages.showInfoMessage] 的 JLabel，滚动、字体、行距均正常。
 */
private class BkHelpDialog(
    project: Project,
    private val dialogTitle: String,
    private val bodyHtml: String,
) : DialogWrapper(project) {

    init {
        title = dialogTitle
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val fg = UIUtil.getLabelForeground()
        val fgCss = "rgb(${fg.red},${fg.green},${fg.blue})"
        val bg = UIUtil.getPanelBackground()
        val monoFont = "JetBrains Mono, Menlo, Consolas, monospace"
        val html =
            """
            <html>
            <head><style>
              body  { font-family: sans-serif; font-size: 13px; color: $fgCss;
                      line-height: 1.6; margin: 12px 16px; }
              code  { font-family: $monoFont; font-size: 12px; }
              b     { font-weight: bold; }
            </style></head>
            <body>$bodyHtml</body>
            </html>
            """.trimIndent()

        val editorPane =
            JEditorPane("text/html", html).apply {
                isEditable = false
                background = bg
                border = null
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            }

        return JBScrollPane(editorPane).apply {
            border = null
            preferredSize = Dimension(JBUI.scale(500), JBUI.scale(340))
        }
    }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

private fun escapeHtmlForJlabel(s: String): String =
    s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private sealed class PathTrie {
    data class Dir(val name: String) : PathTrie() {
        override fun toString(): String = name
    }

    data class Leaf(val fullPath: String) : PathTrie() {
        override fun toString(): String = fullPath.substringAfterLast('/').ifEmpty { fullPath }
    }
}

/** 为左侧文件树的叶节点展示 IDEA 原生文件类型图标。 */
private class FileIconCellRenderer : DefaultTreeCellRenderer() {
    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode ?: return this
        val userObj = node.userObject
        if (userObj is PathTrie.Leaf) {
            val fileName = userObj.fullPath.substringAfterLast('/')
            FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon?.let { icon = it }
        }
        return this
    }
}

private fun buildPathsTree(paths: List<String>): JTree {
    val normalized =
        paths
            .map { it.replace('\\', '/').trim().removePrefix("./") }
            .filter { it.isNotEmpty() }
            .distinct()
    val root = DefaultMutableTreeNode(PathTrie.Dir("LOCAL CHANGES (${normalized.size} files)"))
    for (path in normalized.sorted()) insertPathIntoTree(root, path)
    val tree = JTree(DefaultTreeModel(root))
    tree.isRootVisible = true
    tree.cellRenderer = FileIconCellRenderer()
    for (i in 0 until tree.rowCount) tree.expandRow(i)
    return tree
}

private fun insertPathIntoTree(root: DefaultMutableTreeNode, path: String) {
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return
    var parent = root
    for (i in parts.indices) {
        val seg = parts[i]
        val isLast = i == parts.lastIndex
        var child: DefaultMutableTreeNode? = null
        for (c in 0 until parent.childCount) {
            val ch = parent.getChildAt(c) as DefaultMutableTreeNode
            when (val u = ch.userObject) {
                is PathTrie.Dir -> if (u.name == seg) { child = ch; break }
                is PathTrie.Leaf -> if (isLast && u.fullPath == path) { child = ch; break }
            }
        }
        if (child == null) {
            val newNode = DefaultMutableTreeNode(if (isLast) PathTrie.Leaf(path) else PathTrie.Dir(seg))
            parent.add(newNode)
            parent = newNode
        } else {
            parent = child
        }
    }
}

private fun suggestBranchName(service: String): String =
    "feature/" +
        service
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "split" }

private fun suggestCommitMessage(service: String): String = "feat: update $service"

private fun listModelStrings(m: DefaultListModel<String>): List<String> =
    (0 until m.size).map { m.getElementAt(it) }
