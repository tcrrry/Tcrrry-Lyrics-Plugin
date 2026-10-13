# 安装插件 1.0.6

支持 Apple Music 7.0.0-beta / 1606、1609（AM++ 1012 嵌入版）。适用于 Android 12+、支持 SDK v1 插件管理器的 AM++。1.0.4 的歌词功能基于已在 AM++ 105 嵌入版 / Apple Music 7.0.0-beta / 1606 验收的 r15。

1. 从[正式发布页](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/tag/plugin-v1.0.6)下载 `Tcrrry-Lyrics-Plugin-1.0.6.zip`。
2. 在 AM++ 插件管理器导入 ZIP，覆盖相同插件 ID，启用后彻底退出并重新打开 AM++。直接覆盖可保留插件设置；删除插件会删除其设置。
3. 进入 **Tcrrry 歌词设置**；要使用日语字词对应发音，可开启同名选项。无法可靠对齐的句子保留整行发音。
4. 需要离线机翻时，另行安装 `Tcrrry-Lyrics-Offline-1.0.3.apk`，首次打开授权并下载语言模型。旧版伴随 APK 无需更新；平台译文、API 翻译、词典注音不依赖伴随 APK。

不要把伴随 APK 导入插件管理器，也不要安装 engine 编译中间 APK。完整 AMPP-Lyrics v1.6 安装包已内置本次功能，不需要再导入插件或安装伴随 APK。

更新无需卸载已有音乐应用或清除下载歌曲。新 ZIP 与旧版使用相同插件 ID，只启用一套歌词增强，避免重复 Hook。其他宿主/设备的实测范围见 [VALIDATION.md](VALIDATION.md)。

下载后可按 `SHA256SUMS.txt` 对比每个附件；版本、测试及实际设备范围见发布附件 `validation.json` 和 [1.0.6 更新说明](RELEASE-1.0.6.md)。旧版标签、源码与安装附件继续保留。
