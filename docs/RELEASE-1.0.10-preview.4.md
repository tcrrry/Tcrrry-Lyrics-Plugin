# 1.0.10-preview.4 — 播放页审查与原生高亮恢复

## 审查结论

- 1609/1017 使用 ViewModel 结果通道，旧 Fragment 重装、LyricsContentTransition、NativeLyricsImmediateAnchor/SmoothReturn、重放元数据与重试锚定位于互斥的旧宿主分支，没有和新插入通道同时运行。保留旧分支兼容，不重新启用其中的时钟/滚动劫持。
- 当前模糊恢复层仍独立运行。此前只检测当前句出屏，漏掉原生动画/浏览状态异常但句子仍在屏幕内的情况；新通道没有安装旧 Anchor，所以原恢复会退化成只滚动 RecyclerView，无法恢复原生状态。
- Native 1609 合同核对：A.P(boolean) 写动画门 A；A.C() 读浏览门 B；Fragment.L.b(first,last) 开启原生动画、退出浏览状态、调用原生锚定并更新 ViewModel 的当前行。A.N() 解除旧行去重限制。现有 DEX 审计路径 ruby-device-check/A.dexdump.txt、scroll-callback.dump.txt。

## 修改

- 新 NativeLyricsPresentationRecovery 仅在精确 1609 配置下启用，沿用一个 750ms 的可见页检查，不增加时钟轮询；检测持续 2 秒的出屏或原生门异常，恢复原生行回调和高亮。门异常才重绑定一次，健康出屏不重建字词视图。
- 保护条件：原生/模糊文档指针与适配器文档一致，窗口可见并有焦点，仍在播放，RecyclerView 完全空闲，当前非间奏，用户操作后保留 3.5 秒浏览宽限，任务投递后复核页面/文档/当前行。
- 删除直接 smoothScrollToPosition 的降级；恢复失败保留原生行为，不再制造只有列表滚动、状态未恢复的假象。
- 不再覆盖/清空 View.OnTouchListener；通过 SDK 观察 RecyclerView 自身 onInterceptTouchEvent/onTouchEvent，保持原参数和返回结果。只处理当前歌词列表，不全局钩 ViewGroup 触摸。
- 从逐帧模糊路径移除重复的跟随检查，仅由既有检查任务/用户浏览结束触发。
- 清理模糊只清 RenderEffect，不再强制原生行 alpha=1。
- 删除 NativeTerminalGradientFix 中诊断关闭后仍创建的逐动画帧跟踪对象和 onAnimationUpdate 观察钩。保留 CJK 句尾羽化补偿、延迟遮罩处理、字词尾部保护。

## 保留

短单元平滑作用于输入计时单元，辉光作用于播放位置，两者仍有独立作用；不改变已保存设置。日语/韩语/粤语对齐、完整注音字号、原生换行、发音/原文/译文顺序、仅原文高亮全部保留。上一版五字按钮和保持来源窗口继续保留。主项目和正式版本不修改。

## 验证边界

手机通过 ADB -P 5038 已连接；读取时媒体处于暂停，未在真实播放中复现偶发异常。临时诊断日志标签已恢复为空。回归测试模拟原生动画门关闭、当前句仍可见、健康出屏、暂停/拖动/惯性滚动/隐藏/失去窗口焦点/旧文档/停用以及原触摸监听与透明度保留。桌面检查不能证明该偶发问题已在真机彻底消失。
