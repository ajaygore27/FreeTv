package com.example.freelivetv

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

data class Channel(val name: String, val group: String, val url: String) {
    val label get() = if (group.isEmpty()) name else "$name  •  $group"
}

class MainActivity : AppCompatActivity() {

    // Full iptv-org index: every publicly available free channel worldwide.
    private val playlistUrl = "https://iptv-org.github.io/iptv/index.m3u"

    private lateinit var player: ExoPlayer
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var status: TextView
    private var all = listOf<Channel>()
    private var shown = listOf<Channel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        player = ExoPlayer.Builder(this).build()
        findViewById<PlayerView>(R.id.player).player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@MainActivity, "Channel offline or blocked, try another", Toast.LENGTH_SHORT).show()
            }
        })

        status = findViewById(R.id.status)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val list = findViewById<ListView>(R.id.channels)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            player.setMediaItem(MediaItem.fromUri(shown[pos].url))
            player.prepare()
            player.playWhenReady = true
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadChannels()
    }

    private fun loadChannels() {
        val cache = File(cacheDir, "playlist.m3u")
        // Show cached list instantly if we have one, then refresh from the network.
        if (cache.exists()) {
            all = parseM3u(cache.readText())
            applyFilter("")
        }
        thread {
            try {
                val text = URL(playlistUrl).readText()
                cache.writeText(text)
                val parsed = parseM3u(text)
                runOnUiThread { all = parsed; applyFilter(findViewById<EditText>(R.id.search).text.toString()) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (all.isEmpty()) status.text = "Couldn't load channels. Check internet."
                }
            }
        }
    }

    private fun applyFilter(q: String) {
        val query = q.trim().lowercase()
        shown = if (query.isEmpty()) all else all.filter { it.label.lowercase().contains(query) }
        adapter.clear()
        adapter.addAll(shown.map { it.label })
        status.text = "${shown.size} channels"
    }

    private fun parseM3u(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfterLast(",").trim()
                    group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
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
