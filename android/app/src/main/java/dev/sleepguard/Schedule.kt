package dev.sleepguard

import android.content.Context
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 時間割。変えたいときはここだけ触ればよい。
 * 時刻は端末のタイムゾーン設定(現地時間)で判定する。
 */
object Schedule {
    // 時刻は HHMM の数値
    const val BATH_START = 2200       // 入浴通知の開始
    const val WARN = 50               // 0:50 予告
    const val LATE_START = 100        // 1:00 以降、使っていたら通知 → ロック
    const val LATE_END = 600          // 6:00 で終了(この時刻は含まない)
    const val LATE_HOME_START = 200   // 1時以降に帰宅したら、2:00 からロックの対象にする

    const val BATH_INTERVAL_MS = 15 * 60_000L   // 入浴通知の再表示間隔
    const val LOCK_GRACE_MS = 10 * 60_000L      // 1時以降の通知から画面ロックまでの猶予
    const val ASK_TIMEOUT_MS = 15_000L          // 確認画面が出なかったとみなしてロックするまで
    const val GATE_IDLE_MS = 10_000L            // 確認画面で操作がなければロックするまで
    const val EMERGENCY_WAIT_MS = 30_000L       // 緊急解除のカウントダウン
    const val EMERGENCY_MS = 30 * 60_000L       // 緊急解除してからロックしない時間
    const val GO_OUT_WAIT_MS = 10_000L          // 「今夜は外出」の確認のカウントダウン

    fun hm(t: LocalDateTime) = t.hour * 100 + t.minute

    /** 1時〜6時。外出モードに入ること、停止すること、音声のアプリを変えることはできない。 */
    fun isLate(t: LocalDateTime) = hm(t) in LATE_START until LATE_END

    fun canGoOut(t: LocalDateTime) = !isLate(t)

    fun canStop(t: LocalDateTime) = !isLate(t)

    /** 6時〜翌朝6時を一つの「夜」として扱い、22時側の日付を返す。 */
    fun nightOf(t: LocalDateTime): LocalDate =
        if (hm(t) < LATE_END) t.toLocalDate().minusDays(1) else t.toLocalDate()

    /** 次に時間割を評価するまでの時間。22時〜6時は毎分0秒すぎ、日中は22時まで空ける。 */
    fun untilNextTick(t: LocalDateTime): Duration {
        val next = if (hm(t) in LATE_END until BATH_START)
            t.toLocalDate().atTime(BATH_START / 100, BATH_START % 100)
        else
            t.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        return Duration.between(t, next).plusMillis(500)
    }
}

/** State の保存先。未保存のキーは "" と 0 を返す。 */
interface Store {
    fun getString(key: String): String
    fun putString(key: String, value: String)
    fun getLong(key: String): Long
    fun putLong(key: String, value: Long)
}

private class PrefsStore(ctx: Context) : Store {
    private val p = ctx.getSharedPreferences("sleepguard", Context.MODE_PRIVATE)
    override fun getString(key: String) = p.getString(key, "") ?: ""
    override fun putString(key: String, value: String) = p.edit().putString(key, value).apply()
    override fun getLong(key: String) = p.getLong(key, 0)
    override fun putLong(key: String, value: Long) = p.edit().putLong(key, value).apply()
}

/** 夜ごとの状態。SharedPreferences に保存する。 */
class State(private val p: Store) {
    constructor(ctx: Context) : this(PrefsStore(ctx))

    /** 「開始」で true、「停止」で false。false の間は時間割を評価しない。 */
    var enabled: Boolean
        get() = p.getString("enabled") == "1"
        set(v) = p.putString("enabled", if (v) "1" else "")

    fun bathDone(night: LocalDate) = p.getString("bathDone") == night.toString()
    fun setBathDone(night: LocalDate) = p.putString("bathDone", night.toString())

    /** 外出モードにした夜。 */
    fun outing(night: LocalDate) = p.getString("outing") == night.toString()
    fun setOuting(night: LocalDate) = p.putString("outing", night.toString())
    fun clearOuting() = p.putString("outing", "")

    /** 1時以降に「帰宅した」を押した夜。 */
    fun lateHome(night: LocalDate) = p.getString("lateHome") == night.toString()
    fun setLateHome(night: LocalDate) = p.putString("lateHome", night.toString())

    var lastBathNotify: Long
        get() = p.getLong("lastBath")
        set(v) = p.putLong("lastBath", v)

    /** 入浴の通知を出したままの夜。0時を過ぎたら消すために覚えておく。 */
    var bathShownNight: String
        get() = p.getString("bathShown")
        set(v) = p.putString("bathShown", v)

    /** その夜に一度だけ true を返す。 */
    fun once(key: String, night: LocalDate): Boolean {
        if (p.getString("once:$key") == night.toString()) return false
        p.putString("once:$key", night.toString())
        return true
    }

    /** 確認画面を出そうとした時刻。確認画面が出たか、使うのをやめたら 0 に戻す。 */
    var askedAt: Long
        get() = p.getLong("askedAt")
        set(v) = p.putLong("askedAt", v)

    /** 緊急解除でロックしない期限。 */
    var emergencyUntil: Long
        get() = p.getLong("emergencyUntil")
        set(v) = p.putLong("emergencyUntil", v)

    /** 最後に緊急解除を使った夜と、その夜に使った時刻("2026-10-24 2:14 3:05")。 */
    val emergencyLog get() = p.getString("emergencyLog")
    fun addEmergency(night: LocalDate, time: String) {
        val log = emergencyLog
        p.putString("emergencyLog", if (log.startsWith("$night ")) "$log $time" else "$night $time")
    }

    /** 「音声を聴く」で開くアプリのパッケージ名。アプリ画面で選ぶ。選んでいなければ ""。 */
    var soundApp: String
        get() = p.getString("soundApp")
        set(v) = p.putString("soundApp", v)

    /** 1時以降に初めて使っていた時刻(その夜のもの)。 */
    fun lateNight() = p.getString("lateNight")
    fun lateSince() = p.getLong("lateSince")
    fun startLate(night: LocalDate, now: Long) {
        p.putString("lateNight", night.toString())
        p.putLong("lateSince", now)
    }
}
