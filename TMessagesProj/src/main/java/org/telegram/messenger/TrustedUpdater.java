package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-app updater: downloads the new APK inside TrustedGram (with progress), checks its SHA-256 when the admin panel
 * provided one, then opens the Android installer. After the install TrustedUpdateReceiver starts the app again.
 */
public class TrustedUpdater {

    public static final int IDLE = 0, DOWNLOADING = 1, READY = 2, ERROR = 3;

    public interface Listener {
        void onUpdaterChanged();
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile int state = IDLE;
    private static volatile int percent;
    private static volatile long doneBytes, totalBytes;
    private static volatile String error = "";
    private static volatile boolean cancelled;
    private static volatile boolean installWhenAllowed;
    private static volatile int downloadingCode;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("tgram_prefs", Context.MODE_PRIVATE);
    }

    public static int getState() { return state; }
    public static int getPercent() { return percent; }
    public static long getDoneBytes() { return doneBytes; }
    public static long getTotalBytes() { return totalBytes; }
    public static String getError() { return error; }

    public static void addListener(Listener l) { listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : listeners) l.onUpdaterChanged();
        });
    }

    public static File apkFile() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/update");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "TrustedGram.apk");
    }

    /** True when an APK for this version code was already downloaded and verified. */
    public static boolean isDownloaded(int versionCode) {
        return prefs().getInt("update_ready_code", 0) == versionCode && apkFile().exists() && apkFile().length() > 0;
    }

    /** Called on app start: removes an APK that is already installed (or older than this build). */
    public static void cleanup() {
        try {
            int ready = prefs().getInt("update_ready_code", 0);
            if (ready != 0 && ready <= TrustedConfig.VERSION_CODE) {
                File f = apkFile();
                if (f.exists()) f.delete();
                prefs().edit().remove("update_ready_code").apply();
                if (state == READY) state = IDLE;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void cancel() {
        cancelled = true;
    }

    public static void reset() {
        if (state != DOWNLOADING) {
            state = IDLE;
            percent = 0;
            error = "";
        }
    }

    public static void start(final String url, final long expectedSize, final String sha256, final int versionCode) {
        if (state == DOWNLOADING) return;
        if (isDownloaded(versionCode)) {
            state = READY;
            percent = 100;
            notifyChanged();
            return;
        }
        state = DOWNLOADING;
        percent = 0;
        doneBytes = 0;
        totalBytes = expectedSize;
        error = "";
        cancelled = false;
        downloadingCode = versionCode;
        notifyChanged();
        new Thread(() -> {
            HttpURLConnection c = null;
            File part = new File(apkFile().getParentFile(), "TrustedGram.part");
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) throw new Exception("Server answered " + c.getResponseCode());
                long len = c.getContentLengthLong();
                if (len > 0) totalBytes = len;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long lastNotify = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled) throw new Exception("cancelled");
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        doneBytes += n;
                        if (totalBytes > 0) percent = (int) Math.min(100, doneBytes * 100 / totalBytes);
                        long now = System.currentTimeMillis();
                        if (now - lastNotify > 150) {
                            lastNotify = now;
                            notifyChanged();
                        }
                    }
                    out.getFD().sync();
                }
                if (sha256 != null && !sha256.trim().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : md.digest()) sb.append(String.format("%02x", b));
                    if (!sb.toString().equalsIgnoreCase(sha256.trim())) throw new Exception("The downloaded file is damaged (checksum does not match). Try again.");
                }
                File dest = apkFile();
                if (dest.exists()) dest.delete();
                if (!part.renameTo(dest)) throw new Exception("Could not save the update file.");
                prefs().edit().putInt("update_ready_code", downloadingCode).apply();
                percent = 100;
                state = READY;
            } catch (Exception e) {
                if (!"cancelled".equals(e.getMessage())) FileLog.e(e);
                part.delete();
                state = cancelled ? IDLE : ERROR;
                error = cancelled ? "" : (e.getMessage() == null ? "Download failed" : e.getMessage());
            } finally {
                if (c != null) c.disconnect();
                notifyChanged();
            }
        }, "TrustedUpdater").start();
    }

    /** Opens the Android installer for the downloaded file. Asks for "install unknown apps" first if needed. */
    public static void install(Activity activity) {
        try {
            File f = apkFile();
            if (!f.exists()) {
                state = IDLE;
                notifyChanged();
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                installWhenAllowed = true;
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
                return;
            }
            installWhenAllowed = false;
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", f);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
            error = "Could not open the installer.";
            state = ERROR;
            notifyChanged();
        }
    }

    /** Call from the activity's onResume: continues the install after the user allowed "install unknown apps". */
    public static void onResume(Activity activity) {
        if (installWhenAllowed && state == READY && Build.VERSION.SDK_INT >= 26 && activity.getPackageManager().canRequestPackageInstalls()) {
            install(activity);
        }
    }
}
