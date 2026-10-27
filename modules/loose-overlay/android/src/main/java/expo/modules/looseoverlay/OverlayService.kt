package expo.modules.looseoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The floating circle. Draws over other apps with a draggable circle; tapping it opens a one-box
 * text field (Enter = new line, the box grows) with a send button that posts the text to the
 * saved Loose Leaf server using the saved API key (action=idea_add).
 */
class OverlayService : Service() {

  companion object {
    const val ACTION_STOP = "expo.modules.looseoverlay.STOP"
    private const val CHANNEL = "loose_overlay"
    private const val NOTIF_ID = 4107

    @Volatile
    var running = false
  }

  private lateinit var wm: WindowManager
  private val main = Handler(Looper.getMainLooper())
  private var dp = 1f

  private var bubble: FrameLayout? = null
  private var bubbleLp: WindowManager.LayoutParams? = null
  private var bubbleIcon: IconView? = null

  private var panel: PanelLayout? = null
  private var panelLp: WindowManager.LayoutParams? = null
  private var input: EditText? = null
  private var sendBtn: FrameLayout? = null

  private var open = false
  private var sending = false
  private var draft = ""
  private var restoreY = -1

  private fun px(v: Int): Int = (v * dp + 0.5f).toInt()

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    dp = resources.displayMetrics.density
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      LoosePrefs.setEnabled(this, false)
      stopSelf()
      return START_NOT_STICKY
    }
    // A foreground service must always call startForeground right after being started.
    startFg()
    if (!LoosePrefs.enabled(this) || !Settings.canDrawOverlays(this)) {
      stopSelf()
      return START_NOT_STICKY
    }
    if (bubble == null) addBubble()
    running = true
    return START_STICKY
  }

  override fun onDestroy() {
    running = false
    closePanel()
    bubble?.let { runCatching { wm.removeView(it) } }
    bubble = null
    super.onDestroy()
  }

  // ------------------------------------------------------------ notification --
  @Suppress("DEPRECATION")
  private fun startFg() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Floating circle", NotificationManager.IMPORTANCE_LOW)
      )
    }
    val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    val stopPi = PendingIntent.getService(
      this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), piFlags
    )
    val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
    b.setContentTitle("Loose is on")
      .setContentText("The floating circle is showing")
      .setSmallIcon(android.R.drawable.ic_menu_edit)
      .setOngoing(true)
    packageManager.getLaunchIntentForPackage(packageName)?.let {
      b.setContentIntent(PendingIntent.getActivity(this, 0, it, piFlags))
    }
    b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", stopPi)
    val n = b.build()
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIF_ID, n)
    }
  }

  // ------------------------------------------------------------------ drawing --
  @Suppress("DEPRECATION")
  private fun overlayType(): Int {
    return if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else WindowManager.LayoutParams.TYPE_PHONE
  }

  private fun roundBg(fill: Int, radiusPx: Int, stroke: Int, oval: Boolean = false): GradientDrawable {
    val g = GradientDrawable()
    g.shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
    if (!oval) g.cornerRadius = radiusPx.toFloat()
    g.setColor(fill)
    g.setStroke(px(1), stroke)
    return g
  }

  private val panelFill get() = Color.argb(215, 26, 18, 64)
  private val outline get() = Color.argb(110, 255, 255, 255)

  private fun addBubble() {
    val dm = resources.displayMetrics
    val size = px(44)
    val frame = FrameLayout(this)
    frame.background = roundBg(Color.argb(205, 42, 31, 94), 0, outline, oval = true)
    val icon = IconView(this, IconView.PLUS)
    frame.addView(icon, FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER))

    val lp = WindowManager.LayoutParams(
      size, size, overlayType(),
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT
    )
    lp.gravity = Gravity.TOP or Gravity.START
    lp.x = LoosePrefs.posX(this, dm.widthPixels - size - px(16))
    lp.y = LoosePrefs.posY(this, dm.heightPixels / 2)
    clampInto(lp)

    frame.setOnTouchListener(object : View.OnTouchListener {
      var sx = 0f
      var sy = 0f
      var ox = 0
      var oy = 0
      var moved = false
      override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.action) {
          MotionEvent.ACTION_DOWN -> {
            sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
            return true
          }
          MotionEvent.ACTION_MOVE -> {
            val dx = e.rawX - sx
            val dy = e.rawY - sy
            if (!moved && Math.abs(dx) + Math.abs(dy) > px(6)) moved = true
            if (moved) {
              lp.x = ox + dx.toInt()
              lp.y = oy + dy.toInt()
              clampInto(lp)
              wm.updateViewLayout(frame, lp)
              if (open) layoutPanel()
            }
            return true
          }
          MotionEvent.ACTION_UP -> {
            if (!moved) togglePanel()
            else {
              if (open) restoreY = lp.y
              LoosePrefs.savePos(this@OverlayService, lp.x, lp.y)
            }
            return true
          }
        }
        return false
      }
    })

    wm.addView(frame, lp)
    bubble = frame
    bubbleLp = lp
    bubbleIcon = icon
  }

  /** Keep the circle on screen; while the field is open keep it high enough for the keyboard. */
  private fun clampInto(lp: WindowManager.LayoutParams) {
    val dm = resources.displayMetrics
    val size = px(44)
    val maxY = if (open) (dm.heightPixels * 0.40f).toInt() else dm.heightPixels - size
    lp.x = lp.x.coerceIn(0, dm.widthPixels - size)
    lp.y = lp.y.coerceIn(0, maxY.coerceAtLeast(0))
  }

  // -------------------------------------------------------------------- panel --
  private fun togglePanel() {
    if (open) closePanel() else openPanel()
  }

  private fun openPanel() {
    val blp = bubbleLp ?: return
    val bub = bubble ?: return
    if (open) return
    restoreY = blp.y
    open = true
    // Lift the circle (and with it the field) so the keyboard does not cover them.
    clampInto(blp)
    wm.updateViewLayout(bub, blp)

    val root = PanelLayout(this) { closePanel() }
    root.minimumHeight = px(44)
    root.background = roundBg(panelFill, px(22), outline)

    val et = EditText(this)
    et.background = null
    et.setTextColor(Color.WHITE)
    et.setHintTextColor(Color.argb(180, 255, 255, 255))
    et.hint = "Write your idea…"
    et.textSize = 16f
    et.minHeight = px(44)
    et.maxLines = 8
    et.gravity = Gravity.CENTER_VERTICAL
    et.setPadding(px(18), px(8), px(52), px(8))
    et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
      InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    et.setText(draft)
    et.setSelection(et.text.length)
    root.addView(et, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

    val sendView = FrameLayout(this)
    sendView.background = roundBg(Color.argb(70, 255, 255, 255), 0, Color.argb(110, 255, 255, 255), oval = true)
    sendView.addView(IconView(this, IconView.SEND), FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER))
    val slp = FrameLayout.LayoutParams(px(32), px(32), Gravity.BOTTOM or Gravity.END)
    slp.setMargins(0, 0, px(6), px(6))
    sendView.setOnClickListener { send() }
    root.addView(sendView, slp)

    val lp = WindowManager.LayoutParams(
      px(200), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT
    )
    lp.gravity = Gravity.TOP or Gravity.START
    lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
      WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE

    panel = root
    panelLp = lp
    input = et
    sendBtn = sendView
    layoutPanel()
    wm.addView(root, lp)
    bubbleIcon?.animate()?.rotation(45f)?.setDuration(180)?.start()

    et.requestFocus()
    main.postDelayed({
      val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      input?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
    }, 200)
  }

  /** Same row as the circle, on whichever side has more room; one-line height = circle height. */
  private fun layoutPanel() {
    val lp = panelLp ?: return
    val blp = bubbleLp ?: return
    val dm = resources.displayMetrics
    val size = px(44)
    val gap = px(8)
    val m = px(12)
    val x: Int
    var w: Int
    if (blp.x + size / 2 < dm.widthPixels / 2) {
      x = blp.x + size + gap
      w = dm.widthPixels - x - m
    } else {
      w = blp.x - gap - m
      x = m
    }
    if (w < px(160)) w = px(160)
    lp.x = x
    lp.width = w
    lp.y = blp.y
    panel?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, lp) }
  }

  private fun closePanel() {
    val p = panel ?: return
    draft = input?.text?.toString() ?: ""
    runCatching {
      val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.hideSoftInputFromWindow(p.windowToken, 0)
    }
    runCatching { wm.removeView(p) }
    panel = null
    panelLp = null
    input = null
    sendBtn = null
    open = false
    bubbleIcon?.animate()?.rotation(0f)?.setDuration(180)?.start()
    val blp = bubbleLp
    val bub = bubble
    if (blp != null && bub != null && restoreY >= 0) {
      blp.y = restoreY
      clampInto(blp)
      runCatching { wm.updateViewLayout(bub, blp) }
    }
    restoreY = -1
  }

  // --------------------------------------------------------------------- send --
  private fun send() {
    val et = input ?: return
    val text = et.text.toString().trim()
    if (text.isEmpty() || sending) return
    val endpoint = LoosePrefs.endpoint(this)
    val key = LoosePrefs.key(this)
    if (endpoint.isEmpty() || key.isEmpty()) {
      toast("Open Loose and save your API key first")
      return
    }
    sending = true
    sendBtn?.alpha = 0.4f
    thread {
      var error: String? = null
      try {
        postIdea(endpoint, key, text)
      } catch (e: Exception) {
        error = e.message ?: "Failed"
      }
      main.post {
        sending = false
        sendBtn?.alpha = 1f
        if (error == null) {
          draft = ""
          input?.setText("")
          toast("Sent")
        } else {
          toast("Not sent: $error")
        }
      }
    }
  }

  private fun postIdea(endpoint: String, key: String, text: String) {
    val conn = URL("$endpoint?action=idea_add").openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = 10000
      conn.readTimeout = 20000
      conn.doOutput = true
      conn.setRequestProperty("Authorization", "Bearer $key")
      conn.setRequestProperty("X-API-Key", key)
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      conn.setRequestProperty("Accept", "application/json")
      val body = JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)
      conn.outputStream.use { it.write(body) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
      if (code !in 200..299) {
        val msg = try { JSONObject(resp).optString("error") } catch (e: Exception) { "" }
        throw Exception(if (msg.isNotBlank()) msg else "HTTP $code")
      }
    } finally {
      conn.disconnect()
    }
  }

  private fun toast(msg: String) {
    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
  }

  // ------------------------------------------------------------------- views --
  /** Closes the field when Back is pressed. */
  private class PanelLayout(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
      if (event.keyCode == KeyEvent.KEYCODE_BACK) {
        if (event.action == KeyEvent.ACTION_UP) onBack()
        return true
      }
      return super.dispatchKeyEvent(event)
    }
  }

  /** The + and paper-plane icons, drawn in code so no image resources are needed. */
  private class IconView(context: Context, private val kind: Int) : View(context) {
    companion object {
      const val PLUS = 0
      const val SEND = 1
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = Color.WHITE
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
      val s = width / 24f
      paint.strokeWidth = 2f * s
      if (kind == PLUS) {
        canvas.drawLine(12 * s, 5 * s, 12 * s, 19 * s, paint)
        canvas.drawLine(5 * s, 12 * s, 19 * s, 12 * s, paint)
      } else {
        val p = Path()
        p.moveTo(21 * s, 3 * s)
        p.lineTo(10 * s, 14 * s)
        p.moveTo(21 * s, 3 * s)
        p.lineTo(14 * s, 21 * s)
        p.lineTo(10 * s, 14 * s)
        p.lineTo(3 * s, 10 * s)
        p.close()
        canvas.drawPath(p, paint)
      }
    }
  }
}
