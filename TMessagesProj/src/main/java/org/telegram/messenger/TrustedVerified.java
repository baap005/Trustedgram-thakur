package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.HashSet;
import java.util.Set;

/**
 * Accounts you mark as verified in the admin panel. They get Telegram's verified flag inside the app, and the
 * badge image itself is the TrustedGram logo badge (res/drawable-*/verified_*.webp).
 */
public class TrustedVerified {

    private static volatile boolean loaded;
    private static final Set<Long> userIds = new HashSet<>();   // kinds: user, bot
    private static final Set<Long> chatIds = new HashSet<>();   // kinds: channel, group
    private static final Set<String> granted = new HashSet<>(); // flags we set ourselves ("u:1", "c:2")

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("tgram_prefs", Context.MODE_PRIVATE);
    }

    private static synchronized void load() {
        if (loaded) return;
        try {
            String raw = prefs().getString("verified_rows", null);
            if (raw != null) fill(new JSONArray(raw));
            granted.addAll(prefs().getStringSet("verified_granted", new HashSet<>()));
        } catch (Exception e) {
            FileLog.e(e);
        }
        loaded = true;
    }

    private static void fill(JSONArray rows) throws Exception {
        userIds.clear();
        chatIds.clear();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.getJSONObject(i);
            long id = o.optLong("id", 0);
            if (id <= 0) continue;
            String kind = o.optString("kind", "user");
            if (kind.equals("channel") || kind.equals("group")) chatIds.add(id); else userIds.add(id);
        }
    }

    public static synchronized boolean isListedUser(long id) {
        load();
        return userIds.contains(id);
    }

    public static synchronized boolean isListedChat(long id) {
        load();
        return chatIds.contains(id);
    }

    public static void apply(TLRPC.User user) {
        if (user == null || !isListedUser(user.id) || user.verified) return;
        user.verified = true;
        synchronized (TrustedVerified.class) { granted.add("u:" + user.id); }
    }

    public static void apply(TLRPC.Chat chat) {
        if (chat == null || !isListedChat(chat.id) || chat.verified) return;
        chat.verified = true;
        synchronized (TrustedVerified.class) { granted.add("c:" + chat.id); }
    }

    /** Called after a download. Runs on a background thread. */
    public static void setRows(JSONArray rows) {
        try {
            load();
            Set<String> removed;
            synchronized (TrustedVerified.class) {
                fill(rows);
                removed = new HashSet<>();
                for (String g : granted) {
                    long id = Long.parseLong(g.substring(2));
                    boolean stillListed = g.startsWith("u:") ? userIds.contains(id) : chatIds.contains(id);
                    if (!stillListed) removed.add(g);
                }
                granted.removeAll(removed);
                prefs().edit().putString("verified_rows", rows.toString()).putStringSet("verified_granted", new HashSet<>(granted)).apply();
            }
            final Set<String> toRevoke = removed;
            AndroidUtilities.runOnUIThread(() -> applyToLoaded(toRevoke));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void applyToLoaded(Set<String> revoke) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) continue;
            MessagesController mc = MessagesController.getInstance(a);
            for (Long id : new HashSet<>(userIds)) apply(mc.getUser(id));
            for (Long id : new HashSet<>(chatIds)) apply(mc.getChat(id));
            for (String g : revoke) {
                long id = Long.parseLong(g.substring(2));
                if (g.startsWith("u:")) {
                    TLRPC.User u = mc.getUser(id);
                    if (u != null) u.verified = false;
                } else {
                    TLRPC.Chat c = mc.getChat(id);
                    if (c != null) c.verified = false;
                }
            }
            NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
        }
    }
}
