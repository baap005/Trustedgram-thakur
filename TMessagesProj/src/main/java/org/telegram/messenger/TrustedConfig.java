package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

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
                URL u = new URL(SUPABASE_URL + "/rest/v1/app_config?id=eq.1&select=tgram_settings");
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
                        JSONObject ts = rows.getJSONObject(0).optJSONObject("tgram_settings");
                        prefs().edit().putString("tgram_settings", ts == null ? "{}" : ts.toString()).apply();
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
}
