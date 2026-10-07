# Tcrrry Lyrics Plugin 1.0.3 正式版

发布日期：2026-10-08。

适用于 [AM++](https://github.com/Zennmn/AM-plus-plus) 的第三方独立插件，由 Tcrrry 维护。此次将独立插件源码迁移到 `tcrrry/Tcrrry-Lyrics-Plugin`，沿用 **1.0.3**，复用已通过用户实机测试的附件。未增加功能、未重新构建二进制，现有 v1.5 四种安装包及旧私人仓库的发布历史保持不变。

想直接安装完整 Apple Music APK／APKS／模块，请到 [AMPP-Lyrics 主项目主页](https://github.com/tcrrry/AMPP-Lyrics)。本下载页提供用于 AM++ 插件管理器的独立插件。

## 本版内容

- 词典注音提前生成，减少对平台请求及翻译流程的依赖。
- 撤掉1.0.1的overlay方案；发音在上、原文在中、译文在下，全部位于原生歌词整行内，辅助文字避开逐字绘制区域。
- 修复行复用、缓存、发音重叠及译文被逐字动画扫亮的问题。
- 修复冷启动菜单缺项、已有辅助轨道却被置灰、逐行译文丢失；保留用户主动隐藏的选择。
- 译文顺序保持：原生／AM++ → QQ／网易云 → 机翻。

## 安装和附件

**下载选项：主插件ZIP + 离线翻译APK。要使用离线机器翻译，必须同时安装这两个文件，并在伴随APK中下载语言模型；只导入插件ZIP不能使用离线机翻。** 已有旧版伴随APK可继续使用。平台已有译文、API翻译和词典注音不依赖该APK。

从[正式版下载页](https://github.com/tcrrry/Tcrrry-Lyrics-Plugin/releases/tag/plugin-v1.0.3)下载，在 AM++ 插件管理器导入主插件ZIP，启用后重启宿主。覆盖更新保留插件配置。无需卸载Apple Music；已有离线翻译伴随APK可继续使用，词典注音无需伴随APK或API密钥。详细步骤见[安装说明](INSTALL.md)。

| 文件 | 用途 |
| --- | --- |
| `Tcrrry-Lyrics-Plugin-1.0.3.zip` | 主插件，导入AM++插件管理器 |
| `Tcrrry-Lyrics-Offline-1.0.3.apk` | 可选离线机翻伴随服务 |
| `Tcrrry-Lyrics-Plugin-1.0.3-source.zip` | 已验收二进制对应的原始源码包 |
| `validation.json` | 原始云端验证记录 |
| `SHA256SUMS.txt` | 上述四个附件的SHA-256校验 |

主插件SHA-256：`de664c3d3ed40bb626ca006adbba8cce102f519e8b2a83856f86ca731c52d74d`。

## 验收与兼容性

**用户实机验收：** 用户已在 **AM++ 105嵌入版**（AM++1.6.3 / 113、Apple Music7.0.0-beta / 1606）测试1.0.3，反馈暂未发现问题。插件要求Android12及以上、SDK v1插件管理器。其他及后续AM++版本需另行验证。

**此前云端验证：** 共471项测试通过，另有1项跳过；Lint 0错误（engine 57条、offline 9条警告）；最终ZIP导入、重导入及配置保留检查通过；255项宿主符号、10个SDK类及42个公开方法签名核对无失败。云端没有执行真实手机播放测试。

五个附件与旧测试版逐字节相同。`validation.json`里的`deviceTests`及源码/二进制内文档保留当时的云端测试表述；本说明记录随后完成的用户实机验收和正式发布状态。没有把原始验证记录改写成云端实机测试。

## 源码与致谢

标签`plugin-v1.0.3`保留已验收源码提交`b49238ba0d300773d46c9e06bf089d5c955b60f9`，独立仓库保留插件的7个开发提交；默认`main`在该提交之上更新发布文档。原始源码包对应已验收二进制，文档更新未改变程序代码。

按[GPL-3.0](../LICENSE)分发，保留原始版权、许可证、素材来源及词典校验。感谢AM++原作者Zennmn及贡献者、原始AM++（bbelluga）、Tcrrry v1.5、AMLyricBlur及词典作者，完整说明见[第三方声明](../THIRD_PARTY_NOTICES.md)、[移植记录](MIGRATION.md)及[来源校验](provenance.json)。
