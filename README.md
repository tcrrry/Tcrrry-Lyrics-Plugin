# Tcrrry Lyrics Plugin

适用于 [AM++](https://github.com/Zennmn/AM-plus-plus) SDK v1 的第三方独立歌词插件，由 Tcrrry 维护。当前正式版本 **1.0.4**，针对 Apple Music **7.0.0-beta / 1606**。

[![下载插件 ZIP｜1.0.4](docs/images/downloads/plugin-zip.svg)](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/download/plugin-v1.0.4/Tcrrry-Lyrics-Plugin-1.0.4.zip)
[![下载离线翻译 APK](docs/images/downloads/offline-apk.svg)](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/download/plugin-v1.0.4/Tcrrry-Lyrics-Offline-1.0.3.apk)

[1.0.4 发布页](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/tag/plugin-v1.0.4) · [安装说明](docs/INSTALL.md) · [更新说明](docs/RELEASE-1.0.4.md) · [完整安装包主页](https://github.com/tcrrry/AMPP-Lyrics)

**离线机器翻译需要 ZIP 和伴随 APK 两个文件。** ZIP 导入 AM++ 插件管理器；APK 单独安装并下载语言模型。伴随应用仍为 **1.0.3**，已有安装可继续使用。平台译文、API 翻译与词典注音不依赖伴随 APK。

## 1.0.4 更新

- 新增可选的**字词对应发音**：日语发音放在对应原文上方，处理自带发音、词典补注和多行排版。无法可靠对齐的句子保留整行发音。
- 修复多行歌词提前大面积亮起、后半句不亮而在句末突然补齐，以及字母、英文尾词提前亮完的问题；保留原生真实逐字时间。
- 优化换行、发音重叠、孤立字与行距不统一的情况；字词对应模式默认关闭，可在 Tcrrry 歌词设置中开启。
- 单曲循环恢复第一句，使用短距离过渡动画，避免遍历整首歌词产生数秒卡顿；可单独关闭循环复位。
- 修复自传歌曲资料库 ID 覆盖播放身份后出现的长时间加载、已匹配但不显示、歌词停在某句的问题。核对实时播放队列身份后恢复页面绑定，保留资料库状态。
- 保存用户主动选择的发音显示状态；修复部分原生辅助文字与转义 TTML 被当作正文显示的问题。

原有 QQ／网易云及 AM++ 适配歌词库、多源匹配、手动切源、匹配历史、查看当前匹配信息、翻译、离线注音、辉光与短单元平滑功能继续保留。新增设置位于独立 **Tcrrry 歌词设置**。

## 下载与安装

| 文件 | 用途 |
| --- | --- |
| `Tcrrry-Lyrics-Plugin-1.0.4.zip` | 在支持 SDK v1 的 AM++ 插件管理器导入并启用，随后重启 AM++ |
| `Tcrrry-Lyrics-Offline-1.0.3.apk` | 离线机翻伴随应用，单独安装，首次打开授权并下载模型 |
| `Tcrrry-Lyrics-Plugin-1.0.4-source.zip` | 对应源码、SDK、词典资源、许可证与测试 |
| `validation.json` / `SHA256SUMS.txt` | 验证范围与附件校验 |

更新时导入新 ZIP 覆盖同一插件 ID，可保留插件配置；删除插件会删除其配置。不要把伴随 APK 导入插件管理器。建议仅启用一套歌词增强，避免重复挂载独立插件与整合包内置功能。

想直接安装完整 Apple Music 增强版，请下载 [AMPP-Lyrics v1.6](https://github.com/tcrrry/AMPP-Lyrics/releases/tag/v1.6) 的普通 APK、共存 APK、APKS 或模块。完整安装包已移植本次修复，无需再导入本插件；没有插件管理器的旧整合包不能通过 ZIP 获得插件系统。

## 兼容性与验证

插件要求 Android 12+、SDK v1 插件管理器，精确适配 Apple Music `7.0.0-beta / 1606`。声明的包名为 `com.apple.android.music` 和 `com.tcrrry.ampplyrics.coexist`。

本次功能基于已在 vivo V2359A / AM++ 105 嵌入版验收的 r15 代码。实机覆盖自传歌曲恢复、日语字词对应发音、英文多行高亮、进度拖动与单曲循环回首句；正式版另执行编译、单元测试和最终 ZIP 导入检查。共存包名及其他宿主/设备不等于已经全面实测；少数无法可靠对齐的发音仍保留整行显示。详细范围见 [VALIDATION.md](docs/VALIDATION.md)。

## 构建

需要 JDK 17、Android SDK Platform 37、Python 3。优先使用项目 Gradle Wrapper；Windows 通过 `local.properties` 指向现有 SDK。

```sh
./gradlew :engine:testDebugUnitTest :offline:testDebugUnitTest :engine:assembleRelease
python scripts/package.py --plugin-only
python scripts/verify-package.py dist/Tcrrry-Lyrics-Plugin-1.0.4.zip
```

`engine` APK 是 DEX 编译中间产物，不用于安装或分发。SDK v1 无法装载 ML Kit 原生库，离线机翻因此由独立伴随 APK 提供。所有插件 Hook 通过宿主 SDK 注册，配置和缓存使用插件独立目录。

项目按 [GPL-3.0](LICENSE) 分发。感谢 AM++、Tcrrry AMPP Lyrics v1.5、AMLyricBlur 与词典项目作者。完整来源、许可证与固定资源校验见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)、[MIGRATION.md](docs/MIGRATION.md) 和 [provenance.json](docs/provenance.json)。旧发布、标签和附件继续保留。
