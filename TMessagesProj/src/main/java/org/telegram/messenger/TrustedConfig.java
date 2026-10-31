package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.TrustedPopup;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * TrustedGram remote config (Supabase). Only the public anon key is used here.
 * Row id=1 of table app_config holds a jsonb column "tgram_settings":
 * { "items": [ { "key": "ghost_mode", "title": "Ghost mode", "desc": "...", "visible": true } ] }
 */
public class TrustedConfig {

    public static final String SUPABASE_URL = "https://lecmoprsrkfqpjvlcvhb.supabase.co";
    public static final String SUPABASE_ANON_KEY = "sb_publishable_TWaj6-vFDmj3LGbaBhEOLg_BnB5Dy3G";

    /**
     * BUILD NUMBER of this APK. This is the ONLY place you change it.
     * Rule: every release, add 1 here, build, and type the SAME number in the admin panel
     * (App update > Latest version code). You can always read it inside the app:
     * TGram Settings > bottom line "Build N". Users with a smaller number get the update popup.
     * History: 1 and 2 were released before; this build is 3.
     */
    public static final int VERSION_CODE = 3;

    private static long lastFetch;
    private static android.app.Dialog shownDialog;
    private static int updateShownFor;

    public static class Item {
        public String key, title, desc;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("tgram_prefs", Context.MODE_PRIVATE);
    }

    /** Items the admin panel currently shows to users (cached copy, works offline). */
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

    /** Value of a user toggle. Feature code calls this, e.g. TrustedConfig.isOn("ghost_mode"). */
    public static boolean isOn(String key) {
        return prefs().getBoolean("on_" + key, false);
    }

    public static void setOn(String key, boolean value) {
        prefs().edit().putBoolean("on_" + key, value).apply();
    }

    /** Downloads the latest config in the background; calls onDone on the UI thread. */
    public static void refresh(Runnable onDone) {
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL(SUPABASE_URL + "/rest/v1/app_config?id=eq.1&select=*");
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("apikey", SUPABASE_ANON_KEY);
                c.setRequestProperty("Authorization", "Bearer " + SUPABASE_ANON_KEY);
                if (c.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONArray rows = new JSONArray(sb.toString());
                    if (rows.length() > 0) {
                        JSONObject row = rows.getJSONObject(0);
                        JSONObject ts = row.optJSONObject("tgram_settings");
                        prefs().edit().putString("tgram_settings", ts == null ? "{}" : ts.toString())
                            .putString("config_row", row.toString()).apply();
                        lastFetch = System.currentTimeMillis();
                        TrustedBadges.reload();
                        TrustedBadges.sweep();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (c != null) c.disconnect();
            }
            if (onDone != null) AndroidUtilities.runOnUIThread(onDone);
        });
    }

    // ------------------------------------------------------------------ update + announcement popups

    /** Call from LaunchActivity.onResume. Shows cached popups at once, then refreshes in the background. */
    public static void check(Activity activity) {
        showPopups(activity);
        if (System.currentTimeMillis() - lastFetch > 5 * 60 * 1000L) {
            lastFetch = System.currentTimeMillis();
            refresh(() -> showPopups(activity));
        }
    }

    private static void showPopups(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) return;
            if (shownDialog != null && shownDialog.isShowing()) return;
            String raw = prefs().getString("config_row", null);
            if (raw == null) return;
            JSONObject row = new JSONObject(raw);

            int latest = row.optInt("latest_version_code", 0);
            String url = row.optString("apk_url", "");
            boolean popup = row.optBoolean("update_popup_enabled", false);
            boolean force = row.optBoolean("force_update", false);
            if (popup && latest > VERSION_CODE && url.startsWith("https://")) {
                if (force || updateShownFor != latest) {
                    updateShownFor = latest;
                    showUpdate(activity, row, url, force);
                    return;
                }
            }

            int annId = row.optInt("announcement_id", 0);
            if (row.optBoolean("announcement_enabled", false) && annId > prefs().getInt("seen_announcement", 0)) {
                String title = row.optString("announcement_title", "");
                String text = row.optString("announcement_text", "");
                if (title.isEmpty() && text.isEmpty()) return;
                prefs().edit().putInt("seen_announcement", annId).apply();
                // optional extras, used only if the admin panel sends them
                String btnText = row.optString("announcement_button_text", "");
                String btnUrl = row.optString("announcement_button_url", "");
                final boolean hasLink = btnUrl.startsWith("https://") || btnUrl.startsWith("http://");
                TrustedPopup p = new TrustedPopup(activity)
                    .setBadge("ANNOUNCEMENT")
                    .setTitle(title.isEmpty() ? "TrustedGram" : title)
                    .setMessage(text)
                    .setPrimary(hasLink ? (btnText.isEmpty() ? "Open" : btnText) : "Got it", hasLink ? () -> Browser.openUrl(activity, btnUrl) : null);
                if (hasLink) p.setSecondary("Close", null);
                shownDialog = p.show();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void showUpdate(Activity activity, JSONObject row, String url, boolean force) {
        String name = row.optString("latest_version_name", "");
        String log = row.optString("changelog", "");
        TrustedPopup p = new TrustedPopup(activity)
            .setBadge(force ? "REQUIRED UPDATE" : "NEW VERSION")
            .setTitle(force ? "Update required" : "Update available")
            .setChip(name.isEmpty() ? null : "v" + name.replaceFirst("^[vV]", ""))
            .setMessage("TrustedGram " + (name.isEmpty() ? "" : name + " ") + "is ready." + (force ? "\nYou must update to keep using the app." : ""))
            .setNotes(log)
            .setPrimary("Update now", () -> Browser.openUrl(activity, url));
        if (force) p.setCancelable(false); else p.setSecondary("Later", null);
        shownDialog = p.show();
    }
}
