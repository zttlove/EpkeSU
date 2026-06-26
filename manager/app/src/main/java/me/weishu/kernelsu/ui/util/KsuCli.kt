package me.weishu.kernelsu.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.ksuApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * @author weishu
 * @date 2023/1/1.
 */
private const val TAG = "KsuCli"
private const val SHELL_JOB_TIMEOUT_MILLIS = 10_000L
private const val ANDROID_16_API = 36
private const val BUSYBOX = "/data/adb/ksu/bin/busybox"
const val HYBRID_MOUNT_MODULE_ID = "hybrid_mount"
const val BUILTIN_MOUNT_MODE_OVERLAY = "overlay"
const val BUILTIN_MOUNT_MODE_MAGIC = "magic"

private fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"
}

data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}

data class BuiltinMountStatus(
    val moduleId: String = HYBRID_MOUNT_MODULE_ID,
    val moduleName: String = "Hybrid Mount Lite",
    val modulePath: String = "/data/adb/ksu/builtin/hybrid_mount",
    val version: String = "",
    val versionCode: String = "",
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val conflict: String? = null,
    val defaultMode: String = BUILTIN_MOUNT_MODE_OVERLAY,
    val webUi: Boolean = false,
)

data class EpkesuHideStatus(
    val enabled: Boolean = false,
)

object KsuCli {
    private val shellLock = Any()
    private var shell: Shell? = null
    private var globalMntShell: Shell? = null

    val SHELL: Shell
        get() = getCachedShell(false)

    val GLOBAL_MNT_SHELL: Shell
        get() = getCachedShell(true)

    private fun getCachedShell(globalMnt: Boolean): Shell = synchronized(shellLock) {
        val current = if (globalMnt) globalMntShell else shell
        if (current != null && current.isUsableRoot()) {
            return@synchronized current
        }

        current?.closeQuietly()
        val newShell = createRootShell(globalMnt)
        if (globalMnt) {
            globalMntShell = newShell
        } else {
            shell = newShell
        }
        newShell
    }

    fun reset(globalMnt: Boolean = false) = synchronized(shellLock) {
        val current = if (globalMnt) globalMntShell else shell
        current?.closeQuietly()
        if (globalMnt) {
            globalMntShell = null
        } else {
            shell = null
        }
    }
}

private fun Shell.isUsableRoot(): Boolean = runCatching { isRoot }.getOrDefault(false)

private fun Shell.closeQuietly() {
    runCatching { close() }
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) KsuCli.GLOBAL_MNT_SHELL else {
        KsuCli.SHELL
    }
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    val tryKsuShell = {
        if (globalMnt) {
            builder.build(getKsuDaemonPath(), "debug", "su", "-g")
        } else {
            builder.build(getKsuDaemonPath(), "debug", "su")
        }
    }
    val trySuShell = {
        if (globalMnt) {
            builder.build("su", "-mm")
        } else {
            builder.build("su")
        }
    }

    return try {
        tryKsuShell()
    } catch (ksuError: Throwable) {
        Log.w(TAG, "ksu failed: ", ksuError)
        try {
            trySuShell()
        } catch (suError: Throwable) {
            Log.e(TAG, "su failed: ", suError)
            builder.build("sh")
        }
    }
}

fun execKsud(args: String, newShell: Boolean = false): Boolean {
    if (shouldSkipUnsafeKsudCommand()) {
        Log.w(TAG, "skip ksud command without safe root shell: $args")
        return false
    }

    return if (newShell) {
        withNewRootShell {
            ShellUtils.fastCmdResult(this, "${getKsuDaemonPath()} $args")
        }
    } else {
        ShellUtils.fastCmdResult(getRootShell(), "${getKsuDaemonPath()} $args")
    }
}

