package uz.notecam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Geo-tagli kamera (NoteCam uslubida):
 *  - suratga oladi,
 *  - GPS'dan koordinata + manzilni oladi,
 *  - rasm ustiga vaqt/sana/joy yozuvini (watermark) "kuydiradi" —
 *    bu yozuv Telegram orqali "Foto" qilib yuborilsa ham yo'qolmaydi,
 *  - EXIF'ga ham GPS va vaqtni yozadi (rasm "Fayl" qilib yuborilsa saqlanadi).
 *
 * MUHIM: Telegram rasmni "Foto" sifatida yuborganda EXIF'ni o'chiradi, shuning
 * uchun asosiy himoya — rasm ustidagi ko'rinadigan yozuv. EXIF esa rasm
 * "Fayl/Hujjat" sifatida yuborilganda saqlanadi.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var infoOverlay: TextView
    private lateinit var statusText: TextView
    private lateinit var captureButton: Button

    private var imageCapture: ImageCapture? = null
    private lateinit var fused: FusedLocationProviderClient

    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastAddress: String = ""

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val permLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(this, "Kamera ruxsati berilmadi", Toast.LENGTH_LONG).show()
            finish()
        }
        if (hasLocationPermission()) startLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        infoOverlay = findViewById(R.id.infoOverlay)
        statusText = findViewById(R.id.statusText)
        captureButton = findViewById(R.id.captureButton)

        fused = LocationServices.getFusedLocationProviderClient(this)
        captureButton.setOnClickListener { takePhoto() }

        val needed = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) needed += Manifest.permission.CAMERA
        if (!hasLocationPermission()) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needed.isEmpty()) {
            startCamera(); startLocation()
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    // ---------------- Kamera ----------------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Kamerani ochib bo'lmadi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- Joylashuv ----------------
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        // Oxirgi ma'lum joy — tez ko'rsatish uchun
        fused.lastLocation.addOnSuccessListener { loc -> loc?.let { onNewLocation(it) } }
        // Aniq, yangi joy
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc -> loc?.let { onNewLocation(it) } }
    }

    private fun onNewLocation(loc: Location) {
        lastLocation = loc
        resolveAddress(loc)
        runOnUiThread { updateOverlay() }
    }

    private fun resolveAddress(loc: Location) {
        Thread {
            try {
                val geo = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val list = geo.getFromLocation(loc.latitude, loc.longitude, 1)
                if (!list.isNullOrEmpty()) {
                    val a = list[0]
                    lastAddress = listOfNotNull(
                        a.thoroughfare, a.subLocality, a.locality, a.adminArea
                    ).distinct().joinToString(", ")
                    runOnUiThread { updateOverlay() }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Geocoder: ${e.message}")
            }
        }.start()
    }

    private fun overlayText(): String {
        val sb = StringBuilder()
        sb.append(dateFmt.format(Date())).append('\n')
        val loc = lastLocation
        if (loc != null) {
            sb.append(String.format(Locale.US, "GPS: %.6f, %.6f", loc.latitude, loc.longitude))
            if (loc.hasAccuracy()) sb.append(String.format(Locale.US, "  (±%.0fm)", loc.accuracy))
            if (lastAddress.isNotBlank()) sb.append('\n').append(lastAddress)
        } else {
            sb.append("GPS: aniqlanmoqda...")
        }
        return sb.toString()
    }

    private fun updateOverlay() {
        infoOverlay.text = overlayText()
    }

    // ---------------- Suratga olish ----------------
    private fun takePhoto() {
        val capture = imageCapture ?: return
        statusText.text = "Olinmoqda..."
        captureButton.isEnabled = false

        val cacheFile = File(cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(cacheFile).build()

        capture.takePicture(output, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    captureButton.isEnabled = true
                    statusText.text = "Xatolik"
                    Toast.makeText(this@MainActivity, "Surat xatosi: ${exc.message}", Toast.LENGTH_LONG).show()
                }

                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    // Vaqt va joyni shu ondagi holatdan muhrlaymiz
                    val stampTime = Date()
                    val loc = lastLocation
                    val addr = lastAddress
                    Thread { processAndSave(cacheFile, stampTime, loc, addr) }.start()
                }
            })
    }

    private fun processAndSave(cacheFile: File, time: Date, loc: Location?, addr: String) {
        try {
            // 1) Bitmap'ni o'qib, EXIF orientatsiyasiga qarab to'g'rilaymiz
            val raw = BitmapFactory.decodeFile(cacheFile.absolutePath)
                ?: throw IllegalStateException("Rasmni o'qib bo'lmadi")
            val oriented = applyOrientation(cacheFile, raw)

            // 2) Rasm ustiga ko'rinadigan yozuv (watermark)
            val stamped = drawWatermark(oriented, time, loc, addr)

            // 3) JPEG'ga qaytarib, EXIF GPS/vaqt yozamiz
            val bytes = ByteArrayOutputStream().use { bos ->
                stamped.compress(Bitmap.CompressFormat.JPEG, 92, bos); bos.toByteArray()
            }
            val stampedFile = File(cacheDir, "stamped_${System.currentTimeMillis()}.jpg")
            stampedFile.writeBytes(bytes)
            writeExif(stampedFile, time, loc)

            // 4) Galereyaga (Pictures/NoteCam) saqlaymiz
            val name = "NC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(time) + ".jpg"
            val savedTo = saveToGallery(stampedFile, name)

            runOnUiThread {
                captureButton.isEnabled = true
                statusText.text = "Saqlandi"
                Toast.makeText(this, "Saqlandi: $savedTo", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                captureButton.isEnabled = true
                statusText.text = "Xatolik"
                Toast.makeText(this, "Saqlab bo'lmadi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            cacheFile.delete()
        }
    }

    private fun applyOrientation(file: File, bmp: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            when (o) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                else -> return bmp
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Exception) { bmp }
    }

    private fun drawWatermark(src: Bitmap, time: Date, loc: Location?, addr: String): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val lines = mutableListOf<String>()
        lines += dateFmt.format(time)
        if (loc != null) {
            lines += String.format(Locale.US, "GPS: %.6f, %.6f", loc.latitude, loc.longitude)
            if (addr.isNotBlank()) lines += addr
        }

        // Rasm kengligiga qarab shrift o'lchami
        val textSize = bmp.width * 0.028f
        val pad = bmp.width * 0.02f
        val lineGap = textSize * 1.35f

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            setShadowLayer(textSize * 0.12f, 0f, 0f, Color.BLACK)
        }
        val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }

        val blockH = lineGap * lines.size + pad
        val top = bmp.height - blockH
        canvas.drawRect(0f, top, bmp.width.toFloat(), bmp.height.toFloat(), bg)

        var y = top + pad + textSize
        for (line in lines) {
            canvas.drawText(line, pad, y, text)
            y += lineGap
        }
        return bmp
    }

    private fun writeExif(file: File, time: Date, loc: Location?) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(time)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dt)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
            exif.setAttribute(ExifInterface.TAG_MAKE, "NoteCam")
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "NoteCam")
            if (loc != null) {
                exif.setLatLong(loc.latitude, loc.longitude)
                exif.setAltitude(if (loc.hasAltitude()) loc.altitude else 0.0)
            }
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w("MainActivity", "EXIF yozilmadi: ${e.message}")
        }
    }

    private fun saveToGallery(file: File, name: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NoteCam")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Galereyaga yozib bo'lmadi")
        contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        file.delete()
        return "Pictures/NoteCam/$name"
    }

    // ---------------- yordamchilar ----------------
    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
}
