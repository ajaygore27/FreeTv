package com.example.freelivetv

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.net.URL
import java.util.TreeMap
import kotlin.concurrent.thread

data class Channel(val name: String, val group: String, val url: String) {
    val label get() = if (group.isEmpty()) name else "$name  •  ${group.replace(";", ", ")}"
}

class MainActivity : AppCompatActivity() {

    // Full iptv-org index: every publicly available free channel worldwide.
    private val playlistUrl = "https://iptv-org.github.io/iptv/index.m3u"

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView

    private var all = listOf<Channel>()
    private var groups: Map<String, List<Channel>> = emptyMap()
    private var currentGroup: String? = null
    private var showingGroups = true
    private var shownGroups = listOf<String>()
    private var shownChannels = listOf<Channel>()
    private var fullscreen = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                fullscreen -> setFullscreen(false)
                currentGroup != null -> { currentGroup = null; search.setText(""); refresh() }
                else -> search.setText("")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backCallback)

        search = findViewById(R.id.search)
        status = findViewById(R.id.status)
        list = findViewById(R.id.channels)
        playerView = findViewById(R.id.player)

        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.setFullscreenButtonClickListener { setFullscreen(it) }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@MainActivity, "Channel offline or blocked, try another", Toast.LENGTH_SHORT).show()
            }
        })

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            if (showingGroups) {
                currentGroup = shownGroups[pos]
                refresh()
                list.setSelection(0)
            } else {
                player.setMediaItem(MediaItem.fromUri(shownChannels[pos].url))
                player.prepare()
                player.playWhenReady = true
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadChannels()
    }

    private fun loadChannels() {
        val cache = File(cacheDir, "playlist.m3u")
        if (cache.exists()) setChannels(parseM3u(cache.readText()))
        thread {
            try {
                val text = URL(playlistUrl).readText()
                cache.writeText(text)
                val parsed = parseM3u(text)
                runOnUiThread { setChannels(parsed) }
            } catch (e: Exception) {
                runOnUiThread { if (all.isEmpty()) status.text = "Couldn't load channels. Check internet." }
            }
        }
    }

    private fun setChannels(channels: List<Channel>) {
        all = channels
        val map = TreeMap<String, MutableList<Channel>>(String.CASE_INSENSITIVE_ORDER)
        for (c in channels) {
            val gs = c.group.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf("Uncategorized") }
            for (g in gs) map.getOrPut(g) { mutableListOf() }.add(c)
        }
        groups = map
        if (currentGroup != null && !map.containsKey(currentGroup)) currentGroup = null
        refresh()
    }

    private fun refresh() {
        val q = search.text.toString().trim().lowercase()
        val g = currentGroup
        adapter.clear()
        if (g == null && q.isEmpty()) {
            showingGroups = true
            shownGroups = groups.keys.toList()
            adapter.addAll(shownGroups.map { "$it  (${groups[it]!!.size})" })
            status.text = "${groups.size} groups • ${all.size} channels"
        } else {
            showingGroups = false
            val base = if (g == null) all else groups[g].orEmpty()
            shownChannels = if (q.isEmpty()) base else base.filter {
                it.name.lowercase().contains(q) || (g == null && it.group.lowercase().contains(q))
            }
            adapter.addAll(shownChannels.map { if (g == null) it.label else it.name })
            status.text = "${g ?: "Search results"} • ${shownChannels.size} channels"
        }
        updateBack()
    }

    private fun updateBack() {
        backCallback.isEnabled = fullscreen || currentGroup != null || search.text.isNotEmpty()
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val vis = if (on) View.GONE else View.VISIBLE
        search.visibility = vis; status.visibility = vis; list.visibility = vis
        playerView.layoutParams = playerView.layoutParams.apply {
            height = if (on) ViewGroup.LayoutParams.MATCH_PARENT
            else (220 * resources.displayMetrics.density).toInt()
        }
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        updateBack()
    }

    private val groupRegex = Regex("group-title=\"([^\"]*)\"")

    private fun parseM3u(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfterLast(",").trim()
                    group = groupRegex.find(line)?.groupValues?.get(1).orEmpty()
                }
                line.isNotEmpty() && !line.startsWith("#") && name != null -> {
                    out.add(Channel(name, group, line)); name = null
                }
            }
        }
        return out
    }

    override fun onStop() { super.onStop(); player.pause() }
    override fun onDestroy() { super.onDestroy(); player.release() }
}
