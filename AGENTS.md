# AGENTS.md

本文件记录本项目经代码、配置与版本控制证据确认的工程约定，供在本仓库工作的自动化助手与协作者参考。仅包含有据可查的结论；无法确认的内容在末尾单独标注。

## 项目概述

JTools SSH Publisher 是一个 IntelliJ IDEA 平台插件（`plugin.xml` 中 `id=com.lhstack.ssh-publisher`），提供 SSH 终端、SFTP 文件浏览、批量上传、上传模板与传输管理能力。插件通过右侧工具窗口 `SSH Publisher` 接入 IDE（`SshToolWindowFactory`）。

## 工程环境与主要工具

- 语言：Kotlin（`org.jetbrains.kotlin.jvm` 1.9.22），同时启用 Java 插件。
- 构建：Gradle + `org.jetbrains.intellij` 1.17.2；`build.gradle.kts` / `settings.gradle.kts` 为 Kotlin DSL。
- 目标平台：IntelliJ IC 2022.3，`sinceBuild=223`，`untilBuild=261.*`。
- JVM Toolchain：17（`sourceCompatibility`/`targetCompatibility`/`jvmTarget` 均为 17）。
- Kotlin 编译参数：`-Xjvm-default=all`。
- 依赖：
  - `org.apache.sshd:sshd-sftp:2.15.0`（SSH/SFTP 客户端，Apache MINA sshd）
  - `org.xerial:sqlite-jdbc:3.45.1.0`（本地配置存储）
  - 平台插件依赖：`org.jetbrains.plugins.terminal`（JediTerm 终端）
  - 测试：`org.jetbrains.kotlin:kotlin-test`，`tasks.test` 使用 `useJUnitPlatform()`
- 依赖仓库顺序：`mavenLocal` → 阿里云公共镜像 → `mavenCentral`。
- 平台类加载约定：全局 `exclude` 了 `org.slf4j:slf4j-api` 与 `jcl-over-slf4j`，因为 IntelliJ 平台已提供 SLF4J 绑定，重复打入会触发类加载冲突（见 `build.gradle.kts` 注释）。新增依赖时需留意不要重新引入这些模块。
- 编码：源码与项目环境统一 UTF-8。

## 目录与模块结构

源码根：`src/main/kotlin/com/lhstack/ssh`

- `PluginIcons.kt`：图标常量集中管理（`resources` 下每个图标有明暗两套 SVG）。
- `SshToolWindowFactory.kt`：工具窗口入口，创建 `MainView`。
- `model/`：数据模型，`data class` + `Serializable`（`SshConfig`、`JumpHostConfig`、`ScriptConfig`、`UploadTemplate`、`TransferTask`、`SystemInfo`）。
- `service/`：业务服务层。
  - `SshConfigService`（`object` 单例，SQLite 持久化）
  - `SshConnectionManager`（每实例一条 SSH 连接，含跳板链、SFTP、Shell、命令执行）
  - `JumpHostSupport`（跳板链规划 `SshConnectionChainPlanner`、地址解析、错误格式化、JSON 编解码）
  - `TransferTaskManager`（`object` 单例，线程池调度上传/下载任务）
  - `RemoteFileEditorService`（远程文件下载到本地缓存、编辑、保存回传）
  - `ConfigExportImportService`（配置与模板 JSON 导入导出）
- `util/`：无 UI 依赖的纯逻辑工具，便于单测（`SftpTreeOperationUtils`、`RemoteRiskOperationUtils`、`TransferAndTerminalUtils`、`TerminalInputMethodUtils`、`LatestRequestGuard`、`ActionFactory`）。
- `view/`：Swing UI 面板与对话框（`MainView`、`DockableTabPanel`、`SftpFileSystemPanel`、`SshTerminalPanel`、`SystemMonitorBar`、`TransferTaskPanel`、`UploadTemplatePanel`、各类 Dialog）。
- `component/`：可复用 UI 组件（`MultiLanguageTextField`）。

资源：`src/main/resources`，含 `META-INF/plugin.xml` 与全部 SVG 图标。

测试：`src/test/kotlin/com/lhstack/ssh`，按 `util` / `view` / `service` 分包，主要覆盖纯逻辑工具类（命名后缀 `Test` 或 `SmokeTest`）。

## 分层架构与依赖方向

