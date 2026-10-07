# Tcrrry Lyrics Plugin

适用于 [AM++](https://github.com/Zennmn/AM-plus-plus) 的**第三方独立歌词插件**，由 Tcrrry 维护，不是 AM++ 原作者的官方插件。采用 AM++ SDK v1，以 **AMPP Lyrics v1.5 正式版**为代码基线。当前正式版本为 **1.0.3**。

[![下载插件 ZIP｜1.0.3](docs/images/downloads/plugin-zip.svg)](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/download/plugin-v1.0.3/Tcrrry-Lyrics-Plugin-1.0.3.zip)
[![下载离线翻译 APK｜离线机翻必需](docs/images/downloads/offline-apk.svg)](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/download/plugin-v1.0.3/Tcrrry-Lyrics-Offline-1.0.3.apk)

**使用离线机翻：两个都要下载。** ZIP 导入 AM++ 插件管理器；APK 单独安装，首次打开授权并下载语言模型。已有旧版伴随 APK 可继续使用。

现有 v1.5 的普通 APK、共存 APK、APKS、模块 APK保持独立。本项目产物是插件导入 ZIP；支持插件系统的 AM++ 宿主负责运行它。它不要求插件本身拥有 root，也不包含 Apple Music 安装包。

[正式版下载页](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/tag/plugin-v1.0.3) · [安装说明](docs/INSTALL.md) · [正式发布说明](docs/RELEASE-1.0.3.md)。

**想直接安装 Tcrrry 的完整 Apple Music APK／APKS／模块？请到 [AMPP-Lyrics 主项目主页](https://github.com/tcrrry/AMPP-Lyrics)。** 本仓库提供独立插件，适合使用已具备插件管理器的 AM++ 宿主。完整安装包和插件采用独立版本；主项目现有 v1.5 安装包不因本插件发布而更新。

## 下载选择：插件与离线翻译 APK

点击主页顶部的两个按钮可直接下载。若下载失败，请在上面的正式版下载页展开 **Assets**，按需要选择：

| 下载选项 | 文件 | 何时需要 |
| --- | --- | --- |
| **主插件 ZIP（必需）** | `Tcrrry-Lyrics-Plugin-1.0.3.zip` | 导入 AM++ 插件管理器，启用歌词增强 |
| **离线翻译 APK（使用离线机翻必需）** | `Tcrrry-Lyrics-Offline-1.0.3.apk` | 单独安装到 Android，首次打开授权并下载语言模型 |

**要使用离线机器翻译，请同时下载并安装这两个文件。只导入插件 ZIP 不能运行离线机翻。** 已安装旧版伴随 APK 的用户可继续使用。平台已有译文、API 翻译和词典注音不需要此 APK。APK 应由 Android 安装器安装，不要导入插件管理器。

独立仓库保留插件分支的完整 7 个开发提交；旧私人仓库的插件测试版发布历史继续保留。1.0.3 的五个附件原样复用旧 Release，不重新构建插件 ZIP 或伴随 APK。标签 `plugin-v1.0.3` 对应已验收源码提交 `b49238ba0d300773d46c9e06bf089d5c955b60f9`；`main` 在此基础上更新发布文档。原始源码包和二进制内的文档保留当时的测试版表述，当前发布状态及后续实机验收以本仓库文档和正式 Release 为准。

1.0.3 针对原作者 **105 嵌入版（AM++ 1.6.3 / Apple Music 1606）**：保留 1.0.2 的同行辅助布局，覆盖菜单的全部弹出入口及首次页面绑定；有真实辅助文字时以插件可渲染的轨道判断开关可用性，保留主动隐藏选择；逐行歌词加入译文/发音兜底，处理原生解析为空和缓存旧句。仅更新插件，既有 v1.5 四个安装包不变。

## 交付文件

| 文件 | 用途 |
| --- | --- |
| `Tcrrry-Lyrics-Plugin-1.0.3.zip` | 在作者 AM++ 插件管理器导入、启用，重启宿主 |
| `Tcrrry-Lyrics-Offline-1.0.3.apk` | 可选离线机翻服务，安装后下载语言模型 |
| `Tcrrry-Lyrics-Plugin-1.0.3-source.zip` | 完整独立工程、SDK、词典素材与测试 |
| `validation.json` | 原始云端验证记录，实机验收说明见正式发布说明 |
| `SHA256SUMS.txt` | 下载校验 |

**已完成实机验收的宿主：AM++ 105 嵌入版**（内含 AM++ 1.6.3 / 113、Apple Music `7.0.0-beta / 1606`），用户反馈暂未发现问题。插件要求 Android 12及以上和 SDK v1 插件管理器。插件描述支持包名 `com.apple.android.music` 或 `com.tcrrry.ampplyrics.coexist`，不代表两个包名或所有后续 AM++ 版本都已实测。其他宿主版本需另行验证。普通 v1.5 APK不能仅靠导入 ZIP 获得插件系统。作者提供的模块 APK也不是一个可独立播放音乐的 Apple Music 安装包。

**验证来源：** 用户已在上述宿主完成实机测试；此前云端记录为 471 项测试通过（另有 1 项跳过）、最终 ZIP 导入/重导入与配置保留检查、宿主接口核对，以及 Lint 0 错误（保留原记录中的警告）。云端没有执行真实手机播放测试，详见 [验证范围](docs/VALIDATION.md)。

## 从 v1.5 迁移的功能

- QQ／网易云及 AM++ 适配歌词库匹配、逐字/质量偏好、逐字最高权重、候选历史、当前匹配信息、手动最佳结果与自动选源。
- 原生真实逐字优先；保留原文时间，QQ／网易云译文按文本与时间补齐；不覆盖已有可靠译文，不合并不可靠的对应行。
- API 机翻和独立密钥保存；日语、韩语、粤语离线注音；原文真实逐字时间转换为突出发音的逐字时间。
- 短单元平滑默认开启，作用于原文和可对齐发音；英语词间空格保留。
- 50–500% 辉光灵敏度、触发位置；尾字扫亮、上下句重叠；单曲循环/尾奏回拖聚焦恢复。
- 独立 Tcrrry 设置、来源单击切换（无结果继续查下一源）、双击寻找当前源的其他匹配、长按详细设置、匹配信息与自适应气泡。

**离线机翻的边界：** SDK v1 的导入包不支持注册 Activity/Provider 和装载原生库。ML Kit因而由独立伴随 APK运行，插件通过版本化 Provider请求它。QQ／网易云译文、API机翻、所有离线注音和逐字发音均不依赖该 APK。伴随应用不访问歌曲下载目录，也不暴露 API密钥或模型文件。

详细安装见 [INSTALL.md](docs/INSTALL.md)，验证和未验证范围见 [VALIDATION.md](docs/VALIDATION.md)，移植记录见 [MIGRATION.md](docs/MIGRATION.md)。

## 构建

需要 JDK 17、Android SDK Platform 37、Python 3；配置 `ANDROID_HOME` 或 `local.properties`。Gradle Wrapper、SDK JAR、词典已随源码提供。

```sh
./gradlew :engine:testDebugUnitTest :offline:testDebugUnitTest :engine:assembleRelease :offline:assembleRelease
python3 scripts/package.py
```

Windows 请优先使用 `.\gradlew.bat` 和 `python scripts/package.py`；通过本地 `local.properties` 的 `sdk.dir` 指向已安装 SDK 即可，不必修改全局环境变量。构建说明供开发使用，1.0.3 正式发布使用已经实机验收的原始附件。

`engine` 的 APK只是 DEX编译中间产物，**不要安装或分发**。打包脚本仅提取 DEX形成 `code.jar`，并把词典、宿主描述和许可证作为 ZIP assets。伴随 APK正式构建需设置 `OFFLINE_KEYSTORE`、`OFFLINE_STORE_PASSWORD`、`OFFLINE_KEY_PASSWORD`，可选 `OFFLINE_KEY_ALIAS`；它与既有 Apple Music签名完全独立。

停用/更新插件遵循 SDK 的进程重启要求。配置保存在 SDK分配的插件目录，更新 ZIP可保留；删除插件会删除插件配置。不会读取或自动搬走 v1.5 的配置、API Key及20GB离线歌曲。

本项目按 [GPL-3.0](LICENSE) 分发。感谢 [Zennmn/AM-plus-plus](https://github.com/Zennmn/AM-plus-plus) 原作者及贡献者提供宿主和 SDK，以及原始 AM++（bbelluga）、Tcrrry v1.5、AMLyricBlur 与词典项目作者。代码、SDK、词典及素材来源和许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)、[移植记录](docs/MIGRATION.md) 和 [来源校验](docs/provenance.json)。

本次覆盖更新：逐字优先查完可用逐字来源后，若均无逐字，按 Apple Music 原生 → AM++ 适配库 → QQ／网易云等第三方的质量顺序选择逐行；手动选源仍优先。译文保留原生／AM++ 当前歌词自带的可靠译文，再补 QQ／网易云，最后机翻。双击来源仅寻找当前来源其他匹配，单击换来源、长按打开设置。
