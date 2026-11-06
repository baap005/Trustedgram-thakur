package org.telegram.messenger;

import android.app.Activity;
import android.widget.Toast;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.TrustedPopup;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * TrustedGram remote config (Supabase, public anon key only).
 * Tables: app_config (row id=1) and verified.
 */
public class TrustedConfig {

    public static final String SUPABASE_URL = "https://lecmoprsrkfqpjvlcvhb.supabase.co";
    public static final String SUPABASE_ANON_KEY = "sb_publishable_TWaj6-vFDmj3LGbaBhEOLg_BnB5Dy3G";
    public static final String RELEASES_URL = "https://t.me/trustedgramreleases";

    /**
     * Build number of THIS build. Raise it by 1 for every release and put the same number in the
     * admin panel (App update > Latest version code). It is shown in TGram Settings.
     */
    public static final int VERSION_CODE = 4;
    public static final String VERSION_NAME = "1.4";

    private static long lastFetch;
    private static Dialog shownDialog;

    public static class Item {
        public String key, title, desc;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("tgram_prefs", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ simple local options

    public static String getTitleText() {
        String t = prefs().getString("title_text", "TrustedGram");
        return t == null || t.trim().isEmpty() ? "TrustedGram" : t;
    }

    public static void setTitleText(String text) {
        prefs().edit().putString("title_text", text == null ? "" : text.trim()).apply();
    }

    /** 0 = app name, 1 = first name of the account, 2 = custom text. */
    public static int getTitleMode() {
        return prefs().getInt("title_mode", 0);
    }

    public static void setTitleMode(int mode) {
        prefs().edit().putInt("title_mode", mode).apply();
    }

    /** iOS look: iOS colours, always-on liquid glass, green switches. Colours apply after restart. */
    public static boolean isIosStyle() {
        return prefs().getBoolean("ios_style", true);
    }

    public static void setIosStyle(boolean v) {
        prefs().edit().putBoolean("ios_style", v).apply();
    }

    public static boolean isTitleCentered() {
        return prefs().getBoolean("title_center", true);
    }

    public static void setTitleCentered(boolean v) {
        prefs().edit().putBoolean("title_center", v).apply();
    }

    /** The text at the top of the chat list. */
    public static String getChatListTitle(int account) {
        int mode = getTitleMode();
        if (mode == 1) {
            TLRPC.User u = UserConfig.getInstance(account).getCurrentUser();
            if (u != null && u.first_name != null && !u.first_name.trim().isEmpty()) return u.first_name.trim();
        } else if (mode == 2) {
            return getTitleText();
        }
        return "TrustedGram";
    }

    /** Show ID and DC in profiles: 0 = hide, 1 = Telegram API id, 2 = Bot API id. */
    public static int getIdDcMode() {
        return prefs().getInt("id_dc_mode", 1);
    }

    public static void setIdDcMode(int mode) {
        prefs().edit().putInt("id_dc_mode", mode).apply();
    }

    public static boolean isHideStories() {
        return prefs().getBoolean("hide_stories", false);
    }

    public static void setHideStories(boolean v) {
        prefs().edit().putBoolean("hide_stories", v).apply();
    }

    public static boolean isHideFloatingButton() {
        return prefs().getBoolean("hide_fab", false);
    }

    public static void setHideFloatingButton(boolean v) {
        prefs().edit().putBoolean("hide_fab", v).apply();
    }

    /**
     * "Center Title": puts the title in the middle of the top bar on every screen. Runs when the screen stack changes
     * and again shortly after, because a screen builds its top bar a moment after it is added.
     */
    public static void centerTitles(final org.telegram.ui.ActionBar.INavigationLayout layout) {
        if (layout == null) return;
        Runnable r = () -> {
            try {
                for (org.telegram.ui.ActionBar.BaseFragment f : layout.getFragmentStack()) {
                    if (f != null && f.getActionBar() != null) f.getActionBar().setCenterTitleEnabled(isTitleCentered());
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        };
        r.run();
        AndroidUtilities.runOnUIThread(r, 120);
        AndroidUtilities.runOnUIThread(r, 450);
    }

    public static boolean isGlassBlur() {
        return prefs().getBoolean("glass_blur", true);
    }

    public static void setGlassBlur(boolean v) {
        prefs().edit().putBoolean("glass_blur", v).apply();
    }

    /** Use collapsing iOS-style large titles on TrustedGram settings subpages. */
    public static boolean isLargeTitles() {
        return prefs().getBoolean("large_titles", true);
    }

    public static void setLargeTitles(boolean value) {
        prefs().edit().putBoolean("large_titles", value).apply();
    }

    // ------------------------------------------------------------------ admin-managed switches

    public static java.util.ArrayList<Item> getItems() {
        java.util.ArrayList<Item> out = new java.util.ArrayList<>();
        try {
            String raw = prefs().getString("tgram_settings", null);
            if (raw == null) return out;
            JSONArray arr = new JSONObject(raw).optJSONArray("items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.optBoolean("visible", true)) continue;
                Item it = new Item();
                it.key = o.optString("key");
                it.title = o.optString("title");
                it.desc = o.optString("desc");
                if (it.key.isEmpty() || it.title.isEmpty()) continue;
                out.add(it);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return out;
    }

    public static boolean isOn(String key) {
        return prefs().getBoolean("on_" + key, false);
    }

    public static void setOn(String key, boolean value) {
        prefs().edit().putBoolean("on_" + key, value).apply();
    }

    // ------------------------------------------------------------------ network

    private static String get(String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(SUPABASE_URL + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("apikey", SUPABASE_ANON_KEY);
            c.setRequestProperty("Authorization", "Bearer " + SUPABASE_ANON_KEY);
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Downloads config and verified list in the background; onDone runs on the UI thread. */
    public static void refresh(Runnable onDone) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String cfg = get("/rest/v1/app_config?id=eq.1&select=*");
                if (cfg != null) {
                    JSONArray rows = new JSONArray(cfg);
                    if (rows.length() > 0) {
                        JSONObject row = rows.getJSONObject(0);
                        JSONObject ts = row.optJSONObject("tgram_settings");
                        prefs().edit().putString("tgram_settings", ts == null ? "{}" : ts.toString())
                            .putString("config_row", row.toString()).apply();
                        lastFetch = System.currentTimeMillis();
                    }
                }
                String ver = get("/rest/v1/verified?select=id,kind");
                if (ver != null) TrustedVerified.setRows(new JSONArray(ver));
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (onDone != null) AndroidUtilities.runOnUIThread(onDone);
        });
    }

    // ------------------------------------------------------------------ updates

    /**
     * What the admin panel published. update_mode:
     *   "button" - no popup at all, an "Update available" button appears in the app (default)
     *   "popup"  - same, plus one popup per new version (not repeated at every start)
     *   "force"  - builds older than min_version_code (or older than latest when it is 0) must update, others get "popup"
     * Older rows without update_mode still work: force_update -> force, update_popup_enabled -> popup, else button.
     */
    public static class UpdateInfo {
        public int latest;
        public int min;
        public String name = "";
        public String url = "";
        public String changelog = "";
        public String sha256 = "";
        public long size;
        public String mode = "button";
        /** a newer build exists */
        public boolean available;
        /** this build is not allowed to run any more */
        public boolean blocked;
    }

    public static UpdateInfo getUpdateInfo() {
        UpdateInfo u = new UpdateInfo();
        try {
            String raw = prefs().getString("config_row", null);
            if (raw == null) return u;
            JSONObject row = new JSONObject(raw);
            u.latest = row.optInt("latest_version_code", 0);
            u.min = row.optInt("min_version_code", 0);
            u.name = row.optString("latest_version_name", "");
            u.url = row.optString("apk_url", "");
            u.changelog = row.optString("changelog", "");
            u.sha256 = row.optString("apk_sha256", "");
            u.size = row.optLong("apk_size", 0);
            String mode = row.optString("update_mode", "");
            if (mode.isEmpty()) {
                mode = row.optBoolean("force_update", false) ? "force" : row.optBoolean("update_popup_enabled", false) ? "popup" : "button";
            }
            u.mode = mode;
            u.available = u.latest > VERSION_CODE && u.url.startsWith("https://");
            int minNeeded = u.min > 0 ? u.min : ("force".equals(mode) ? u.latest : 0);
            u.blocked = u.available && minNeeded > VERSION_CODE;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return u;
    }

    public static boolean isUpdateAvailable() {
        return getUpdateInfo().available;
    }

    /** Opens the in-app update screen (download, then install). */
    public static void openUpdate(Activity activity) {
        UpdateInfo u = getUpdateInfo();
        if (activity == null || activity.isFinishing() || !u.available) return;
        if (shownDialog != null && shownDialog.isShowing()) return;
        shownDialog = org.telegram.ui.TrustedUpdateDialog.show(activity, u, u.blocked);
    }

    /** Call from LaunchActivity.onResume. */
    public static void check(Activity activity) {
        TrustedUpdater.cleanup();
        TrustedUpdater.onResume(activity);
        showPopups(activity);
        if (System.currentTimeMillis() - lastFetch > 5 * 60 * 1000L) {
            lastFetch = System.currentTimeMillis();
            refresh(() -> showPopups(activity));
        }
    }

    /** "Check for updates": refreshes right away and shows the update screen, or says that you are up to date. */
    public static void checkNow(final Activity activity) {
        lastFetch = System.currentTimeMillis();
        refresh(() -> {
            if (activity == null || activity.isFinishing()) return;
            if (isUpdateAvailable()) openUpdate(activity);
            else Toast.makeText(activity, "You have the latest version (" + VERSION_NAME + ")", Toast.LENGTH_SHORT).show();
        });
    }

    private static void showPopups(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) return;
            if (shownDialog != null && shownDialog.isShowing()) return;
            String raw = prefs().getString("config_row", null);
            if (raw == null) return;
            JSONObject row = new JSONObject(raw);

            UpdateInfo u = getUpdateInfo();
            if (u.blocked) {
                openUpdate(activity);
                return;
            }
            // one popup per version (remembered, so it does not come back at every start)
            if (u.available && ("popup".equals(u.mode) || "force".equals(u.mode)) && prefs().getInt("seen_update_popup", 0) < u.latest) {
                prefs().edit().putInt("seen_update_popup", u.latest).apply();
                openUpdate(activity);
                return;
            }

            int annId = row.optInt("announcement_id", 0);
            if (row.optBoolean("announcement_enabled", false) && annId > prefs().getInt("seen_announcement", 0)) {
                String title = row.optString("announcement_title", "");
                String text = row.optString("announcement_text", "");
                if (title.isEmpty() && text.isEmpty()) return;
                prefs().edit().putInt("seen_announcement", annId).apply();
                shownDialog = TrustedPopup.show(activity, title.isEmpty() ? "TrustedGram" : title, text, "OK", null, null, false);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
