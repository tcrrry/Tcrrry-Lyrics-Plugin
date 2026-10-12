# Apple Music 1609 适配依据

输入为 Zennmn/AM-plus-plus 发布标签 embedded-2026.08.10-r1 的 1012-version-com.apple.android.music-npatched.apks。二进制 Manifest 为 com.apple.android.music / 7.0.0-beta / 1609。

基础档案源自上游提交 3547a833dac819a23b8515784601873d1d27ed47 的 host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1609.json，按本插件实际索引裁剪。完整输入摘要见 provenance.json 和新档案 evidence。

上游的新基础档案不能直接替代本插件的原生歌词扩展：这些扩展另依赖列表绑定、词级遮罩、资料库身份恢复和原生发音配置。新增 pluginLyricsCompatibility 只为精确 1609 选择对应类与字段；1606 的已验收映射和档案不变。

scripts/verify-native-lyrics-host.py 直接检查原始 APK DEX 的方法、字段类型与静态属性和资源，不执行 APK。原始 APK 不随源码或插件分发。该检查覆盖静态接口，不能证明新版本动画在真机正常。
