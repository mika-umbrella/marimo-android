/* MainActivity.kt — android port skeleton for marimo.
 * NOT BUILDABLE HERE YET (needs Android SDK/NDK). This is the
 * architecture seed: SAF picker → fd → C core → MediaSession service.
 *
 * Build prerequisites (see README.md):
 *   Android Studio / SDK + NDK, mpv-android (or audio-only libmpv),
 *   curl for android if scrobbling is wanted.
 */

package moe.umbrella.marimo

import android.app.*
import android.content.*
import android.media.*
import android.media.session.*
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile

class MarimoService : Service() {
    // Foreground service: playback must survive the activity dying.
    // Playback: MediaPlayer(setDataSource(context, contentUri)) for
    //   simple needs, or mpv via libmpv + fd:// for gapless fidelity
    //   (mpv-android maintains the build). fd:// <-> ParcelFileDescriptor
    //   is the known-good SAF bridge.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, Notification.Builder(this, "playback")
            .setContentTitle("marimo").build())
        return START_STICKY
    }
}

class MainActivity : ComponentActivity() {

    private val queue = nativeQueueNew(0, 1)   // C queue, opaque handle
    private var treeUri: Uri? = null

    // SAF folder picker: no paths exist on android 11+, this is the
    // only way to grant access to the whole music tree.
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let {
        contentResolver.takePersistableUriPermission(
            it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        treeUri = it
        scanTree(DocumentFile.fromTreeUri(this, it))
    } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickFolder.launch(null)
        setupMediaSession()
    }

    private fun scanTree(dir: DocumentFile) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) scanTree(f)
            else if (f.isFile && isAudio(f.name)) index(f.uri, f.name ?: "?")
        }
    }

    private fun isAudio(name: String) =
        name.endsWith(".flac") || name.endsWith(".mp3") || name.endsWith(".ogg") ||
        name.endsWith(".opus") || name.endsWith(".m4a") || name.endsWith(".wav")

    private fun index(uri: Uri, name: String) {
        // C core reads tags straight from the fd — no copying to cache.
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return
        val fd = pfd.detachFd()
        val t = ByteArray(512); val a = ByteArray(512); val al = ByteArray(512)
        val dur = IntArray(1); val tr = IntArray(1); val dc = IntArray(1)
        if (nativeTagReadFd(fd, t, a, al, dur, tr, dc) == 0) {
            nativeQueueAdd(queue, uri.toString(), name, 0)
            // title/artist/album are the C-side metadata for the UI
        }
    }

    private fun setupMediaSession() {
        // MediaSession replaces mpris on android: lockscreen controls,
        // bluetooth media keys, swipe-down quick settings — for free.
        val session = MediaSession(this, "marimo")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { /* resolve queue[cur] uri, play via service */ }
            override fun onPause() { }
            override fun onSkipToNext() { }
            override fun onSkipToPrevious() { }
        })
        session.setActive(true)
    }

    // ---- JNI (jni/marimo_core.c wraps core/core_api.h) ----
    private external fun nativeQueueNew(shuffle: Int, repeat: Int): Long
    private external fun nativeQueueAdd(q: Long, token: String, name: String, size: Long): Int
    private external fun nativeTagReadFd(
        fd: Int, title: ByteArray, artist: ByteArray, album: ByteArray,
        dur: IntArray, track: IntArray, disc: IntArray): Int

    companion object {
        init { System.loadLibrary("marimo_core") }
    }
}
