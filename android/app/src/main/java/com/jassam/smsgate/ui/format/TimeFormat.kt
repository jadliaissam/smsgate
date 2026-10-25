package com.jassam.smsgate.ui.format

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jassam.smsgate.R
import kotlinx.coroutines.delay

/**
 * The current time, refreshed every [periodMs] while the screen is started (the
 * dashboard's useNow), so relative times stay fresh without running in the background.
 */
@Composable
fun rememberNow(periodMs: Long = 30_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, periodMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(periodMs)
            }
        }
    }
    return now
}

object TimeFormat {
    /** Within this of now (or in the future: clocks differ) a past event reads "just now". */
    const val JUST_NOW_MS = 5_000L

    /** "just now", "5 min. ago", "yesterday"… in the phone's locale (web sinceTime). */
    fun since(context: Context, atMs: Long, nowMs: Long): String =
        if (atMs > nowMs - JUST_NOW_MS) {
            context.getString(R.string.time_just_now)
        } else {
            DateUtils.getRelativeTimeSpanString(atMs, nowMs, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        }

    /** Full local date and time (web absoluteTime). */
    fun absolute(context: Context, atMs: Long): String =
        DateUtils.formatDateTime(
            context,
            atMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )

    /** Time of day only, for timeline steps today. */
    fun time(context: Context, atMs: Long): String = DateUtils.formatDateTime(context, atMs, DateUtils.FORMAT_SHOW_TIME)
}

@Composable
fun sinceText(atMs: Long, nowMs: Long): String = TimeFormat.since(LocalContext.current, atMs, nowMs)

@Composable
fun absoluteText(atMs: Long): String = TimeFormat.absolute(LocalContext.current, atMs)
