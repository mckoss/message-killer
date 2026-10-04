package com.mckoss.message_killer

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Telephony
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    // A pool, so quick calls (status, Spam folder list, export) aren't stuck
    // behind a long scan or delete. Cleanup serializes scans and deletes itself.
    private val worker = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private var pendingRoleResult: MethodChannel.Result? = null
    private var pendingPermissionResult: MethodChannel.Result? = null
    private var launchAction: String? = null

    private val settings by lazy { AppSettings(this) }
    private val store by lazy { SpamStore.get(this) }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        launchAction = intent?.getStringExtra(Notifications.EXTRA_ACTION)
        DailyCleanupJob.schedule(this, settings.dailyCleanup)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler(::onMethodCall)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifications.EXTRA_ACTION)?.let { launchAction = it }
    }

    private fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getStatus" -> background(result) { status() }
            "scanProgress" -> result.success(Cleanup.progress)
            "takeLaunchAction" -> result.success(launchAction.also { launchAction = null })
            "requestPermissions" -> requestPermissions(result)
            "openNotificationAccessSettings" -> {
                val intent = if (Build.VERSION.SDK_INT >= 30) {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(this, MessageNotificationListener::class.java).flattenToString(),
                    )
                } else {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
                result.success(launch(intent) || launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
            }
            "openAppSettings" -> result.success(launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            ))
            "openDefaultAppsSettings" -> result.success(
                launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) || launch(Intent(Settings.ACTION_SETTINGS))
            )
            "requestDefaultSmsRole" -> requestDefaultSmsRole(result)
            "scanInbox" -> background(result) {
                val scan = Cleanup.scan(this, forceFull = call.argument<Boolean>("full") == true)
                val stats = Cleanup.lastStats
                mapOf(
                    "scanned" to scan.scanned, "newlyFiled" to scan.newlyFiled, "pending" to scan.pending,
                    "texts" to (stats?.texts ?: 0), "pictureMessages" to (stats?.pictureMessages ?: 0),
                    "full" to (stats?.full ?: true),
                )
            }
            "deletePending" -> background(result) {
                val (deleted, failed) = Cleanup.deletePending(this)
                if (failed == 0) Notifications.cancelCleanupReady(this)
                mapOf("deleted" to deleted, "failed" to failed)
            }
            "listSpam" -> background(result) { store.list().map { it.toMap() } }
            "prunePreview" -> background(result) {
                Cleanup.prunePreview(this).map {
                    mapOf(
                        "sender" to it.sender, "count" to it.count, "oldest" to it.oldest,
                        "newest" to it.newest, "sample" to it.sample,
                    )
                }
            }
            "prune" -> background(result) {
                val (deleted, failed) = Cleanup.prune(this, call.argument<List<String>>("senders") ?: emptyList())
                mapOf("deleted" to deleted, "failed" to failed)
            }
            "listRestorable" -> background(result) { Cleanup.restorable(this).map { it.toMap() } }
            // Separate from getStatus: re-checks every deleted entry, so the home
            // screen shows first and this count fills in after.
            "countRestorable" -> background(result) { Cleanup.restorable(this).size }
            "confirmSpam" -> background(result) {
                store.confirm((call.argument<List<Number>>("ids") ?: emptyList()).map { it.toLong() })
                null
            }
            "restore" -> background(result) {
                val ids = (call.argument<List<Number>>("ids") ?: emptyList()).map { it.toLong() }
                mapOf("restored" to Cleanup.restore(this, ids), "requested" to ids.size)
            }
            "listFlagged" -> background(result) {
                store.flaggedSenders().map { mapOf("sender" to it.sender, "addedAt" to it.addedAt, "messages" to it.messages) }
            }
            "unflagSender" -> background(result) {
                val sender = call.argument<String>("sender") ?: ""
                store.untaint(sender)
                // Texts waiting to be deleted only because this sender was flagged stay in the inbox.
                val normalized = Classifier.normalizeSender(sender)
                store.removePendingWhere {
                    Classifier.normalizeSender(it.sender) == normalized && Classifier.TAINTED_REASON in it.reasons
                }
            }
            "exportFlagged" -> background(result) {
                val export = SpamExporter.exportSenderReport(this)
                mapOf("count" to export.count, "files" to export.files)
            }
            "exportSpam" -> background(result) {
                val export = SpamExporter.export(this, pendingOnly = call.argument<Boolean>("pendingOnly") == true)
                mapOf("count" to export.count, "files" to export.files)
            }
            "removeSpam" -> background(result) {
                val id = call.argument<Number>("id")!!.toLong()
                if (call.argument<Boolean>("allowSender") == true) {
                    store.get(id)?.let {
                        settings.allowSender(it.sender)
                        store.untaint(it.sender)
                    }
                }
                store.remove(id)
                null
            }
            "allowSender" -> background(result) {
                val sender = call.argument<String>("sender") ?: ""
                settings.allowSender(sender)
                store.untaint(sender)
                val normalized = Classifier.normalizeSender(sender)
                store.removePendingWhere { Classifier.normalizeSender(it.sender) == normalized }
            }
            "getSettings" -> result.success(mapOf(
                "liveFilter" to settings.liveFilter,
                "dailyCleanup" to settings.dailyCleanup,
                "customKeywords" to settings.customKeywords,
                "allowedSenders" to settings.allowedSenders,
                "categories" to settings.enabledCategories.map { it.key },
            ))
            "updateSettings" -> {
                call.argument<Boolean>("liveFilter")?.let { settings.liveFilter = it }
                call.argument<Boolean>("dailyCleanup")?.let {
                    settings.dailyCleanup = it
                    DailyCleanupJob.schedule(this, it)
                }
                call.argument<List<String>>("customKeywords")?.let { settings.customKeywords = it }
                call.argument<List<String>>("allowedSenders")?.let { settings.allowedSenders = it }
                call.argument<List<String>>("categories")?.let { keys ->
                    settings.enabledCategories = keys.mapNotNull { Classifier.Category.fromKey(it) }.toSet()
                }
                result.success(null)
            }
            "classify" -> {
                val r = settings.classifier().classify(
                    call.argument<String>("text") ?: "", call.argument<String>("sender"),
                )
                result.success(mapOf(
                    "score" to r.score, "spam" to r.isSpam, "category" to r.category?.key, "reasons" to r.reasons,
                ))
            }
            else -> result.notImplemented()
        }
    }

    private fun status(): Map<String, Any?> {
        store.purgeExpired()
        val defaultSms = Telephony.Sms.getDefaultSmsPackage(this)
        val previous = settings.previousDefaultSmsPackage
        return mapOf(
            "smsPermission" to Cleanup.hasSmsPermission(this),
            "notificationAccess" to hasNotificationAccess(),
            "contactsPermission" to ContactsChecker(this).hasPermission,
            "isDefaultSmsApp" to Cleanup.isDefaultSmsApp(this),
            "defaultSmsApp" to appLabel(defaultSms),
            "previousDefaultSmsApp" to appLabel(previous),
            "liveFilter" to settings.liveFilter,
            "dailyCleanup" to settings.dailyCleanup,
            "counts" to store.counts(),
            "version" to versionInfo(),
            "retentionDays" to SpamStore.RETENTION_DAYS,
        )
    }

    /** "0.16.0 (build 42)" from the installed package. */
    private fun versionInfo(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (build ${info.longVersionCode})"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, MessageNotificationListener::class.java)
        return enabled.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
    }

    private fun appLabel(pkg: String?): String? {
        if (pkg == null) return null
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun requestPermissions(result: MethodChannel.Result) {
        val wanted = buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) {
            result.success(true)
            return
        }
        pendingPermissionResult?.success(false)
        pendingPermissionResult = result
        requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            pendingPermissionResult?.success(Cleanup.hasSmsPermission(this))
            pendingPermissionResult = null
        }
    }

    private fun requestDefaultSmsRole(result: MethodChannel.Result) {
        if (Cleanup.isDefaultSmsApp(this)) {
            result.success(true)
            return
        }
        // Remember the real messaging app so we can forward to it and send the user back.
        Telephony.Sms.getDefaultSmsPackage(this)?.let { settings.previousDefaultSmsPackage = it }
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
            result.success(false)
            return
        }
        pendingRoleResult?.success(false)
        pendingRoleResult = result
        @Suppress("DEPRECATION")
        startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS), REQUEST_SMS_ROLE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SMS_ROLE) {
            pendingRoleResult?.success(resultCode == RESULT_OK || Cleanup.isDefaultSmsApp(this))
            pendingRoleResult = null
        }
    }

    private fun launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun background(result: MethodChannel.Result, block: () -> Any?) {
        worker.execute {
            try {
                val value = block()
                main.post { result.success(value) }
            } catch (e: Exception) {
                main.post { result.error("native_error", e.message, null) }
            }
        }
    }

    companion object {
        private const val CHANNEL = "message_killer/native"
        private const val REQUEST_SMS_ROLE = 7001
        private const val REQUEST_PERMISSIONS = 7002
    }
}
