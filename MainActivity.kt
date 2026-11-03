package com.sonic8d.player

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.sin

private data class DeviceMedia(val name: String, val uri: Uri, val duration: Long)

class MainActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var page: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var info: TextView
    private lateinit var visualizer: SpectrumView
    private val audioItems = mutableListOf<DeviceMedia>()
    private val videoItems = mutableListOf<DeviceMedia>()
    private var tab = "MUSIC"
    private var hdMode = false
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var effectsListener: Player.Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF070B14.toInt()
        window.navigationBarColor = 0xFF070B14.toInt()
        player = ExoPlayer.Builder(this).build()
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true
        )
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                info.text = when (state) {
                    Player.STATE_BUFFERING -> "Loading media…"
                    Player.STATE_READY -> "Ready • ${player.mediaMetadata.title ?: "Playing"}"
                    Player.STATE_ENDED -> "Playback finished"
                    else -> "Your sound. Your space."
                }
                visualizer.invalidate()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { visualizer.isPlaying = isPlaying }
        })
        createScreen()
        requestLibraryAccess()
    }

    private fun createScreen() {
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(0xFF070B14.toInt())
        }
        val heading = TextView(this).apply {
            text = "SONIC 8D"
            textSize = 28f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF35C8FF.toInt())
            setPadding(0, dp(8), 0, dp(2))
            alpha = 0f
            animate().alpha(1f).setDuration(700).setInterpolator(DecelerateInterpolator()).start()
        }
        page.addView(heading)
        info = TextView(this).apply {
            text = "MUSIC • VIDEO • IMMERSIVE SOUND"
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(0xFF9CB1C9.toInt())
            setPadding(0, 0, 0, dp(10))
        }
        page.addView(info)
        visualizer = SpectrumView()
        page.addView(visualizer, LinearLayout.LayoutParams(-1, dp(72)))
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(makeTab("MUSIC") { showMusic() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        tabs.addView(makeTab("VIDEOS") { showVideos() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        page.addView(tabs)
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private inner class SpectrumView : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var isPlaying = false
        private var phase = 0f
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val count = 40
            val gap = width * 0.012f
            val barW = (width - gap * (count + 1)) / count
            for (i in 0 until count) {
                val wave = if (isPlaying) (0.18f + 0.72f * (0.5f + 0.5f * sin(phase + i * 0.55f))) else 0.12f
                val h = height * wave
                paint.color = if (i % 3 == 0) 0xFF9B5CFF.toInt() else 0xFF20C8FF.toInt()
                canvas.drawRoundRect(gap + i * (barW + gap), (height-h)/2, gap + i*(barW+gap)+barW, (height+h)/2, barW/2, barW/2, paint)
            }
            if (isPlaying) {
                phase += 0.16f
                postInvalidateDelayed(45)
            }
        }
    }

    private fun makeTab(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = true
        setTextColor(0xFFFFFFFF.toInt())
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF12314A.toInt())
        setOnClickListener { animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            action()
        }.start() }
    }

    private fun requestLibraryAccess() {
        val needed = if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) scanMedia() else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 72)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 72) {
            if (results.any { it == PackageManager.PERMISSION_GRANTED }) scanMedia()
            else info.text = "Allow media access to show phone files"
        }
    }

    private fun scanMedia() {
        audioItems.clear(); videoItems.clear()
        readStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false, audioItems)
        readStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoItems)
        if (tab == "MUSIC") showMusic() else showVideos()
        info.text = "${audioItems.size} songs • ${videoItems.size} videos found"
    }

    private fun readStore(uri: Uri, isVideo: Boolean, target: MutableList<DeviceMedia>) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION)
        val filter = if (isVideo) "${MediaStore.Video.Media.DURATION} > 0" else "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        try {
            contentResolver.query(uri, projection, filter, null, "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
                val idIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val durationIndex = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(idIndex)
                    target.add(DeviceMedia(c.getString(nameIndex) ?: "Unknown", ContentUris.withAppendedId(uri, id),
                        if (durationIndex >= 0) c.getLong(durationIndex) else 0L))
                }
            }
        } catch (_: Exception) { info.text = "Unable to scan media on this device" }
    }

    private fun showMusic() {
        tab = "MUSIC"; body.removeAllViews()
        section("MUSIC LIBRARY")
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        controls.addView(controlButton("PREVIOUS") { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() })
        controls.addView(controlButton("PLAY / PAUSE") { if (player.isPlaying) player.pause() else player.play() })
        controls.addView(controlButton("NEXT") { if (player.hasNextMediaItem()) player.seekToNextMediaItem() })
        body.addView(controls)
        section("AUDIO QUALITY")
        val hdRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        hdRow.addView(TextView(this).apply {
            text = "HD AUDIO MODE\nUses the source file's available quality"
            textSize = 13f; setTextColor(0xFFE1ECF8.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val hdSwitch = Switch(this).apply { isChecked = hdMode; setOnCheckedChangeListener { _, checked ->
            hdMode = checked
            info.text = if (checked) "HD mode on • original file quality is preserved" else "Standard playback mode"
        } }
        hdRow.addView(hdSwitch); body.addView(hdRow)
        section("SOUND CONTROLS")
        addEffectSlider("Equalizer • Tone", 100) { value -> applyEqualizer(value) }
        addEffectSlider("Bass Boost", 100) { value -> try { bass?.setStrength((value * 10).toShort()) } catch (_: Exception) {} }
        addEffectSlider("8D / Surround", 100) { value -> try { virtualizer?.setStrength((value * 10).toShort()) } catch (_: Exception) {} }
        body.addView(TextView(this).apply {
            text = "Note: HD quality and sound effects depend on the original file, Android device and audio hardware."
            textSize = 11f; setTextColor(0xFF8FA6C0.toInt()); setPadding(0, dp(8), 0, dp(8))
        })
        section("ALL SONGS • ${audioItems.size}")
        mediaRows(audioItems, false)
    }

    private fun showVideos() {
        tab = "VIDEOS"; body.removeAllViews()
        section("PHONE VIDEO PLAYER")
        val preview = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            layoutParams = LinearLayout.LayoutParams(-1, dp(220))
        }
        body.addView(preview)
        section("ALL VIDEOS • ${videoItems.size}")
        mediaRows(videoItems, true)
    }

    private fun section(label: String) {
        body.addView(TextView(this).apply {
            text = label; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF35C8FF.toInt()); setPadding(0, dp(15), 0, dp(8))
        })
    }
    private fun controlButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 10f
        setOnClickListener { action() }
    }
    private fun addEffectSlider(label: String, maxValue: Int, action: (Int) -> Unit) {
        body.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(0xFFE1ECF8.toInt()) })
        body.addView(SeekBar(this).apply {
            max = maxValue
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) action(value) }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
    }
    private fun applyEqualizer(value: Int) {
        try {
            val effect = eq ?: return
            val range = effect.bandLevelRange
            val level = (range[0] + (range[1] - range[0]) * value / 100).toShort()
            for (band in 0 until effect.numberOfBands) effect.setBandLevel(band.toShort(), level)
        } catch (_: Exception) {}
    }
    private fun mediaRows(items: List<DeviceMedia>, isVideo: Boolean) {
        if (items.isEmpty()) {
            body.addView(TextView(this).apply {
                text = if (isVideo) "No videos found. Add videos to your phone and refresh." else "No songs found on this phone."
                setTextColor(0xFF9CB1C9.toInt()); setPadding(dp(8), dp(14), dp(8), dp(14))
            }); return
        }
        items.forEachIndexed { index, item ->
            val row = TextView(this).apply {
                text = "${if (isVideo) "▶" else "♫"}   ${item.name}"
                textSize = 14f; setTextColor(0xFFF4F8FF.toInt())
                setPadding(dp(12), dp(15), dp(12), dp(15))
                setBackgroundColor(if (index % 2 == 0) 0xFF111D2D.toInt() else 0xFF0B1421.toInt())
                setOnClickListener {
                    animate().alpha(0.65f).setDuration(70).withEndAction { alpha = 1f; startPlaylist(items, index, isVideo) }.start()
                }
            }
            body.addView(row)
        }
    }
    private fun startPlaylist(items: List<DeviceMedia>, index: Int, isVideo: Boolean) {
        try {
            player.setMediaItems(items.map { MediaItem.fromUri(it.uri) }, index, 0L)
            player.prepare(); player.play()
            if (!isVideo) {
                effectsListener?.let { player.removeListener(it) }
                effectsListener = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && eq == null) attachEffects()
                    }
                }
                player.addListener(effectsListener!!)
            } else releaseEffects()
        } catch (_: Exception) { info.text = "Could not play this file" }
    }
    private fun attachEffects() {
        try {
            releaseEffects()
            val sessionId = player.audioSessionId
            if (sessionId <= 0) return
            eq = Equalizer(0, sessionId).apply { enabled = true }
            bass = BassBoost(0, sessionId).apply { enabled = true }
            virtualizer = Virtualizer(0, sessionId).apply { enabled = true }
        } catch (_: Exception) { info.text = "Some audio effects are unavailable on this phone" }
    }
    private fun releaseEffects() {
        try { eq?.release() } catch (_: Exception) {}
        try { bass?.release() } catch (_: Exception) {}
        try { virtualizer?.release() } catch (_: Exception) {}
        eq = null; bass = null; virtualizer = null
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        effectsListener?.let { player.removeListener(it) }
        releaseEffects(); player.release()
        super.onDestroy()
    }
}
