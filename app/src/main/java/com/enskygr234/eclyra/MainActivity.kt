package com.enskygr234.eclyra

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.enskygr234.eclyra.ui.ChatScreen
import com.enskygr234.eclyra.ui.EclyraTheme
import com.enskygr234.eclyra.ui.SettingsScreen
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var visible = false
            private set
    }

    private val vm: AssistantViewModel by viewModels()
    private var screen by mutableStateOf("chat")
    private var overlayGranted by mutableStateOf(false)
    private var listenAfterPermission = false
    private var photoUri: Uri? = null

    private val basePermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (listenAfterPermission) {
            listenAfterPermission = false
            if (res[Manifest.permission.RECORD_AUDIO] == true || has(Manifest.permission.RECORD_AUDIO)) vm.startListening()
            else Toast.makeText(this, "Konuşabilmem için mikrofon izni gerekiyor", Toast.LENGTH_LONG).show()
        }
    }

    private val smsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(this, "SMS izni verilmedi, mesajlar ekranda hazırlanacak", Toast.LENGTH_LONG).show()
    }

    private val speechDialog = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == RESULT_OK) vm.onDialogResult(text)
    }

    private fun openSpeechDialog() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Eclyra dinliyor…")
        try {
            speechDialog.launch(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Ses tanıma bulunamadı. Play Store'dan \"Google\" uygulamasını yükle/güncelle.", Toast.LENGTH_LONG).show()
        }
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = photoUri
        if (ok && u != null) loadImage(u)
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.onDialogNeeded = { openSpeechDialog() }
        setContent {
            EclyraTheme {
                BackHandler(enabled = screen != "chat") { screen = "chat" }
                if (screen == "settings") {
                    SettingsScreen(
                        vm = vm,
                        onBack = { screen = "chat" },
                        onRequestPermissions = { permLauncher.launch(basePermissions) },
                        onRequestSms = { smsLauncher.launch(Manifest.permission.SEND_SMS) },
                        overlayGranted = overlayGranted
                    )
                } else {
                    ChatScreen(
                        vm = vm,
                        onSettings = { screen = "settings" },
                        onMic = { mic() },
                        onCamera = { camera() },
                        onGallery = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                }
            }
        }
        if (!vm.prefs.askedPermissions) {
            vm.prefs.askedPermissions = true
            permLauncher.launch(basePermissions)
        }
        handleIntent(intent)
    }

    override fun onDestroy() {
        vm.onDialogNeeded = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val fromWake = i.getBooleanExtra(WakeWordService.EXTRA_WAKE, false)
        val assist = i.action == Intent.ACTION_ASSIST || i.action == Intent.ACTION_VOICE_COMMAND
        if (fromWake) showOverLock(true)
        if (fromWake || assist) {
            i.removeExtra(WakeWordService.EXTRA_WAKE)
            screen = "chat"
            window.decorView.postDelayed({ mic(forceStart = true) }, 450)
        }
    }

    private fun showOverLock(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(f) else window.clearFlags(f)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        overlayGranted = Settings.canDrawOverlays(this)
        // Uygulama açıkken arka plandaki dinleme mikrofonu bırakır
        if (vm.prefs.wakeWord && WakeModel.ready(this) && has(Manifest.permission.RECORD_AUDIO)) {
            WakeWordService.startPaused(this)
        }
    }

    override fun onStop() {
        super.onStop()
        visible = false
        showOverLock(false)
        if (!isChangingConfigurations) {
            vm.stopListeningOnly()
            vm.stopWakeTest()
            if (vm.prefs.wakeWord) WakeWordService.send(this, WakeWordService.ACTION_RESUME)
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun mic(forceStart: Boolean = false) {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            listenAfterPermission = true
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (forceStart) vm.startListening() else vm.toggleMic()
    }

    private fun camera() {
        val dir = File(cacheDir, "images").apply { mkdirs() }
        val f = File(dir, "soru_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        photoUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Kamera açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImage(uri: Uri) {
        val bmp = decodeScaled(this, uri, 1400)
        if (bmp == null) {
            Toast.makeText(this, "Görsel açılamadı", Toast.LENGTH_SHORT).show()
            return
        }
        vm.pendingImage = bmp
    }
}

/** Görseli bellek dostu şekilde küçültüp doğru yöne çevirir. */
fun decodeScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = try {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    if (bmp != null) {
        val rot = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        if (rot != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
    }
    bmp
} catch (e: Exception) {
    null
}
