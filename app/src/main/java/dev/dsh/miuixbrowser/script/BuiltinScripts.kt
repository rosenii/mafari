package dev.dsh.miuixbrowser.script


/**
 * 推荐脚本。首次打开 app 时会自动从 userScriptUrl 拉取并安装（每个只自动装一次，之后删除不会再装回来）；
 * 去广告规则不在这里，见「设置 → 高级 → 去广告」。
 */
data class BuiltinScript(
    val name: String,
    val description: String,
    /** 直接是 .user.js 的订阅地址 */
    val userScriptUrl: String = "",
    /** 没有地址时直接加入的脚本内容 */
    val code: String = "",
)

val BUILTIN_SCRIPTS = listOf(
    BuiltinScript(
        name = "悬浮媒体播放器",
        description = "识别页面里的 M3U8 / MP4 / WebM，自动在顶部悬浮 iOS 玻璃质感播放器",
        userScriptUrl = "https://update.greasyfork.org/scripts/604191/%E6%82%AC%E6%B5%AE%E5%AA%92%E4%BD%93%E6%92%AD%E6%94%BE%E5%99%A8.user.js",
    ),
    BuiltinScript(
        name = "M3U8媒体下载器",
        description = "智能嗅探 m3u8、多线程下载，支持 AES-128 解密",
        userScriptUrl = "https://update.greasyfork.org/scripts/584352/M3U8%E5%AA%92%E4%BD%93%E4%B8%8B%E8%BD%BD%E5%99%A8%20%28%E6%94%AF%E6%8C%81AES-128%E8%A7%A3%E5%AF%86%29.user.js",
    ),
    BuiltinScript(
        name = "万能网页限制解除",
        description = "解除复制、右键、文本选择、粘贴、拖拽、键盘等网页限制",
        userScriptUrl = "https://update.greasyfork.org/scripts/586251/%E4%B8%87%E8%83%BD%E7%BD%91%E9%A1%B5%E9%99%90%E5%88%B6%E8%A7%A3%E9%99%A4.user.js",
    ),
)
