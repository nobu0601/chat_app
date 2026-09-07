package io.github.nobu0601.icocaautocharge.core

import android.util.Log
import io.github.nobu0601.icocaautocharge.BuildConfig

/**
 * 本アプリのログ出力口。**Log を直接呼ばず、必ずここを通す。**
 *
 * 指示書 §24 のタグを列挙で固定し、出力前に [LogRedactor] でマスクをかける。
 */
object SecureLog {

    enum class Tag(val value: String) {
        MONITOR("ICOCA_MONITOR"),
        BALANCE("ICOCA_BALANCE"),
        AUTOMATION("ICOCA_AUTOMATION"),
        PAYMENT("ICOCA_PAYMENT"),
        ERROR("ICOCA_ERROR"),
    }

    fun d(tag: Tag, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag.value, LogRedactor.redact(message))
    }

    fun i(tag: Tag, message: String) {
        Log.i(tag.value, LogRedactor.redact(message))
    }

    fun w(tag: Tag, message: String, throwable: Throwable? = null) {
        Log.w(tag.value, LogRedactor.redact(message), throwable)
    }

    /** 例外は握りつぶさず必ずここに集約する（指示書 §25）。 */
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(Tag.ERROR.value, LogRedactor.redact(message), throwable)
    }
}
