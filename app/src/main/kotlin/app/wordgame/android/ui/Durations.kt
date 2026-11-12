package app.wordgame.android.ui

import app.wordgame.android.R
import app.wordgame.android.Strings

/** A thinking time, as precise as useful: "45 s", "1 min 20", "12 min", "3 h 05", "2 j 4 h". */
fun duration(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        seconds < 60 -> Strings.get(R.string.duration_seconds, seconds.toInt())
        minutes < 10 -> Strings.get(R.string.duration_minutes_seconds, minutes.toInt(), (seconds % 60).toInt())
        minutes < 60 -> Strings.get(R.string.duration_minutes, minutes.toInt())
        hours < 24 -> Strings.get(R.string.duration_hours, hours.toInt(), (minutes % 60).toInt())
        else -> Strings.get(R.string.duration_days, (hours / 24).toInt(), (hours % 24).toInt())
    }
}