suspend fun getBuiltinMountStatus(): BuiltinMountStatus = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext BuiltinMountStatus()
    }

    runCatching {
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = withTimeoutOrNull(SHELL_JOB_TIMEOUT_MILLIS) {
            getRootShell().newJob()
                .add("${getKsuDaemonPath()} builtin-mount status")
                .to(stdout, stderr)
                .exec()
        }

        if (result == null) {
            Log.w(TAG, "builtin-mount status timed out")
            KsuCli.reset()
            return@runCatching BuiltinMountStatus()
        }

        if (!result.isSuccess) {
            Log.w(TAG, "builtin-mount status failed: ${stderr.joinToString("\n")}")
            return@runCatching BuiltinMountStatus()
        }

        val obj = JSONObject(stdout.joinToString("\n"))
        val mode = obj.optString("defaultMode", BUILTIN_MOUNT_MODE_OVERLAY)
            .takeIf { it == BUILTIN_MOUNT_MODE_OVERLAY || it == BUILTIN_MOUNT_MODE_MAGIC }
            ?: BUILTIN_MOUNT_MODE_OVERLAY
        BuiltinMountStatus(
            moduleId = obj.optString("moduleId", HYBRID_MOUNT_MODULE_ID),
            moduleName = obj.optString("moduleName", "Hybrid Mount Lite"),
            modulePath = obj.optString("modulePath", "/data/adb/ksu/builtin/hybrid_mount"),
            version = obj.optString("version", ""),
            versionCode = obj.optString("versionCode", ""),
            installed = obj.optBoolean("installed", false),
            enabled = obj.optBoolean("enabled", false),
            conflict = obj.optString("conflict").takeIf { it.isNotBlank() && it != "null" },
            defaultMode = mode,
            webUi = obj.optBoolean("webui", false),
        )
    }.getOrElse {
        Log.w(TAG, "builtin-mount status unavailable", it)
        KsuCli.reset()
        BuiltinMountStatus()
    }
}

fun setBuiltinMountEnabled(enabled: Boolean): Boolean {
    val command = if (enabled) "enable" else "disable"
    return execKsud("builtin-mount $command", true)
}

fun setBuiltinMountDefaultMode(mode: String): Boolean {
    val normalized = if (mode == BUILTIN_MOUNT_MODE_MAGIC) {
        BUILTIN_MOUNT_MODE_MAGIC
    } else {
        BUILTIN_MOUNT_MODE_OVERLAY
    }
    return execKsud("builtin-mount set-default-mode $normalized", true)
}

suspend fun getEpkesuHideStatus(): EpkesuHideStatus = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext EpkesuHideStatus()
    }

    val shell = getRootShell()
    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    val result = shell.newJob()
        .add("${getKsuDaemonPath()} epkesu-hide status")
        .to(stdout, stderr)
        .exec()

    if (!result.isSuccess) {
        Log.w(TAG, "epkesu-hide status failed: ${stderr.joinToString("\n")}")
        return@withContext EpkesuHideStatus()
    }

    runCatching {
        val obj = JSONObject(stdout.joinToString("\n"))
        EpkesuHideStatus(
            enabled = obj.optBoolean("enabled", false),
        )
    }.getOrElse {
        Log.w(TAG, "parse epkesu-hide status failed: ${stdout.joinToString("\n")}", it)
        EpkesuHideStatus()
    }
}

fun setEpkesuHideEnabled(enabled: Boolean): Boolean {
    val command = if (enabled) "enable" else "disable"
    return execKsud("epkesu-hide $command", true)
}

suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext ""
    }

    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature check $feature").to(ArrayList<String>(), null).exec().out
    out.firstOrNull()?.trim().orEmpty()
}

suspend fun getFeaturePersistValue(feature: String): Long? = withContext(Dispatchers.IO) {
    if (shouldSkipUnsafeKsudCommand()) {
        return@withContext null
    }

    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature get --config $feature").to(ArrayList<String>(), null).exec().out
    val valueLine = out.firstOrNull { it.trim().startsWith("Value:") } ?: return@withContext null
    valueLine.substringAfter("Value:").trim().toLongOrNull()
}

