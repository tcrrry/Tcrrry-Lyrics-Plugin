# 移植边界与来源

基线：`tcrrry/AMPP-Lyrics`正式 `v1.5`，公开提交 `c6a7f831da788efa01a2ca6c817a4d92b38f4fda`；本地等价 release树 `9e07f336e6db6395c2e0d4cc3c4d0aa410779057`。未从早期0.1兼容原型替代完整功能。

作者 SDK：用户上传 `AM-plus-plus-plugin-system-v1-source.zip`，SHA-256 `ff52cdd1a8a6c3b90676289bb3d963d607707ebce1f6a82fd9d8a2995688af24`。已重新导出并比较10个API类，随项目SDK JAR公开签名相同；SDK仅 compileOnly，不进入插件 DEX。

| 范围 | 本插件处理 |
| --- | --- |
| 歌词匹配、来源排名、缓存、保留最佳、译文融合 | 保留 v1.5实现，重定位命名空间 |
| 注音及突出发音的真实逐字、平滑、辉光、聚焦 | 保留 v1.5实现，SDK注册Hook |
| Tcrrry歌词设置 | 独立原生 View设置会话/对话框，保留设置键 |
| 加载、停止、删除、资源 | SDK管理Hook，自己的任务/控件清理，SDK数据/cache目录 |
| Kuromoji/韩粤资源 | PluginContext.openAsset；不借用宿主 ClassLoader资源 |
| ML Kit原生运行时 | 独立、可选伴随APK；Provider协议1；宿主既有数据保持独立 |
| 玻璃UI、平板双栏、原生设置修复、宿主元数据修复 | 宿主职责，不安装这些功能的Hook；依赖闭包保留的辅助源码不意味着启用它们 |

代码命名空间改为 `com.tcrrry.lyrics.engine/host/metadata/sources`，避免 SDK父加载器把插件类绑定到宿主旧版同名类。SDK API仍保持作者原包名，仅由宿主提供。

来源事实：原文选源与辅助补齐分开。当前原文已有的可靠译文优先，QQ／网易云仅补缺，再由用户启用的机翻补缺。AM++适配库仍作为完整TTML来源获取；不声称SDK v1提供了独立的“AM++译文查询接口”。

更新包独立版本 `1.0.2`，既有正式App仍为 `v1.5`，后续插件版本不会改变四个安装包的版本/签名。

伴随应用首次打开向两个已知宿主授予仅用于连接可见性的 URI 许可；插件确认
协议后保留许可，以免重启后失联。Provider自身仍校验调用UID对应包名，query/
insert/update/delete不提供数据，授权不暴露歌曲/模型/密钥文件。无需修改宿主manifest。
