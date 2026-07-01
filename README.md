# NevolutionXposed

微信通知对话化 —— 基于 Xposed 的 WeChat 通知增强模块。

## 功能

**对话式通知** (MessagingStyle)
- 将微信通知转换为 Android `MessagingStyle` 格式
- 展开后显示多条历史消息气泡，保留对话上下文
- 群聊自动识别，与私聊分开显示

**通知渠道分离**
- 自动从微信原生渠道克隆出「群消息通知」和「私聊消息通知」两个独立渠道
- 可分别设置铃声、振动、重要性

**撤回标记**
- 撤回消息时在对话末尾追加 `↩️ 撤回一条消息` 提示
- 不会误标已有正常消息

**通知染色**
- 通知主题色设为微信绿色 `#33B332`

## 架构

```
Main.kt (Xposed 入口)
  └─ HookWeChat.kt (hook NotificationManager.notify)
       ├─ Messages.kt  (文本解析 + MessagingStyle 构建)
       └─ Channels.kt  (通知渠道路由)
```

模块拦截 WeChat 的 `NotificationManager.notify()` 调用，从通知的 `tickerText` / `text` / `title` 字段解析发送者和消息内容，构建带有完整对话历史的 `MessagingStyle` 通知。

## 前提

- Android 8.0+ (API 26)
- Magisk + LSPosed (或 Xposed 框架)

## 安装

1. 编译 APK: `./gradlew assembleDebug`
2. 在 LSPosed 中激活模块
3. 作用域勾选微信 `com.tencent.mm`
4. 重启即可生效

## 已知限制

- **回复/已读按钮**不可用 —— 新版微信不再通过标准 Android Notification Action 提供 `RemoteInput` 回复通道
- **头像**仅在通知展开时显示（MessagingStyle 系统行为）
- **CarExtender 数据源**已废弃 —— 模块不再依赖 Android Auto 协议，纯文本解析