fun install() {
    val start = SystemClock.elapsedRealtime()
    val libadbroot = File(ksuApp.applicationInfo.nativeLibraryDir, "libadbroot.so").absolutePath
    val result = execKsud("install --libadbroot $libadbroot", true)
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
}

fun listModules(): String {
    if (shouldSkipUnsafeKsudCommand()) {
        return "[]"
    }

    val shell = getRootShell()

    val result = shell.newJob()
        .add("${getKsuDaemonPath()} module list").to(ArrayList(), ArrayList()).exec()
    if (!result.isSuccess) {
        KsuCli.reset()
        Log.w(TAG, "module list failed: ${result.err.joinToString("\n")}")
        return "[]"
    }
    return result.out.joinToString("\n").ifBlank { "[]" }
}

suspend fun listModulesWithTimeout(timeoutMillis: Long = SHELL_JOB_TIMEOUT_MILLIS): String {
    if (shouldSkipUnsafeKsudCommand()) {
        return "[]"
    }

    val stdout = ArrayList<String>()
    val result = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { cont ->
            val shell = getRootShell()
            shell.newJob()
                .add("${getKsuDaemonPath()} module list")
                .to(stdout, null)
                .submit(Shell.EXECUTOR) { result ->
                    if (cont.isActive) {
                        cont.resume(result)
                    }
                }
        }
    }

    if (result == null) {
        Log.w(TAG, "module list timed out after ${timeoutMillis}ms")
        KsuCli.reset()
        error("module list timed out after ${timeoutMillis}ms")
    }

    if (!result.isSuccess) {
        KsuCli.reset()
        error("module list failed: ${result.err.joinToString("\n")}")
    }

    return result.out.joinToString("\n").ifBlank { "[]" }
}

fun getModuleCount(): Int {
    val result = listModules()
    runCatching {
        val array = JSONArray(result)
        return array.length()
    }.getOrElse { return 0 }
}

fun getSuperuserCount(): Int {
    return Natives.getSuperuserCount()
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable $id"
    } else {
        "module disable $id"
    }
    val result = execKsud(cmd, true)
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun undoUninstallModule(id: String): Boolean {
    val cmd = "module undo-uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}

private fun processUiPrintLine(s: String?): Pair<Int, String?> {
    if (s == null) {
        return Pair(1, null)
    }

    val check1 = s.startsWith("ui_print")
    val trimmed = s.trim()
    val check2 = trimmed.startsWith("ui_print")
    if (!check1 && check2) return Pair(1, null)

    return if (check1) {
        Pair(1, trimmed.drop(8).dropWhile { it.isWhitespace() })
    } else {
        Pair(2, trimmed)
    }
}

private fun flashWithIoAk3(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            val (type, text) = processUiPrintLine(s)
            if (type == 1) {
                text?.let(onStdout)
            } else {
                text?.let(onStderr)
            }
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}

fun flashModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    install()

    val resolver = ksuApp.contentResolver
    with(resolver.openInputStream(uri)) {
        val file = File(ksuApp.cacheDir, "module.zip")
        file.outputStream().use { output ->
            this?.copyTo(output)
        }
        val cmd = "module install ${file.absolutePath}"
        val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
        Log.i("KernelSU", "install module $uri result: $result")

        file.delete()

        return FlashResult(result)
    }
}

fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Shell.Result {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add("${getKsuDaemonPath()} module action $moduleId")
            .to(stdoutCallback, stderrCallback).exec()
    }

    Log.i("KernelSU", "Module runAction result: $result")

    return result
}

fun restoreBoot(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} boot-restore -f", onStdout, onStderr)
    return FlashResult(result)
}

fun uninstallPermanently(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} uninstall --package-name ${BuildConfig.APPLICATION_ID}", onStdout, onStderr)
    return FlashResult(result)
}

