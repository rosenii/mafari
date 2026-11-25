/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.translate

/**
 * 翻译功能的公共类型。整个 translate 包只依赖 JDK + org.json（不碰 Android 类），
 * 所以能在 JVM 单元测试里直接跑（见 TranslateSourcesTest）。
 */

/** 翻译源 id：写进 SharedPreferences，改名会让旧设置失效，别改。 */
object TranslateSourceId {
    const val GOOGLE = "google"
    const val BING = "bing"
    const val CUSTOM = "custom"

    /** 设置 / 下拉里的显示顺序 */
    val ALL = listOf(GOOGLE, BING, CUSTOM)

    const val DEFAULT = BING
}

/** 一次翻译所需的全部配置（设置里读出来的快照；翻译进行中改设置不影响已开始的页面）。 */
data class TranslateConfig(
    val sourceId: String = TranslateSourceId.DEFAULT,
    /** 目标语言（见 [TranslateLangs]） */
    val target: String = TranslateLangs.DEFAULT_TARGET,
    /** 自定义源（LibreTranslate 兼容）的服务地址，例如 https://libretranslate.example.com */
    val customUrl: String = "",
    val customKey: String = "",
)

/** 失败原因：界面按 [kind] 翻成用户能看懂的文案，[detail] 是附带的技术细节（HTTP 状态码、服务端消息等）。 */
class TranslateException(val kind: Kind, val detail: String = "", cause: Throwable? = null) :
    Exception("$kind${if (detail.isNotEmpty()) ": $detail" else ""}", cause) {
    enum class Kind {
        /** 连不上 / 超时 */
        NETWORK,

        /** 服务端返回非 2xx（含限流） */
        HTTP,

        /** 取 token / key 无效 */
        AUTH,

        /** 返回内容解析不了或条数对不上 */
        PARSE,

        /** 配置不完整（比如自定义源没填地址） */
        CONFIG,
    }
}

/** 一个翻译源：把一批文本译成目标语言。阻塞调用，必须在后台线程上用。 */
interface TranslateSource {
    val id: String

    /**
     * @param texts 待译文本（非空；每条不超过服务限制，长文本由 [TranslateService] 先切好）
     * @param to 目标语言（[TranslateLangs] 里的代码）
     * @return 与 [texts] 等长、同序的译文
     * @throws TranslateException
     */
    fun translate(texts: List<String>, to: String): List<String>
}

/** 可选的目标语言。名称用该语言自己的写法，不随界面语言变，所以不需要 i18n。 */
object TranslateLangs {
    class Lang(val code: String, val name: String)

    const val DEFAULT_TARGET = "zh-CN"

    val ALL = listOf(
        Lang("zh-CN", "简体中文"),
        Lang("zh-TW", "繁體中文"),
        Lang("en", "English"),
        Lang("ja", "日本語"),
        Lang("ko", "한국어"),
        Lang("fr", "Français"),
        Lang("de", "Deutsch"),
        Lang("es", "Español"),
        Lang("pt", "Português"),
        Lang("it", "Italiano"),
        Lang("ru", "Русский"),
        Lang("ar", "العربية"),
        Lang("vi", "Tiếng Việt"),
        Lang("th", "ไทย"),
        Lang("id", "Bahasa Indonesia"),
        Lang("tr", "Türkçe"),
    )

    fun isSupported(code: String) = ALL.any { it.code == code }

    /** 设置里读出来的值不认识就回退默认 */
    fun normalize(code: String?): String = ALL.firstOrNull { it.code.equals(code, true) }?.code ?: DEFAULT_TARGET

    /** 按系统语言挑一个合理的默认目标语言（首次使用） */
    fun fromLocale(language: String, country: String): String = when {
        language.equals("zh", true) -> if (country.equals("TW", true) || country.equals("HK", true) || country.equals("MO", true)) "zh-TW" else "zh-CN"
        else -> ALL.firstOrNull { it.code.equals(language, true) }?.code ?: "en"
    }
}
