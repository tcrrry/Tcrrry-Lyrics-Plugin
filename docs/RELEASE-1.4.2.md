# Tcrrry 歌词增强 1.4.2 & 离线翻译 1.0.1

适配 AM++ 1.7.1／Apple Music 7.0.3（1626），并保留旧版宿主支持。

### Apple Music 7.0.3 稳定性修复（内置资源兜底 + 翻译扩展隔离）
- 歌词增强 **1.4.2** 内置 `HostResourceRecovery`（通过 AM++ Plugin SDK v1 Hook），当原版 AM++ 1.7.1 + Apple Music 7.0.3 出现 `Can't convert to ComplexColor: type=0x5` 资源表异常时，自动从宿主私有独立 `AssetManager` 兜底恢复 `getColor`、`getColorStateList`、`getBoolean`、`getDrawableForDensity`、`TypedArray.getColor` 与 `TypedArray.getColorStateList`，大幅降低高版本 7.0.3 闪退风险。
- 离线翻译 **1.0.1** 改用全新独立的 `AssetManager` 加载 `runtime.apk` 资源，彻底移除对进程全局 `Resources.getSystem().assets` 的 `addLoaders` 污染，修复歌词增强与离线翻译同时开启时易闪退的问题。

### 专辑页封面／内容下载按钮修复（Apple Music 7.0.3 / 1626）
- 修复 1626 Compose 专辑页因父类混淆映射偏移导致 `AlbumPageFragment` 未命中 `onCreateView` Hook 的问题，改为沿继承链动态定位真实 `onCreateView`（命中 `Yb.f`），并补充标题锚点宽限期后的安全兜底定位，恢复专辑封面及内容下载入口。

### 播放页 `...` 更多菜单优化
- 隐藏当前歌曲不可用或已下载时的灰显无图标「下载」空占位按钮，并补全「添加到资料库」`+` 图标渲染。

在 AM++ 插件管理里导入 **Tcrrry-Lyrics-Plugin-1.4.2.zip**（覆盖升级并保留设置）；需要离线翻译时请同步升级导入 **Tcrrry-Translation-Plugin-1.0.1.zip** 并完全重启宿主。
