# Branch-Knife 插件手动测试仓库

**中性占位** monorepo：目录与文件名均为虚构示例。

## 路径 → 服务名（`branch-knife.paths`）

在**仓库根目录**维护 **`branch-knife.paths`**（本仓库已附带示例）。每行：

`路径片段=服务显示名`

先匹配的先生效，更具体的路径请写在文件**靠前**位置。无此文件或文件无有效行时，才使用插件内置的 `sample.*` 示例规则。

## 目录与分组（与 `branch-knife.paths` 示例一致）

| 路径前缀 | 分组名 |
|----------|--------|
| `src/sample.svc.alpha/` | Svc-Alpha |
| `src/sample.svc.beta/` | Svc-Beta |
| `src/sample.lib.shared/` | Lib-Shared |
| 其余 | Others |

## 插件工作流：已提交的功能分支 → 多个 `split/*` 本地分支

1. 在 **master** 上有基线提交；新建功能分支并 **commit** 你的改动。
2. 切到该功能分支，执行 **Slice by Service**。
3. 插件会在后台：**从 master/main 新建** `split/<服务>-<时间戳>`、**检出对应路径、提交**，最后**切回你的功能分支**。完成后弹窗会**列出新建的本地分支名**；请到 **右下角 Git 分支** 或 **Git → Branches** 里查看 `split/` 前缀的分支，再分别 Push 提 PR。

一键示例分支：

```bash
chmod +x scripts/demo-committed-feature-branch.sh
./scripts/demo-committed-feature-branch.sh
```

## 初次生成目录树

```bash
./scripts/seed-layout.sh
git add -A && git commit -m "chore: refresh fixture tree"
```

## 可选：`reset-test-wip.sh`

仅用于批量制造**未提交**修改做别的实验；**当前 Slice 主流程按「已提交差异」工作，不依赖此脚本**。

## 在 IDEA 里测

1. **Run Plugin** 沙箱或安装 `build/distributions/*.zip`，打开本目录。
2. 使用 `demo-committed-feature-branch.sh` 或自备带提交的功能分支。
3. **Tools → Branch-Knife → Slice by Service…**（或 Git 菜单 / Find Action）。

## 清理

```bash
git checkout master
git branch -D demo/feature-combined 2>/dev/null || true
git branch --list 'split/*' | sed 's/^[* ]*//' | while read -r b; do [ -n "$b" ] && git branch -D "$b"; done
```
