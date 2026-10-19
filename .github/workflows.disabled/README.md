# CI/CD 工作流（已暂停）

GitHub Actions 只识别 `.github/workflows/` 目录下的 workflow，因此本目录的内容**不会触发任何运行**。

## 为什么暂停

- 项目当前处于 **阶段二：接口契约与三方接入固化**，构建与测试都在本地 `gradlew` + javac 快路径上迭代，仓库暂时不需要每次 push 都消耗 Actions 分钟数。
- 门禁没有降级，只是改为**本地执行同样四项**：`:common:test` + 架构守卫 + jar 冒烟 + spec↔`@Tag` 双向覆盖扫描（见 [CONTRIBUTING.md](../../CONTRIBUTING.md) §2、[specs/README.md](../../specs/README.md)）。

## 里面有什么

- `ci.yml`：push / PR 时的四项门禁 + spec-coverage 双向映射。
- `release.yml`：推送 `v*` 标签时构建 `forge/versions/` 下全部 MC 版本并发布 GitHub Release。

## 如何恢复

```bash
git mv .github/workflows.disabled/ci.yml .github/workflows/ci.yml
git mv .github/workflows.disabled/release.yml .github/workflows/release.yml
git rm .github/workflows.disabled/README.md
git commit -m "ci: restore GitHub Actions workflows"
```

恢复后请在推送新的 `v*` 标签前确认 `forge/versions/` 版本矩阵与 `gradlew :common:test` 全绿。（历史 `v0.0.1` 的 Release 与标签已撤回，留档提交 `a462a73` 可按需重建。）
