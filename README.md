# Tcrrry Lyrics Plugin

独立的 AM++ SDK v1 歌词插件，以 **AMPP Lyrics v1.5 正式版**为基线。插件版本独立编号，当前 **1.0.1**，保留 1.0.0 历史。

现有 v1.5 的普通 APK、共存 APK、APKS、模块 APK保持独立。本项目产物是插件导入 ZIP；支持插件系统的 AM++ 宿主负责运行它。它不要求插件本身拥有 root，也不包含 Apple Music 安装包。

私仓下载页：[完整插件 1.0.1](https://github.com/tcrrry/AM-plus-plus-Desktop-Lyrics/releases/tag/plugin-v1.0.1)。独立工程位于 `plugin/main` 分支。

1.0.1 针对原作者 **105 嵌入版（AM++ 1.6.3 / Apple Music 1606）**：发音留在歌词容器内，使用独立绘制层避开原文高亮；普通刷新保留发音；词典注音先于网络译文完成。仅更新插件，既有 v1.5 四个安装包不变。

## 交付文件

| 文件 | 用途 |
| --- | --- |
| `Tcrrry-Lyrics-Plugin-1.0.1.zip` | 在作者 AM++ 插件管理器导入、启用，重启宿主 |
| `Tcrrry-Lyrics-Offline-1.0.1.apk` | 可选离线机翻服务，安装后下载语言模型 |
| `Tcrrry-Lyrics-Plugin-1.0.1-source.zip` | 完整独立工程、SDK、词典素材与测试 |
| `SHA256SUMS.txt` | 下载校验 |

**兼容范围：** Android 12及以上；Apple Music `7.0.0-beta / 1606`；包名 `com.apple.android.music` 或 `com.tcrrry.ampplyrics.coexist`；宿主必须具备作者 SDK v1 的插件管理器。普通 v1.5 APK不能仅靠导入 ZIP 获得插件系统。作者提供的模块 APK也不是一个可独立播放音乐的 Apple Music 安装包。

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

`engine` 的 APK只是 DEX编译中间产物，**不要安装或分发**。打包脚本仅提取 DEX形成 `code.jar`，并把词典、宿主描述和许可证作为 ZIP assets。伴随 APK正式构建需设置 `OFFLINE_KEYSTORE`、`OFFLINE_STORE_PASSWORD`、`OFFLINE_KEY_PASSWORD`，可选 `OFFLINE_KEY_ALIAS`；它与既有 Apple Music签名完全独立。

停用/更新插件遵循 SDK 的进程重启要求。配置保存在 SDK分配的插件目录，更新 ZIP可保留；删除插件会删除插件配置。不会读取或自动搬走 v1.5 的配置、API Key及20GB离线歌曲。

GPL-3.0；原始 AM++与 v1.5 代码作者及第三方许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本次覆盖更新：逐字优先查完可用逐字来源后，若均无逐字，按 Apple Music 原生 → AM++ 适配库 → QQ／网易云等第三方的质量顺序选择逐行；手动选源仍优先。译文保留原生／AM++ 当前歌词自带的可靠译文，再补 QQ／网易云，最后机翻。双击来源仅寻找当前来源其他匹配，单击换来源、长按打开设置。
