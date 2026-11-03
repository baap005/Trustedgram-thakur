package org.telegram.messenger;

import android.app.Activity;
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
    public static final int VERSION_CODE = 3;
    public static final String VERSION_NAME = "1.3";

    private static long lastFetch;
    private static Dialog shownDialog;
    private static int updateShownFor;

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

    public static boolean isFeedTab() {
        return prefs().getBoolean("feed_tab", true);
    }

    public static void setFeedTab(boolean v) {
        prefs().edit().putBoolean("feed_tab", v).apply();
    }

    public static boolean isTitleCentered() {
        return prefs().getBoolean("title_center", true);
    }

    public static void setTitleCentered(boolean v) {
        prefs().edit().putBoolean("title_center", v).apply();
    }

    public static boolean isFeedUnreadCounter() {
        return prefs().getBoolean("feed_unread", true);
    }

    public static void setFeedUnreadCounter(boolean v) {
        prefs().edit().putBoolean("feed_unread", v).apply();
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

    public static boolean isFeedWide() {
        return prefs().getBoolean("feed_wide", true);
    }

    public static void setFeedWide(boolean v) {
        prefs().edit().putBoolean("feed_wide", v).apply();
    }

    public static boolean isFeedArchive() {
        return prefs().getBoolean("feed_archive", false);
    }

    public static void setFeedArchive(boolean v) {
        prefs().edit().putBoolean("feed_archive", v).apply();
        TrustedFeed.dirty = true;
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

    // ------------------------------------------------------------------ update + announcement popups

    /** Call from LaunchActivity.onResume. */
    public static void check(Activity activity) {
        showPopups(activity);
        if (System.currentTimeMillis() - lastFetch > 5 * 60 * 1000L) {
            lastFetch = System.currentTimeMillis();
            refresh(() -> showPopups(activity));
        }
    }

    /** Used by "Check for updates": forgets earlier "Later" choices and refreshes right away. */
    public static void checkNow(Activity activity) {
        updateShownFor = 0;
        lastFetch = System.currentTimeMillis();
        refresh(() -> showPopups(activity));
    }

    private static void showPopups(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) return;
            if (shownDialog != null && shownDialog.isShowing()) return;
            String raw = prefs().getString("config_row", null);
            if (raw == null) return;
            JSONObject row = new JSONObject(raw);

            int latest = row.optInt("latest_version_code", 0);
            int minVersion = row.optInt("min_version_code", 0);
            String url = row.optString("apk_url", "");
            if (!url.startsWith("https://")) url = RELEASES_URL;
            boolean popup = row.optBoolean("update_popup_enabled", false);
            boolean force = row.optBoolean("force_update", false);
            boolean blocked = minVersion > VERSION_CODE;

            if (blocked) {
                showUpdate(activity, row, url, true, true);
                return;
            }
            if (popup && latest > VERSION_CODE) {
                if (force || updateShownFor != latest) {
                    updateShownFor = latest;
                    showUpdate(activity, row, url, force, false);
                    return;
                }
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

    private static void showUpdate(Activity activity, JSONObject row, String url, boolean blocking, boolean unsupported) {
        String name = row.optString("latest_version_name", "");
        String log = row.optString("changelog", "");
        StringBuilder msg = new StringBuilder();
        if (unsupported) {
            msg.append("This version of TrustedGram is no longer supported.\nPlease install the latest version to continue.");
        } else {
            msg.append("TrustedGram ").append(name.isEmpty() ? "" : name + " ").append("is ready to install.");
            if (blocking) msg.append("\nYou must update to keep using the app.");
        }
        if (!log.isEmpty()) msg.append("\n\n").append(log);
        final String link = url;
        shownDialog = TrustedPopup.show(activity,
            blocking ? "Update required" : "Update available",
            msg.toString(), "Update now", () -> Browser.openUrl(activity, link),
            blocking ? null : "Later", blocking);
    }
}
