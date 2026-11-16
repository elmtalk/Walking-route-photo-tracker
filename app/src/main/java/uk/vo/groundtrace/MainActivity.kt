package uk.vo.groundtrace

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var projectInput: EditText
    private lateinit var surveyorInput: EditText
    private var pendingPhoto: File? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val photosRequest = 202
    private val permissionRequest = 201
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(TextView(this).apply { text = "GroundTrace"; textSize = 28f })
        root.addView(TextView(this).apply { text = "GPS coverage + geotagged photos. Start tracking, then use Mergin Maps. The ongoing notification indicates tracking is running."; textSize = 15f })
        projectInput = EditText(this).apply { hint = "Project name"; setSingleLine(); setText(prefs.getString("project", "")) }
        root.addView(projectInput)
        surveyorInput = EditText(this).apply { hint = "Surveyor"; setSingleLine(); setText(prefs.getString("surveyor", "")) }
        root.addView(surveyorInput)
        fun button(label: String, action: () -> Unit) { root.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
        button("START / RESUME GPS RECORDING") { startTracking() }
        button("STOP TRACKING") { stopTracking() }
        button("TAKE GEOLOCATED PHOTO") { takePhoto() }
        button("EXPORT PROJECT ZIP / KMZ") { exportProject() }
        button("OPEN SAVED PROJECT") { chooseProject() }
        status = TextView(this).apply { textSize = 15f; setPadding(0, 20, 0, 0) }
        root.addView(status)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 301)
        }
        refresh()
    }

    private fun projectName(): String {
        return projectInput.text.toString().trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").take(65)
    }
    private fun folder(): File = File(filesDir, "projects/${projectName()}")
    private fun remember(): Boolean {
        val name = projectName()
        if (name.isBlank()) { toast("Enter a project name"); return false }
        prefs.edit().putString("project", name).putString("surveyor", surveyorInput.text.toString()).apply()
        val dir = folder(); dir.mkdirs()
        val meta = File(dir, "project.txt")
        if (!meta.exists()) meta.writeText("Project: $name\nSurveyor: ${surveyorInput.text}\nCreated: ${Date()}\n")
        return true
    }
    private fun startTracking() {
        if (!remember()) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), permissionRequest)
            return
        }
        launchService()
    }
    private fun launchService() {
        val i = Intent(this, TrackingService::class.java).putExtra("project", projectName())
        try { startForegroundService(i); toast("Recording started"); refresh() }
        catch (e: Exception) { toast("Cannot start tracking: ${e.message}") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) launchService()
        else if (requestCode == permissionRequest) toast("Precise location permission needed for best coverage")
    }
    private fun stopTracking() {
        startService(Intent(this, TrackingService::class.java).setAction("STOP"))
        prefs.edit().putBoolean("recording", false).apply()
        refresh()
    }
    private fun takePhoto() {
        if (!remember()) return
        val last = prefs.getLong("last_fix_time", 0L)
        if (System.currentTimeMillis() - last > 120000L) {
            toast("WARNING: no recent GPS position. Photo may have no accurate position.")
        }
        val dir = File(folder(), "photos"); dir.mkdirs()
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(intent, photosRequest) }
        catch (e: Exception) { toast("No camera available: ${e.message}") }
    }
    @Deprecated("Legacy camera result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == photosRequest) {
            val f = pendingPhoto
            if (resultCode == RESULT_OK && f != null && f.exists() && f.length() > 0) {
                val fix = prefs.getString("last_fix", "") ?: ""
                val age = System.currentTimeMillis() - prefs.getLong("last_fix_time", 0L)
                val point = if (age in 0..120000L) fix else ""
                File(folder(), "photos.csv").appendText("${f.name},${System.currentTimeMillis()},$point\n")
                toast("Photo saved; location ${if (point.isNotBlank()) "attached" else "unavailable"}")
            } else { f?.delete(); toast("Photo cancelled") }
            pendingPhoto = null
            refresh()
        }
    }
    private fun chooseProject() {
        val dir = File(filesDir, "projects")
        val names = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        if (names.isEmpty()) { toast("No saved projects"); return }
        android.app.AlertDialog.Builder(this).setTitle("Open a project")
            .setItems(names.toTypedArray()) { _, which ->
                if (prefs.getBoolean("recording", false) && prefs.getString("project", "") != names[which]) {
                    toast("Stop tracking before changing projects")
                } else { projectInput.setText(names[which]); prefs.edit().putString("project", names[which]).apply(); refresh() }
            }.show()
    }
    private fun points(): List<List<String>> = File(folder(), "track.csv").takeIf { it.exists() }?.readLines()
        ?.map { it.split(",") }?.filter { it.size >= 4 && it[1].toDoubleOrNull() != null && it[2].toDoubleOrNull() != null } ?: emptyList()
    private fun photos(): List<List<String>> = File(folder(), "photos.csv").takeIf { it.exists() }?.readLines()
        ?.map { it.split(",") }?.filter { it.size >= 4 && it[2].toDoubleOrNull() != null && it[3].toDoubleOrNull() != null } ?: emptyList()
    private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun kml(): String {
        val p = points()
        val segments = mutableListOf<MutableList<List<String>>>()
        for (pt in p) {
            if (segments.isEmpty() || (pt[0].toLongOrNull() ?: 0) - (segments.last().lastOrNull()?.get(0)?.toLongOrNull() ?: 0) > 45000L)
                segments.add(mutableListOf())
            segments.last().add(pt)
        }
        val result = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>${xml(projectName())}</name>")
        result.append("<Style id=\"walk\"><LineStyle><color>ff00aa00</color><width>4</width></LineStyle></Style>")
        result.append("<Style id=\"gap\"><LineStyle><color>ff0088ff</color><width>2</width></LineStyle></Style>")
        for ((i, seg) in segments.withIndex()) {
            if (seg.size > 1) result.append("<Placemark><name>Recorded track ${i + 1}</name><styleUrl>#walk</styleUrl><LineString><tessellate>1</tessellate><coordinates>${seg.joinToString(" ") { "${it[2]},${it[1]},0" }}</coordinates></LineString></Placemark>")
            else if (seg.size == 1) result.append("<Placemark><name>GPS fix</name><Point><coordinates>${seg[0][2]},${seg[0][1]},0</coordinates></Point></Placemark>")
            if (i > 0 && segments[i - 1].isNotEmpty() && seg.isNotEmpty()) {
                val a = segments[i - 1].last()
                val b = seg.first()
                result.append("<Placemark><name>INFERRED GAP — not surveyed evidence</name><description>Location points unavailable; straight line is an approximation only.</description><styleUrl>#gap</styleUrl><LineString><coordinates>${a[2]},${a[1]},0 ${b[2]},${b[1]},0</coordinates></LineString></Placemark>")
            }
        }
        for (photo in photos()) {
            result.append("<Placemark><name>${xml(photo[0])}</name><description><![CDATA[<img width='360' src='photos/${photo[0]}'/>]]></description><Point><coordinates>${photo[3]},${photo[2]},0</coordinates></Point></Placemark>")
        }
        return result.append("</Document></kml>").toString()
    }
    private fun exportProject() {
        if (!remember()) return
        val dir = folder()
        val outDir = File(cacheDir, "exports"); outDir.mkdirs()
        val safe = projectName().replace(" ", "_")
        val output = File(outDir, "${safe}_${System.currentTimeMillis()}.zip")
        try {
            val kmzFile = File.createTempFile("groundtrace_", ".kmz", cacheDir)
            ZipOutputStream(FileOutputStream(kmzFile)).use { zip ->
                zip.putNextEntry(ZipEntry("doc.kml")); zip.write(kml().toByteArray()); zip.closeEntry()
                val picDir = File(dir, "photos")
                picDir.listFiles()?.filter { it.isFile }?.forEach { photo ->
                    zip.putNextEntry(ZipEntry("photos/${photo.name}")); photo.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                fun add(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                add("survey.kmz", kmzFile.readBytes())
                add("track.kml", kml().toByteArray())
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val relative = f.relativeTo(dir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            kmzFile.delete()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", output)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Send GroundTrace project ZIP"))
            toast("Project ZIP ready")
        } catch (e: Exception) { toast("Export failed: ${e.message}") }
    }
    private fun refresh() {
        if (!::status.isInitialized) return
        val running = prefs.getBoolean("recording", false)
        val name = projectName()
        val count = if (name.isNotBlank()) points().size else 0
        val photoCount = if (name.isNotBlank()) File(folder(), "photos") .listFiles()?.size ?: 0 else 0
        val lastTime = prefs.getLong("last_fix_time", 0L)
        status.text = "Tracking: ${if (running) "STARTED (check persistent notification)" else "STOPPED"}\n" +
            "Saved GPS points: $count\nPhotos: $photoCount\n" +
            "Last location: ${if (lastTime > 0) Date(lastTime).toString() else "None"}\n\n" +
            "GPS tracks are stored immediately. Unobserved gaps are approximate straight links in exported KML/KMZ, not proof of coverage. " +
            "Keep location enabled and disable battery optimisation for best results."
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ refresh() }, 5000L)
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
