# 0.1.1 兼容性修复与验证

日期：2026-10-30。

## 修复内容

- 使用公开的 `ToolWindowManagerListener.stateChanged(ToolWindowManager)` 回调，按当前工具窗口可见性决定是否停止实时拉取；移除同时标记 Internal 和 Experimental 的三参数回调。
- 使用公开的 `FilenameIndex.getVirtualFilesByName(String, GlobalSearchScope)` 查找有效文件，替换 deprecated 的 PSI 文件查找接口；同名文件回退仍只打开文件，不依据可能不匹配的行号定位。
- 发布版本升为 0.1.1，保留此前 0.1.0 的下载文件。

## 本地对照验证

工具：JetBrains Plugin Verifier 1.410；目标：本机 IntelliJ IDEA 2026.2.1（IU-262.9437.185）；运行时：IDE 自带 JBR 25；离线验证使用本机依赖。

| 检查项 | 0.1.0 | 0.1.1 |
| --- | --- | --- |
| 总体结果 | Compatible，含以下三项 API 告警 | Compatible |
| Internal API usage | 1 | 0 |
| Experimental API usage | 1 | 0 |
| Deprecated API usage | 1 | 0 |
| 二进制兼容问题 | 0 | 0 |

本机 IDE 布局读取存在部分缺失布局文件及可选依赖提示，两版对照使用同一安装环境；以上为插件验证结论，不代表已完成其他 IDE 版本或实际云服务联调。

- 38 项单元测试通过。
- 独立只读代码复查无阻断项。
- 检查了 ZIP 结构、版本号、MIT 许可证及已识别的内部资源值；未打包测试类或旧版插件 JAR。
- 内部工程复制前后 219 个文件校验一致，无修改。

## Marketplace 状态与下一步

作者提供的截图显示：0.1.0 在 IDEA 2026.2.3 上通过 IDE 启动检查，并报告上述三项 API 告警；整体状态仍为 Under review。

本次没有自动提交 Marketplace。请在已有插件页面的 Upload Update 中上传 0.1.1，查看该版本针对 2026.2.3 的复核结果。无需新建另一个插件。

参考：[Plugin Verifier](https://github.com/JetBrains/intellij-plugin-verifier)、[API 兼容性检查](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)。
