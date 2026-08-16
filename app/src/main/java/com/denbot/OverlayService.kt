package com.denbot

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var metrics: DisplayMetrics
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var selectorView: View? = null
    private var fabView: View? = null
    private var statusPanel: View? = null

    private var regionRect = Rect()
    private var regionConfirmed = false
    private var fabX = 100f; private var fabY = 300f
    private var dX = 0f; private var dY = 0f
    private var selLeft = 100; private var selTop = 250
    private var selRight = 700; private var selBottom = 850

    private val handler = Handler(Looper.getMainLooper())
    private var analyzing = false
    private val engine = ChessEngine()

    companion object {
        const val CHANNEL_ID = "denbot_channel"
        const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode != -1 && data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            try {
                mediaProjection = mgr.getMediaProjection(resultCode, data)
                if (mediaProjection == null) {
                    toastMsg("❌ mediaProjection es null tras getMediaProjection")
                } else {
                    toastMsg("✅ MediaProjection obtenido")
                }
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        virtualDisplay?.release()
                        imageReader?.close()
                    }
                }, handler)
                handler.postDelayed({ setupImageReader() }, 300)
            } catch (e: Exception) {
                toastMsg("❌ Excepción MediaProjection: ${e.message}")
            }
        } else {
            toastMsg("⚠ resultCode=$resultCode o data=null — permiso no llegó")
        }
        showFab()
        return START_STICKY
    }

    private fun toastMsg(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFab() {
        if (fabView != null) return
        val params = WindowManager.LayoutParams(
            120, 120,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = fabX.toInt(); y = fabY.toInt() }
        val fab = LayoutInflater.from(this).inflate(R.layout.fab_button, null)
        fabView = fab
        fab.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dX = params.x - e.rawX; dY = params.y - e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (e.rawX + dX).toInt(); params.y = (e.rawY + dY).toInt()
                    fabX = params.x.toFloat(); fabY = params.y.toFloat()
                    wm.updateViewLayout(fab, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(e.rawX + dX - fabX) < 10 && Math.abs(e.rawY + dY - fabY) < 10) {
                        if (regionConfirmed) triggerAnalysis() else showSelector()
                    }
                }
            }
            true
        }
        wm.addView(fab, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSelector() {
        val params = WindowManager.LayoutParams(
            metrics.widthPixels, metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        val sel = SelectorView(this) { l, t, r, b -> selLeft = l; selTop = t; selRight = r; selBottom = b }
        selectorView = sel
        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 80 }
        val btnRow = LayoutInflater.from(this).inflate(R.layout.selector_controls, null)
        btnRow.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            regionRect = Rect(selLeft, selTop, selRight, selBottom)
            regionConfirmed = true
            removeSelectorViews(sel, btnRow)
            showStatusPanel()
            triggerAnalysis()
        }
        btnRow.findViewById<Button>(R.id.btnCancel).setOnClickListener { removeSelectorViews(sel, btnRow) }
        wm.addView(sel, params)
        wm.addView(btnRow, btnParams)
    }

    private fun removeSelectorViews(vararg views: View) {
        views.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        selectorView = null
    }

    private fun showStatusPanel() {
        if (statusPanel != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 80 }
        val panel = LayoutInflater.from(this).inflate(R.layout.status_panel, null)
        statusPanel = panel
        panel.findViewById<ImageButton>(R.id.btnEdit).setOnClickListener {
            regionConfirmed = false
            try { wm.removeView(panel) } catch (_: Exception) {}
            statusPanel = null
            showSelector()
        }
        panel.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { stopSelf() }
        wm.addView(panel, params)
    }

    private fun setupImageReader() {
        try {
            imageReader?.close()
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            virtualDisplay?.release()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "DenBotCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, handler
            )
            if (virtualDisplay == null) {
                toastMsg("❌ VirtualDisplay null — createVirtualDisplay falló")
            } else {
                toastMsg("✅ VirtualDisplay creado — captura lista")
            }
        } catch (e: Exception) {
            toastMsg("❌ setupImageReader excepción: ${e.message}")
        }
    }

    private fun captureRegion(): Bitmap? {
        if (imageReader == null || virtualDisplay == null) {
            return null
        }
        // Try up to 5 times with delay, since first frames may not be ready
        var image: android.media.Image? = null
        for (attempt in 1..5) {
            Thread.sleep(200)
            image = imageReader?.acquireLatestImage()
            if (image != null) break
        }
        if (image == null) return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * metrics.widthPixels
            val fullBmp = Bitmap.createBitmap(metrics.widthPixels + rowPadding / pixelStride, metrics.heightPixels, Bitmap.Config.ARGB_8888)
            fullBmp.copyPixelsFromBuffer(buffer)
            val safeLeft = regionRect.left.coerceAtLeast(0)
            val safeTop = regionRect.top.coerceAtLeast(0)
            val safeRight = regionRect.right.coerceAtMost(fullBmp.width)
            val safeBottom = regionRect.bottom.coerceAtMost(fullBmp.height)
            val cropped = Bitmap.createBitmap(fullBmp, safeLeft, safeTop, safeRight - safeLeft, safeBottom - safeTop)
            fullBmp.recycle()
            cropped
        } finally { image.close() }
    }

    private fun triggerAnalysis() {
        if (analyzing) return
        analyzing = true
        updateStatus("🔍 Analizando...")
        Thread {
            try {
                val bmp = captureRegion()
                if (bmp == null) {
                    val reason = if (imageReader == null) "motor de captura no iniciado" else "sin frames disponibles"
                    handler.post { updateStatus("❌ Sin captura ($reason)"); analyzing = false }
                    return@Thread
                }
                val fen = sendToClaudeVision(bmp)
                bmp.recycle()
                if (fen.isNullOrEmpty() || !fen.contains("/")) {
                    handler.post { updateStatus("❌ No detecté tablero"); analyzing = false }
                    return@Thread
                }

                val prefs = getSharedPreferences("denbot_prefs", MODE_PRIVATE)
                val playAsBlack = prefs.getBoolean("playAsBlack", false)
                val elo = prefs.getInt("elo", 1500)
                val bulletMode = prefs.getBoolean("bulletMode", false)

                val board = Board()
                board.loadFromFen(fen)
                val playerSide = if (playAsBlack) Side.BLACK else Side.WHITE

                if (board.sideToMove != playerSide) {
                    handler.post { updateStatus("⏳ Turno del rival"); analyzing = false }
                    return@Thread
                }

                val move = engine.getBestMove(fen, elo, bulletMode)
                if (move == null) {
                    handler.post { updateStatus("❌ Sin movimiento"); analyzing = false }
                    return@Thread
                }

                handler.post {
                    updateStatus("♟ Jugando: $move")
                    playMove(move)
                }
            } catch (e: Exception) {
                handler.post { updateStatus("❌ ${e.message}"); analyzing = false }
            }
        }.start()
    }

    private fun playMove(uci: String) {
        val sqSize = regionRect.width() / 8f
        val sqSizeH = regionRect.height() / 8f
        val fromFile = uci[0] - 'a'; val fromRank = uci[1] - '1'
        val toFile = uci[2] - 'a'; val toRank = uci[3] - '1'

        val prefs = getSharedPreferences("denbot_prefs", MODE_PRIVATE)
        val playAsBlack = prefs.getBoolean("playAsBlack", false)
        val bulletMode = prefs.getBoolean("bulletMode", false)

        val fx = if (playAsBlack) 7 - fromFile else fromFile
        val fy = if (playAsBlack) fromRank else 7 - fromRank
        val tx = if (playAsBlack) 7 - toFile else toFile
        val ty = if (playAsBlack) toRank else 7 - toRank

        val fromX = regionRect.left + fx * sqSize + sqSize / 2
        val fromY = regionRect.top + fy * sqSizeH + sqSizeH / 2
        val toX = regionRect.left + tx * sqSize + sqSize / 2
        val toY = regionRect.top + ty * sqSizeH + sqSizeH / 2

        val delay = if (bulletMode) 80L else 300L
        AutoClickService.tapSequence(fromX, fromY, toX, toY, delay, if (bulletMode) 30L else 60L) {
            handler.postDelayed({ analyzing = false }, if (bulletMode) 200L else 600L)
        }
    }

    private fun sendToClaudeVision(bmp: Bitmap): String? {
        val prefs = getSharedPreferences("denbot_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: return null
        val playAsBlack = prefs.getBoolean("playAsBlack", false)
        val side = if (playAsBlack) "black" else "white"
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        val prompt = "You are a chess board FEN detector. Look at this chess board image and return ONLY the FEN notation representing the current position. The board may be oriented with $side at the bottom. The player to move is $side. Return ONLY the FEN string, nothing else, no explanation."
        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 100)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64"); put("media_type", "image/jpeg"); put("data", b64)
                            })
                        })
                        put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                    })
                })
            })
        }
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = client.newCall(request).execute()
        val json = JSONObject(resp.body!!.string())
        return json.getJSONArray("content").getJSONObject(0).getString("text").trim()
    }

    private fun updateStatus(text: String) {
        handler.post { statusPanel?.findViewById<TextView>(R.id.statusText)?.text = text }
    }

    override fun onDestroy() {
        super.onDestroy()
        listOf(fabView, selectorView, statusPanel).forEach {
            it?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Den Bot", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Den Bot activo")
            .setContentText("Toca el botón flotante para jugar")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
