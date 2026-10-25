# AM++ 1024 / Apple Music 1613 适配

输入来自 Zennmn/AM-plus-plus 标签 `embedded-2026.08.10-r1` 的 `1024-version-com.apple.android.music-npatched.apks`。外层 ZIP 完整性与 GitHub 发布附件 SHA-256 一致。内嵌原始 APK 的二进制 Manifest 为 `com.apple.android.music / 7.0.0-beta / 1613`；内嵌模块为 AM++ 1.6.7，源码标签 `v1.6.7`，提交 `3ff083e09452a551cf6c91929e3eda9ea376d68f`。

## 精确适配

- 新增独立 1613 档案，基础条目取自该提交，插件扩展逐项检查实际 DEX。保留 1606、1609 的档案，不把未知版本当作兼容版本。
- 1613 的词适配器为 `player.x`、逐行适配器为 `player.S0`、公共适配器为 `player.g1`、行读取器为 `ttml.i`、元数据转换器为 `player.M`。播放页绑定与处理器字段分别按档案定位，完整方法签名、静态属性和字段类型均校验。
- 延续原生 ViewModel 结果提交，不直接伪造安装回调；先建立时间索引再通知页面。资料库身份校正继续要求实时媒体 ID、persistent ID 和 queue ID 一致。
- 发音／原文／译文、原文逐字高亮、原生进度跳转与聚焦恢复接入 1613 精确映射。歌词时间轴不重写。
- 1024 的 `LiquidBottomTabs-zD62xY4` 新增可选 refractionContent。按完整公开签名选择新接口，传入空的可选内容；继续兼容 1012、1017 的接口。无需向 ZIP 打包宿主的 Compose、Backdrop 或 SDK 类。
- 可选播放模式优化的命名队列 API、原生按钮绑定和资源名也对照 1613 校验；原来硬编码的按钮资源编号改为按名称解析。仍由原生管理实际队列与循环。

## 输入摘要

| 输入 | SHA-256 |
| --- | --- |
| 1024 APKS | `749526ee1240375f3d69ec1332b6843e2da41196258b1aa3f07d07bb943c33b8` |
| 原始 Apple Music APK | `3cfc3ce2e51273235413175f68652586edb4406f3de8c3989d12c29da3e47040` |
| 内嵌 AM++ 模块 APK | `4577148f50aa1e69629a29063ac97d503cd9f101fa1fdca9c201086ff10944da` |

`scripts/verify-native-lyrics-host.py` 对 1613 检查 215 项歌词、播放模式和资源契约；`verify-sdk-host.py` 对模块检查 42 项 SDK API；`verify-plugin-module.py` 检查最终 DEX 实际引用的宿主方法及公开玻璃接口。单元测试、lint、构建、打包检查和 PluginStore 导入检查另外执行。

这些检查证明静态接口与包格式，不代表真机歌词、软键盘或动画已验收。此次发布时没有连接 ADB 设备；1017 和早期预览版的验收记录不能替代 1024 实机验证。原始宿主包仅作本地验证输入，不随歌词插件发布。
