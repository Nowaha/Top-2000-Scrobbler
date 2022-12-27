package xyz.nowaha

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.jcm.discordgamesdk.Core
import de.jcm.discordgamesdk.CreateParams
import de.jcm.discordgamesdk.activity.Activity
import de.umass.lastfm.Authenticator
import de.umass.lastfm.Caller
import de.umass.lastfm.Session
import de.umass.lastfm.Track
import kotlinx.coroutines.*
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.logging.Level
import kotlin.system.exitProcess

var run = true

lateinit var core: Core
lateinit var activity: Activity

var latest = ""
var scrobbled = false

const val apiKey = "d9719af04dfe26c9a87f944b75a3eb2b"
const val apiSecret = Secrets.lastFmApiSecret

lateinit var lastFmSession: Session

suspend fun main(args: Array<String>): Unit = coroutineScope {
    setupLastFm()
    setupRichPresence()
    postLogin()
}

fun setupLastFm() {
    Caller.getInstance().userAgent = "nowaha@top2000scrobbler-1.0"
    Caller.getInstance().logger.level = Level.WARNING

    var session: Session? = null

    while (session == null) {
        ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor()

        println("Please log in to start scrobbling! Input '.quit' to quit.\n")
        print("Last.fm username: ")
        val username = readln()
        if (username == ".quit") exitProcess(0)
        print("Last.fm password: ")
        val password = readln()
        if (password == ".quit") exitProcess(0)

        session = Authenticator.getMobileSession(username, password, apiKey, apiSecret)
    }

    ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor()
    lastFmSession = session
}

fun setupRichPresence() {
    Core.initDownload()

    val params = CreateParams()
    params.clientID = 1056352600045387796L
    params.flags = CreateParams.getDefaultFlags()

    core = Core(params)
    activity = Activity()
}

suspend fun postLogin() = coroutineScope {
    launch {
        while (run) {
            core.runCallbacks()
            delay(16)
        }
        core.close()
    }
    launch {
        while (run) {
            checkApi()
            delay(5000)
        }
    }

    launch { checkInput() }
}

suspend fun checkApi() = withContext(Dispatchers.IO) {
    val url = URL("https://www.nporadio2.nl/api/miniplayer/info?channel=npo-radio-2")
    val connection = url.openConnection()
    val parsed = JsonParser.parseReader(connection.getInputStream().bufferedReader()).asJsonObject
    val songData =
        parsed.getObj("data")?.getObj("radioTrackPlays")?.getArray("data")?.get(0)?.asJsonObject ?: return@withContext

    val artist = songData.getString("artist") ?: return@withContext
    val title = songData.getString("song")?.replace(" (Albumversie)", "") ?: return@withContext
    val placement = songData.getObj("cmsChartEditionPositions")?.getString("position") ?: return@withContext
    val albumCover = songData.getObj("radioTracks")?.getString("coverUrl") ?: return@withContext

    activity.details = "Current song (#$placement):"
    activity.state = "$artist - $title"
    activity.assets().largeImage = albumCover

    if (latest != title) {
        activity.timestamps().start = Instant.now()
        latest = title
        scrobbled = false
    } else {
        if (!scrobbled && (Instant.now().toEpochMilli() - activity.timestamps().start.toEpochMilli()) > 60000) {
            Track.scrobble(artist, title, (activity.timestamps().start.toEpochMilli() / 1000L).toInt(), lastFmSession)
            scrobbled = true
            println("Scrobbled $artist - $title (#$placement)")
        }
    }

    Track.updateNowPlaying(artist, title, lastFmSession)
    core.activityManager().updateActivity(activity)
}

fun checkInput() {
    println("Press enter to shut down.")
    readLine()
    println("Shutting down...")
    run = false
}

fun JsonObject.getObj(path: String): JsonObject? = get(path)?.asJsonObject
fun JsonObject.getArray(path: String): JsonArray? = get(path)?.asJsonArray
fun JsonObject.getString(path: String): String? = get(path)?.asString