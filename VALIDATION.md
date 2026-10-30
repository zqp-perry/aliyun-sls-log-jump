# 公开版副本验证记录

日期：2026-10-30。

- 原内部工程复制前后逐文件 SHA-256 清单一致，共 219 个文件，新增/删除/修改均为 0。
- 只新增 Endpoint、Project、Logstore 三项用户配置；不保存 AK/SK/Token。
- 38 项单元测试全部通过，覆盖首次未配置、目标替换、状态重载、CLI 当前账号选择、拒绝不支持的角色模式、配置错误不回显敏感内容。
- 归一化包名并去除注释后，SlsQueryClient、StackFrameFilter、StackFrameHyperlink、SlsRealtimePolicy、SlsConsoleBuffer 五个核心类代码与原版一致。
- 独立只读代码审查已完成，两项发现均已修复并复查：历史旧查询结果失效处理、CLI 角色源账号误用防护。
- 新副本（含 ZIP/JAR 内部）未检出原项目已识别的内部资源值、账号角色标识、登录入口、私人路径和旧业务包名。此结论不等于对所有潜在凭证格式的绝对保证。
- ZIP 结构、独立插件 ID、SVG 图标、Java 25 字节码和不包含测试 class 已检查。
- 连接配置三个输入框已补充格式示例和常驻字段说明，README 已同步获取位置；重新编译后确认文案包含于最新 ZIP。

- 插件 Overview 已补充中文功能介绍，保留英文在前；更新说明同步中英文，已确认打包内容与源码一致。

产物：`sls-jump-plugin/build/distributions/aliyun-sls-log-jump-0.1.0.zip`。

SHA-256：`a1bfb2b14505ada029a7ad39a2395b6308c6d48e0993c7ea57f0312102eb13fb`。

验证边界：未读取真实云凭证，未连接真实 SLS，未启动 IDE 做安装/交互验证，未运行 Plugin Verifier。Marketplace 已由作者提交审核，本次仅补齐源码及本地构建包的许可证。当前只声明用来编译的 262.9437.185 起的 262 分支；其他版本未验证。

已添加 MIT 许可证，版权署名为 2026 zqp-perry；主 JAR 中的 META-INF/LICENSE 与仓库根目录 LICENSE 一致。仍需完善真实发布者名称、联系邮箱和主页。本副本不包含内部操作文档或私人工作记录。
