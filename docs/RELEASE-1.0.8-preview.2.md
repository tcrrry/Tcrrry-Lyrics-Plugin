# 1.0.8-preview.2 本地融合预览

## 署名显示

- “歌词偏好 → 隐藏开头署名”使用同款玻璃“开启 / 关闭”选项，默认关闭。
- 识别中文、繁体、英文、组合角色及中英紧接标签，如“词Lyricist”“制作人Music Producer”“录音师Recording Engineer”。
- 匹配歌曲标题和歌手的开头头行，且后面确有署名块时，可一并隐藏。时间密度只强化已有歌曲身份和署名证据，不按短时长、长文字直接删除。
- 播放与版本预览共用规则。正文时长、逐字时间、译文与注音关联保留；原始歌词和历史不改写。增加渲染缓存策略版本，避免沿用 preview.1 的未过滤结果。
- 遇到不认识的正文就停止；全篇只有署名时保留原样，避免输出空 TTML。

## 研究依据

以下是格式参考，未复制其实现代码，也未将其完整歌词加入发布包：

- [lyrics-merge 元数据处理](https://github.com/hxabcd/lyrics-merge/blob/main/lyrics_merge/parse/metadata.py)：QQ 歌名/歌手头行、中文/英文署名、只处理连续前缀。
- [Cadence YRC 解析](https://github.com/FPSMasterTeam/Cadence/blob/main/src/main/kotlin/top/fpsmaster/music/lyric/LyricParser.kt)：网易云部分 YRC 的 `{t,c:[{tx:...}]}` 元信息。当前本插件的逐字解析本来就跳过这种非逐字格式行，新增回归验证。
- [163MusicLyrics 格式处理](https://github.com/jitwxs/163MusicLyrics/blob/master/cross-platform/MusicLyricApp/Core/Utils/LyricUtils.cs)：存在 QQ 控制标签处理，但不能把这些格式当成所有歌词都具备的署名标记。

测试资源 `opening-credits-mixed-roles.lrc` 的署名前缀根据用户截图转录，末尾两行明确为测试占位正文，并非原歌曲歌词；另外测试组合角色、繁体、英文 byline、对话、快速歌词等。

## 播放控制融合

包含自有独立播放控制 preview.3 的源代码与 57 项策略测试，来源 ZIP SHA256：`fab8305f285ad3dc7ca7d6507044b41cc11f79c6145e12cc763c3e183b65283a`。

1609 上启用新队列旧循环清理及电台/自动扩展歌曲的原生循环按钮 OFF/ONE 切换。1606 的歌词功能保留，播放扩展不在未知接口上安装。直播、节目、视频、远程播放、随机和列表循环的边界继续保持。

**启用融合包时停用独立 `com.tcrrry.playbackintent` 插件并重启宿主**，避免同一功能重复接管；使用同一独占资源声明供宿主检测冲突。

## 设置

日语、韩语、当前歌曲粤语注音和新版本自动提醒使用玻璃分段选项，保留已有配置键。翻译原语言弹窗可搜索中文、英文、语言代码和本地名称；新版本通知按钮复用设置页玻璃样式。

仅本地预览，未发布正式 Release，未重建主项目四个安装包。自动化验证与实机验收分别记录。
