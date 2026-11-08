package app.wordgame.android

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Texts for code outside composables (view models, error messages, the solo backend), in the app's current
 * language (the phone's, or the one chosen for this app in the Android settings). Composables use stringResource.
 */
object Strings {
    private lateinit var context: Context

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun get(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = context.resources.getQuantityString(id, count, *args)
}
