package expo.modules.looseoverlay

import android.content.Context
import android.content.SharedPreferences

/** The one saved server + API key, the on/off choice and the circle's position. */
object LoosePrefs {
  private const val NAME = "loose_prefs"

  private fun sp(c: Context): SharedPreferences =
    c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

  fun endpoint(c: Context): String = sp(c).getString("endpoint", "") ?: ""
  fun key(c: Context): String = sp(c).getString("key", "") ?: ""

  /** Saving replaces whatever was stored: only one key at a time. */
  fun saveConfig(c: Context, endpoint: String, key: String) {
    sp(c).edit().putString("endpoint", endpoint).putString("key", key).apply()
  }

  fun clearConfig(c: Context) {
    sp(c).edit().remove("endpoint").remove("key").putBoolean("enabled", false).apply()
  }

  fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)
  fun setEnabled(c: Context, on: Boolean) {
    sp(c).edit().putBoolean("enabled", on).apply()
  }

  fun posX(c: Context, def: Int): Int = sp(c).getInt("x", def)
  fun posY(c: Context, def: Int): Int = sp(c).getInt("y", def)
  fun savePos(c: Context, x: Int, y: Int) {
    sp(c).edit().putInt("x", x).putInt("y", y).apply()
  }
}
