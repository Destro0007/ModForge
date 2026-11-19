package com.modforge.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

/** Talks to the GitHub API. All calls are blocking, call them from Dispatchers.IO. */
class GitHub(private val token: String, private val repo: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun req(path: String) = Request.Builder()
        .url("https://api.github.com/repos/$repo$path")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun call(b: Request.Builder): Pair<Int, String> =
        http.newCall(b.build()).execute().use { it.code to (it.body?.string() ?: "") }

    fun defaultBranch(): String {
        val (code, body) = call(req(""))
        check(code == 200) { "Cannot reach the repo ($code). Check your token and repo name." }
        return JSONObject(body).getString("default_branch")
    }

    fun putFile(path: String, bytes: ByteArray) {
        val json = JSONObject()
            .put("message", "upload $path")
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .toString()
            .toRequestBody("application/json".toMediaType())
        val (code, body) = call(req("/contents/$path").put(json))
        check(code == 200 || code == 201) { "Upload failed ($code): ${body.take(200)}" }
    }

    fun dispatch(branch: String, jobId: String) {
        val json = JSONObject()
            .put("ref", branch)
            .put("inputs", JSONObject().put("job_id", jobId))
            .toString()
            .toRequestBody("application/json".toMediaType())
        val (code, body) = call(req("/actions/workflows/mod-apk.yml/dispatches").post(json))
        check(code == 204) { "Could not start the cloud job ($code): ${body.take(200)}" }
    }

    /** Returns (conclusion, url) of this job's workflow run, or (null, null) if not found yet. */
    fun runState(jobId: String): Pair<String?, String?> {
        val (code, body) = call(req("/actions/workflows/mod-apk.yml/runs?per_page=10"))
        if (code != 200) return null to null
        val runs = JSONObject(body).getJSONArray("workflow_runs")
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            if (r.optString("display_title") == "mod $jobId") {
                val conclusion = if (r.isNull("conclusion")) null else r.getString("conclusion")
                return conclusion to r.optString("html_url")
            }
        }
        return null to null
    }

    fun release(jobId: String): JSONObject? {
        val (code, body) = call(req("/releases/tags/job-$jobId"))
        return if (code == 200) JSONObject(body) else null
    }

    fun download(assetId: Long, out: File) {
        val r = req("/releases/assets/$assetId").header("Accept", "application/octet-stream").build()
        http.newCall(r).execute().use { resp ->
            check(resp.isSuccessful) { "Download failed (${resp.code})" }
            resp.body!!.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
}

suspend fun runJob(
    ctx: Context,
    gh: GitHub,
    uri: Uri,
    instruction: String,
    status: (String) -> Unit
): File {
    val id = System.currentTimeMillis().toString()

    status("Reading the APK...")
    val bytes = withContext(Dispatchers.IO) {
        ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    }
    check(bytes.size < 40_000_000) { "This APK is over 40 MB. Too big for this simple version." }

    status("Uploading to your GitHub repo...")
    val branch = withContext(Dispatchers.IO) {
        gh.putFile("jobs/$id/input.apk", bytes)
        gh.putFile("jobs/$id/instruction.txt", instruction.toByteArray())
        gh.defaultBranch()
    }
    withContext(Dispatchers.IO) { gh.dispatch(branch, id) }

    status("Cloud job started. This usually takes 3 to 8 minutes. You can leave the app open and wait.")
    repeat(180) {
        delay(10_000)
        val rel = withContext(Dispatchers.IO) { gh.release(id) }
        val assets = rel?.optJSONArray("assets")
        var assetId: Long? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == "modded.apk") assetId = a.getLong("id")
            }
        }
        if (rel != null && assetId != null) {
            status("Downloading the modded APK...")
            val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
            val out = File(dir, "modded.apk")
            withContext(Dispatchers.IO) { gh.download(assetId, out) }
            status("Done!\n\n" + rel.optString("body"))
            return out
        }
        val (conclusion, url) = withContext(Dispatchers.IO) { gh.runState(id) }
        if (conclusion != null && conclusion != "success") {
            error("The cloud job ended with: $conclusion.\nOpen this page to see why:\n$url")
        }
    }
    error("Timed out waiting for the cloud job.")
}

fun install(ctx: Context, f: File) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("modforge", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var repo by remember { mutableStateOf(prefs.getString("repo", "") ?: "") }
    var showSetup by remember { mutableStateOf(token.isBlank() || repo.isBlank()) }

    if (showSetup) {
        SetupScreen(token, repo) { t, r ->
            prefs.edit().putString("token", t.trim()).putString("repo", r.trim()).apply()
            token = t.trim()
            repo = r.trim()
            showSetup = false
        }
    } else {
        ModScreen(GitHub(token, repo)) { showSetup = true }
    }
}

@Composable
fun SetupScreen(token: String, repo: String, onSave: (String, String) -> Unit) {
    var t by remember { mutableStateOf(token) }
    var r by remember { mutableStateOf(repo) }
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ModForge setup", style = MaterialTheme.typography.headlineMedium)
        Text("One-time setup. See the README for the 4 short steps.")
        OutlinedTextField(
            r, { r = it }, label = { Text("GitHub repo (owner/name)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            t, { t = it }, label = { Text("GitHub token") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { onSave(t, r) }, enabled = t.isNotBlank() && r.contains("/")) { Text("Save") }
    }
}

@Composable
fun ModScreen(gh: GitHub, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apk by remember { mutableStateOf<Uri?>(null) }
    var instruction by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Pick an APK and describe the change.") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        apk = it
        result = null
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ModForge", style = MaterialTheme.typography.headlineMedium)

        Button(onClick = { picker.launch("*/*") }, enabled = !busy) {
            Text(if (apk == null) "1. Choose APK" else "1. APK selected ✓")
        }

        OutlinedTextField(
            instruction, { instruction = it },
            label = { Text("2. What should change?") },
            minLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !busy
        )

        Button(
            onClick = {
                scope.launch {
                    busy = true
                    result = null
                    try {
                        result = runJob(ctx, gh, apk!!, instruction) { status = it }
                    } catch (e: Exception) {
                        status = "Failed: ${e.message}"
                    }
                    busy = false
                }
            },
            enabled = !busy && apk != null && instruction.isNotBlank()
        ) { Text("3. Mod it") }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status)

        result?.let { f ->
            Button(onClick = { install(ctx, f) }) { Text("Install modded APK") }
            Text("If install fails, uninstall the original app first. The modded copy is signed with a different key.")
        }

        TextButton(onClick = onSettings, enabled = !busy) { Text("Settings") }
    }
}
