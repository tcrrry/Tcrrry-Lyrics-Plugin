# Tcrrry 歌词增强 1.4.3

适配 AM++ 1.7.1／Apple Music 7.0.3（1626），并保留旧版宿主支持。

### 修复歌词页连续无限转圈（`LiveData.mDispatchingValue` 锁死恢复）
- 修复歌词结果通道（`mLyricsResult`）在界面切换瞬间遭遇观察者异常并被捕获后，因 AndroidX `LiveData.dispatchingValue()` 缺少 `finally` 复位导致 `mDispatchingValue` 永久卡在 `true`、后续切歌每首都无限转圈的问题。
- 在 `LyricsResultSubmissionBridge` 中加入顶层 `LiveData` 分发锁检测与自动清理（嵌套重入时不误清），并在观察者异常且宿主尚未确认装载时自动延迟补发；同时在诊断日志中解包 `InvocationTargetException` 输出真实异常根因与栈顶位置。

### 加固宿主内置桥接协作与状态同步
- 加固 `HostLyricsSubmissionGuard`，在插件自身提交期间及匹配复用指针时避免宿主内置 AM++ 歌词桥重入干扰。
- 修复重复刷新同文本歌词或回退原生歌词后，菜单仍显示「来源尚未确认」、设置页仍显示「歌词已准备好，等待歌词页装载」的问题。

在 AM++ 插件管理里导入 **Tcrrry-Lyrics-Plugin-1.4.3.zip**（覆盖升级并保留设置）；离线翻译扩展保持 **1.0.1** 无需重装。