- 依赖方向：`view` → `service` / `util` / `model`；`service` → `util` / `model`；`util` 与 `model` 不反向依赖上层，`util` 不依赖 IntelliJ UI，可独立单测。
- UI 面板通过 `Disposable` 接入 IDE 生命周期：面板 `init` 中 `Disposer.register(parentDisposable, this)`，`dispose()` 中释放连接、线程池、监听与子组件。
- 每个 SFTP 面板 / 终端面板 / 传输任务各自持有独立的 `SshConnectionManager` 实例（一个 manager 一条会话链），传输任务在 `TransferTaskManager` 中按任务独立建连。
- 配置持久化集中在 `SshConfigService`（SQLite，位于 `~/.jtools/jtools-ssh-publisher/db.data`）；远程文件本地缓存位于 `~/.jtools/jtools-ssh-publisher/<configId>/`。

## 构建、测试与验证方式

- 构建插件：`./gradlew buildPlugin`（或在 IDE 中构建）。
- 运行测试：`./gradlew test`（JUnit Platform）。
- 沙箱运行：`./gradlew runIde`。
- 纯逻辑改动优先补充/运行 `util` 下的单元测试；UI 与 SSH 交互类主要靠沙箱手动验证。

## 项目编码约定

- Kotlin 官方代码风格（`gradle.properties`: `kotlin.code.style=official`），4 空格缩进。
- 模型用 `data class`，需要跨会话/导出的模型实现 `Serializable`。
- ID 生成：`SshConfig`/`UploadTemplate` 用 `UUID.randomUUID().toString().replace("-", "")`。
- 单例服务用 Kotlin `object`（`SshConfigService`、`TransferTaskManager`、各 `util` 下 `object`）。
- 注释与用户可见文案以中文为主，KDoc 说明方法意图。
- 纯逻辑（路径拼接、上传计划、冲突分析、确认文案、分块 IO 等）下沉到 `util` 的 `object`，UI 只负责编排与交互，方便单测。
- SQLite 访问统一使用 `PreparedStatement` 参数化，`.use { }` 管理 `Statement`/`ResultSet`；表结构演进用 `ALTER TABLE ... ADD COLUMN` 包 `try/catch` 做幂等迁移。
- 后台工作用线程或线程池执行，回到 UI 统一 `SwingUtilities.invokeLater` / `ApplicationManager.getApplication().invokeLater`。
- 图标统一走 `PluginIcons`，不散落 `IconLoader` 调用。

## 错误处理约定

- 面向用户的失败通过 `Messages.showErrorDialog` / 状态栏文案 / `Notification` 呈现，并附带具体原因。
- SSH 连接错误经 `SshConnectionErrorFormatter.format(stage, error)` 统一格式化为「阶段+原因」。
- 系统边界（连接参数、跳板链长度、密钥内容）用 `require` / 显式校验并抛出带信息的异常。
- 后台线程中的异常就地捕获并转为用户可见提示或日志，避免线程静默死亡。
- 诊断信息目前多用 `println("[SSH] ...")` / `println("[SFTP] ...")` 前缀输出到标准输出。

## 版本控制信息

- Git 仓库；主要提交者身份：`lhstack <lhstack@foxmail.com>`。
- 提交信息为中文，常按「新增/优化/修复/变更/文档」分类罗列，版本升级时同步更新 `plugin.xml` 的 `<version>`、`<change-notes>` 与 `README.md`、`build.gradle.kts` 的 `version`。
- 当前版本 `1.2.4`（`build.gradle.kts` 与 `plugin.xml` 一致）。

## 有证据支持的用户编码习惯（基于 lhstack 的提交与现有代码）

- 偏好把可测试的纯逻辑抽到 `util` 的 `object`，UI 面板保持薄编排层。
- 方法拆分清晰、入口方法表达高层流程，细节下沉到私有方法（如 `SshConnectionManager.connect` → `connectSession`/`applyAuthentication`/`validateEndpoint`）。
- 危险操作（删除、移动、覆盖上传、覆盖导入）统一加二次确认，并在文案中说明影响范围与不可恢复性。
- 资源释放集中在 `dispose()`，逆序关闭会话链与转发（`asReversed().forEach { close() }`）。
- 版本发布时文档（`plugin.xml`、`README.md`）与代码同步维护。

## 当前无法确认的事项

- Apache MINA sshd `setUpDefaultClient()` 在本依赖版本下对 Host Key 的默认校验策略未经实测确认，需以运行时行为为准。
- 未见 CI 配置文件，发布/签名流程仅从 `build.gradle.kts` 的 `signPlugin`/`publishPlugin`（读取环境变量）推断，具体流水线未确认。
