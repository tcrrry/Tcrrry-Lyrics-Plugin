# Tcrrry 歌词增强

当前正式版 **1.0.10**，适用于 AM++ SDK v1 / Apple Music 7.0.0-beta 1606、1609，已适配 AM++ 1017。

[![下载歌词插件 ZIP](docs/images/downloads/plugin-zip-1.0.10.svg)](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/download/plugin-v1.0.10/Tcrrry-Lyrics-Plugin-1.0.10.zip)
[![下载翻译插件 ZIP](docs/images/downloads/translation-zip.svg)](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/download/plugin-v1.0.9/Tcrrry-Translation-Plugin-1.0.0.zip)

[1.0.10 发布页](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/tag/plugin-v1.0.10) · [安装说明](docs/INSTALL.md) · [翻译扩展](docs/TRANSLATION-EXTENSION.md) · [完整安装包](https://github.com/tcrrry/AMPP-Lyrics)

只需要歌词功能，导入歌词 ZIP 即可。需要离线机器翻译，再导入并启用翻译 ZIP；两个文件都在 AM++ 插件管理器导入，**无需安装翻译 APK**。中文模型内置，其他语言按需下载；下载语言包需要开启代理，下载完成后可离线使用。

## 1.0.10 更新

- 设置首页移除顶部的插件名称、渠道及版本号，直接展示当前歌曲与设置。
- 修复进度跳转时原生动画缺失、歌词残留及旧回调覆盖，补上播放中的小幅跳转、间奏定位、暂停跳转和单曲循环恢复。
- 修复偶发高亮及跟随丢失，保护原生触摸监听，精简新歌词通道下多余的逐帧工作。
- 原生译文优先，平台译文其次；离线与网络补全独立运行，缓存和任务合并减少重复等待。
- 修复歌词来源滚动文字切源后重叠，重排来源操作，操作后保持当前窗口；更多工具与操作说明分别保留。
- 可选翻译插件继续使用 1.0.0，本次只更新歌词插件；主项目尚未同步本版。

## 1.0.9 已有功能

- 1609 歌词改由原生 ViewModel 结果通道提交，先建立时间索引再通知原生界面，减少重复刷新，保留来源切换与过期请求检查。
- 修复韩语、粤语逐字注音触发率低、平台拼音写法识别和失败缓存不重试；参考日语的成对排版，保持注音字号，按需增加原文字间空间。
- 保留日语字词注音、发音／原文／译文顺序和仅原文逐字高亮；沿用宿主原有分行，不人为拼接歌词行。
- 修复混合英文中文歌词的繁体转简体、设置切换后缓存展示及原始语言标记；日语原文不进行繁简转换。
- 离线翻译拆为独立可选插件，歌词 ZIP 不含翻译引擎和模型。翻译面板提供搜索、已下载语言包管理、检测离线翻译及真实下载进度或状态。
- 修复语言搜索不能唤起软键盘和离线翻译入口闪退；设置顺序、玻璃控件兼容 AM++ 1017。

原有多来源匹配、平台译文、API 翻译、离线词典注音、辉光、短单元平滑、缓存管理、播放控制与更新检查保留。辉光和短单元平滑不是新注入通道的必要条件，按个人偏好设置。

## 安装与升级

1. 在 AM++ 插件管理器导入 `Tcrrry-Lyrics-Plugin-1.0.10.zip`。
2. 需要离线机翻时，再导入 `Tcrrry-Translation-Plugin-1.0.0.zip` 并启用。
3. 自行完全退出并重新打开宿主，在「翻译与注音」下载所需原语言模型。

覆盖同一插件 ID 保留歌词设置。旧伴随 APK 和旧试验包的数据不会被删除；新翻译扩展独立存储语言包，其他语言可能需要重新下载。平台译文、API 翻译与词典注音不依赖翻译扩展。启用内置播放控制时，请停用独立播放控制插件。

## 验证与边界

preview.15 的韩语、粤语逐字注音已经用户反馈可用。自动检查覆盖韩语两首歌96句、粤语40句及不同原生分组的原字号排版；样本通过不代表所有歌曲都能可靠对应。无法可靠对齐时保留整行发音，不伪造逐字时间。日语回归、翻译协议、包结构和作者 PluginStore 导入纳入检查。

最终正式包的验证结果及 SHA-256 见发布附件。少数无正文歌曲持续加载仍未解决，保留[异常记录](docs/NO-LYRICS-SPINNER-1807802486.md)。历史正式版和翻译 APK 保留在[旧发布](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases)。

## 构建

使用项目 Gradle Wrapper：`./gradlew :engine:testDebugUnitTest :translation-plugin:testDebugUnitTest :offline-runtime:testDebugUnitTest packagePlugin packageTranslation`。
主 ZIP 不携带 SDK、宿主或翻译运行时；所有 Hook 均通过 SDK，配置和缓存位于插件自己的目录。翻译 ZIP 的内部 APK 只是动态加载的引擎资源，不需要安装。