@Parcelize
sealed class LkmSelection : Parcelable {
    @Parcelize
    data class LkmUri(val uri: Uri) : LkmSelection()

    @Parcelize
    data class KmiString(val value: String) : LkmSelection()

    @Parcelize
    data object KmiNone : LkmSelection()
}

fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    ota: Boolean,
    partition: String?,
    allowShell: Boolean,
    enableAdb: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val resolver = ksuApp.contentResolver

    val bootFile = bootUri?.let { uri ->
        with(resolver.openInputStream(uri)) {
            val bootFile = File(ksuApp.cacheDir, "boot.img")
            bootFile.outputStream().use { output ->
                this?.copyTo(output)
            }

            bootFile
        }
    }

    var cmd = "boot-patch"

    cmd += if (bootFile == null) {
        // no boot.img, use -f to flash
        " -f"
    } else {
        " -b ${bootFile.absolutePath}"
    }

    if (allowShell) {
        cmd += " --allow-shell"
    }

    if (enableAdb) {
        cmd += " --enable-adbd"
    }

    if (ota) {
        cmd += " -u"
    }

    var lkmFile: File? = null
    when (lkm) {
        is LkmSelection.LkmUri -> {
            lkmFile = with(resolver.openInputStream(lkm.uri)) {
                val file = File(ksuApp.cacheDir, "kernelsu-tmp-lkm.ko")
                file.outputStream().use { output ->
                    this?.copyTo(output)
                }

                file
            }
            cmd += " -m ${lkmFile.absolutePath}"
        }

        is LkmSelection.KmiString -> {
            cmd += " --kmi ${lkm.value}"
        }

        LkmSelection.KmiNone -> {
            // do nothing
        }
    }

    // output dir
    if (bootFile != null) {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        cmd += " -o $downloadsDir"
    }

    partition?.let { part ->
        cmd += " --partition $part"
    }

    val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
    Log.i("KernelSU", "install boot result: ${result.isSuccess}")

    bootFile?.delete()
    lkmFile?.delete()

    // if boot uri is empty, it is direct install, when success, we should show reboot button
    val showReboot = bootUri == null && result.isSuccess // we create a temporary val here, to avoid calc showReboot double
    if (showReboot) { // because we decide do not update ksud when startActivity
        install() // install ksud here
    }
    return FlashResult(result, showReboot)
}

fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        execKsud("soft-reboot", true)
        return
    }
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    ShellUtils.fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

fun flashAnyKernelZip(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    val resolver = ksuApp.contentResolver

    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val tmpFile = File(ksuApp.cacheDir, "anykernel_${timestamp}.zip")
    resolver.openInputStream(uri).use { input ->
        tmpFile.outputStream().use { out ->
            input?.copyTo(out)
        }
    }

    val destZip = tmpFile.absolutePath
    val destZipName = tmpFile.name
    val destDirFile = File(ksuApp.cacheDir, "anykernel3_${timestamp}")
    val destDir = destDirFile.absolutePath

    val cmd = """
        mkdir -p '$destDir' && \
        $BUSYBOX unzip -p -o '$destZip' "META-INF/com/google/android/update-binary" > '$destDir/update-binary' 2>/dev/null && \
        cp '$destZip' '$destDir/$destZipName' 2>/dev/null || true && \
        $BUSYBOX chmod 755 '$destDir/update-binary' && \
        $BUSYBOX chown root:root '$destDir/update-binary' && \
        (cd '$destDir' && \
            if [ -f './update-binary' ] && grep -q "AnyKernel3" './update-binary'; then \
                AKHOME='$destDir/tmp' $BUSYBOX ash '$destDir/update-binary' 3 1 '$destDir/$destZipName'; \
            else \
                echo 'No installer script found' >&2; exit 1; \
            fi)
    """.trimIndent().replace(Regex("\\s+\\\\\\s*"), " ")

    val result = flashWithIoAk3(cmd, onStdout, onStderr)
    try {
        return FlashResult(result, result.isSuccess)
    } finally {
        runCatching {
            createRootShell(true).use { shell ->
                shell.newJob().add("rm -rf '$destDir' '$destZip'").exec()
            }
        }
    }
}

