# aliyun-SLS-Log-jump

在 IntelliJ IDEA 内查看阿里云 SLS 日志，点击 Java 异常堆栈跳转到本地源码。

项目地址：[zqp-perry/aliyun-sls-log-jump](https://github.com/zqp-perry/aliyun-sls-log-jump)。

此目录是独立的公开版副本。内部版未改动；公开版使用独立的插件 ID、工具窗口和配置存储，可与内部版区分。当前仍是上架准备版，尚未在 Marketplace 发布，也未选定开源许可证。

## 使用方式

1. 在 IDEA 中通过 Settings → Plugins → 齿轮 → Install Plugin from Disk 安装 `sls-jump-plugin/build/distributions/aliyun-sls-log-jump-0.1.0.zip`，按 IDE 提示重启。
2. 打开工具窗口 `aliyun-SLS-Log-jump`，点击「连接配置」。
3. 只需填写三个字段：

| 字段 | 填写内容和获取位置 | 格式示例 |
| --- | --- | --- |
| Endpoint（服务地址） | 在阿里云 SLS 控制台查看目标 Project 所在地域的 Endpoint。填写本机可访问的 SLS 服务地址，不是控制台网页 URL。 | `cn-hangzhou.log.aliyuncs.com` |
| Project（日志项目） | 在 SLS 控制台的 Project 列表中查看名称。这是阿里云日志项目名称，不是 IDEA 项目名称。 | `my-app-logs` |
| Logstore（日志库） | 进入上述 Project，在「日志存储 → 日志库」中查看需要查询的日志库名称，不是日志文件名或文件路径。 | `application-logs` |

这些示例仅用于说明格式，请替换为自己的真实资源名称。连接弹窗会在空输入框中显示示例，并在每个字段下方显示解释和获取位置。

控制台入口：[阿里云日志服务](https://sls.console.aliyun.com/)。获取资源信息可参考[官方 Project 管理说明](https://help.aliyun.com/zh/sls/manage-a-project)、[查询日志指引](https://help.aliyun.com/zh/sls/quick-guide-to-query-and-analysis)。

4. 确保本机已经配置可查询该资源的阿里云凭证，见下节。
5. 选择时间范围后点击「查询」，或切换到「实时」。点击蓝色 Java 堆栈链接即可定位源码。

首次查询或进入实时模式时，若没有目标配置，会先打开配置弹窗；取消不会发起请求。配置按当前 IDE 项目保存。保存新目标会停止旧目标的实时拉取，之后由使用者重新查询或进入实时模式。

## 凭证

无需在插件配置中填写 AK/SK。插件依次使用：

- IDEA 进程环境变量：`ALIBABA_CLOUD_ACCESS_KEY_ID`、`ALIBABA_CLOUD_ACCESS_KEY_SECRET`，以及可选的 `ALIBABA_CLOUD_SECURITY_TOKEN`。兼容原有 `ALIYUN_AK_ID`、`ALIYUN_AK_SECRET`、`ALIYUN_STS_TOKEN` 名称。
- 环境变量没有完整的 ID/Secret 时，读取本机 `~/.aliyun/config.json` 的 `current` 所指向的 profile。支持 `AK`、`StsToken` 模式，以及缺少 mode 的旧式 AK/STS 导出文件；其他模式会明确提示，不会把角色配置的源 AK 当作目标身份。不执行外部登录程序，不负责交互登录、角色扮演或自动刷新。

更新 CLI 文件后，下次请求重新读取；修改环境变量后通常需要重新启动 IDEA，并确保启动方式能继承这些变量。STS 过期时先更新本机凭证，再恢复查询。

插件只将 Endpoint、Project、Logstore 和查询参数写入当前项目的 `.idea/sls-log-jump-public.xml`，不保存 AK/SK/Token。请勿提交真实凭证或本地 `.idea` 配置到公开仓库。

## 保持原有日志规则

没有新增日志解析配置项，沿用内部版规则：

- 应用输入框使用 `_container_name_` 字段过滤。默认值为本地 IDE 项目名；若与日志中的容器名称不同，可以修改，或者清空以取消应用过滤。非 Kubernetes 日志可清空此框。
- 日志正文优先显示 `content` 字段；不存在该字段时使用其余字段的值作为回退。
- 错误/警告/信息/调试筛选使用原有关键词查询。目标 Logstore 需要支持相应的查询索引。
- Java 堆栈格式、类名解析、行号跳转及文件名回退规则保持原样。需要本地存在对应源码，并尽量与运行版本一致。
- 实时模式默认每 2 秒轮询，保留原有暂停、恢复、重置和缓冲限制。

## 本地构建

当前只针对已验证可编译的平台准备：**IDEA build 262.9437.185 及同一 262 分支的后续版本、Java 25**。没有通过改写 class 版本号宣称兼容旧 IDEA。实际运行和 Marketplace 兼容性仍需安装验证及 Plugin Verifier。

需要 Python 3、Maven 和安装了 Java 支持的 IntelliJ IDEA。构建使用 IDEA 自带的 JBR 25。

```bash
cd sls-jump-plugin
./build-local.sh
# 或传入 IDEA 安装目录：
./build-local.sh "/path/to/IntelliJ IDEA.app"
```

脚本在需要时通过 Maven 获取 `dependencies.pom.xml` 中的运行依赖和固定版本的 JUnit，编译源码、运行测试，并生成：

```text
build/distributions/aliyun-sls-log-jump-0.1.0.zip
```

测试不连接真实 SLS，也不读取本机真实云凭证。`build/` 中的依赖、class 和测试输出是本地生成文件，不能作为源码仓库内容提交。

## 发布前剩余事项

- 确定真实作者/发布者名称、邮箱、主页和许可证。当前 vendor 是中性草稿标识；未代替作者选择开源许可证。
- 用独立测试项目做 IDE 安装、配置保存和真实 SLS 查询验证，再运行 Plugin Verifier。
- 核对第三方依赖的许可证及再分发要求，准备脱敏的真实截图。
- 仅使用公开版构建出的 ZIP；不要使用内部版或旧的 universal ZIP。