fun rootAvailable(): Boolean {
    return runCatching {
        val available = getRootShell().isRoot
        if (!available) {
            KsuCli.reset()
        }
        available
    }.getOrDefault(false)
}

private fun shouldSkipUnsafeKsudCommand(): Boolean {
    return Build.VERSION.SDK_INT >= ANDROID_16_API && !rootAvailable()
}

private val fallbackSupportedKmis = listOf(
    "android12-5.10",
    "android13-5.10",
    "android13-5.15",
    "android14-5.15",
    "android14-6.1",
    "android15-6.6",
    "android16-6.12",
)

private val kmiNameRegex = Regex("""^android\d+-\d+(?:\.\d+)?$""")

suspend fun getCurrentKmi(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info current-kmi"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
}

suspend fun getSupportedKmis(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info supported-kmis"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.map { it.trim() }
        .filter { it.matches(kmiNameRegex) }
        .distinct()
        .ifEmpty { fallbackSupportedKmis }
}

suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info is-ab-device"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim().toBoolean()
}

suspend fun getDefaultPartition(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    if (shell.isRoot) {
        val cmd = "boot-info default-partition"
        ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
    } else {
        if (!Os.uname().release.contains("android12-")) "init_boot" else "boot"
    }
}

suspend fun getSlotSuffix(ota: Boolean): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = if (ota) {
        "boot-info slot-suffix --ota"
    } else {
        "boot-info slot-suffix"
    }
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
}

suspend fun getAvailablePartitions(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info available-partitions"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

fun hasMagisk(): Boolean {
    val shell = getRootShell(true)
    val result = shell.newJob().add("which magisk").exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    return result.isSuccess
}

fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) {
        return true
    }
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} sepolicy check '$rules'").to(ArrayList(), null)
            .exec()
    return result.isSuccess
}

fun getSepolicy(pkg: String): String {
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} profile get-sepolicy $pkg").to(ArrayList(), null)
            .exec()
    Log.i(TAG, "code: ${result.code}, out: ${result.out}, err: ${result.err}")
    return result.out.joinToString("\n")
}

fun setSepolicy(pkg: String, rules: String): Boolean {
    val shell = getRootShell()
    val result = shell.newJob().add("${getKsuDaemonPath()} profile set-sepolicy $pkg '$rules'")
        .to(ArrayList(), null).exec()
    Log.i(TAG, "set sepolicy result: ${result.code}")
    return result.isSuccess
}

fun listAppProfileTemplates(): List<String> {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile list-templates").to(ArrayList(), null)
        .exec().out
}

fun getAppProfileTemplate(id: String): String {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile get-template '${id}'")
        .to(ArrayList(), null).exec().out.joinToString("\n")
}

fun setAppProfileTemplate(id: String, template: String): Boolean {
    val shell = getRootShell()
    val escapedTemplate = template.replace("\"", "\\\"")
    val cmd = """${getKsuDaemonPath()} profile set-template "$id" "$escapedTemplate'""""
    return shell.newJob().add(cmd)
        .to(ArrayList(), null).exec().isSuccess
}

fun deleteAppProfileTemplate(id: String): Boolean {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile delete-template '${id}'")
        .to(ArrayList(), null).exec().isSuccess
}

fun forceStopApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result = shell.newJob().add("am force-stop$userArg $packageName").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

fun launchApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result =
        shell.newJob()
            .add("cmd package resolve-activity --brief$userArg $packageName | tail -n 1 | xargs cmd activity start-activity$userArg -n")
            .exec()
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String, userId: Int? = null) {
    forceStopApp(packageName, userId)
    launchApp(packageName, userId)
}
