package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** TrustedGram feed: which joined channels appear in the feed. Everything is included unless the user turns it off. */
public class TrustedFeed {

    /** Set to true when the channel selection changed, so the feed reloads. */
    public static volatile boolean dirty;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("tgram_prefs", Context.MODE_PRIVATE);
    }

    private static Set<String> excluded() {
        return new HashSet<>(prefs().getStringSet("feed_excluded", new HashSet<>()));
    }

    public static boolean isIncluded(long channelId) {
        return !excluded().contains(String.valueOf(channelId));
    }

    public static void setIncluded(long channelId, boolean included) {
        Set<String> set = excluded();
        if (included) set.remove(String.valueOf(channelId)); else set.add(String.valueOf(channelId));
        prefs().edit().putStringSet("feed_excluded", set).apply();
        dirty = true;
    }

    public static void setAll(int account, boolean included) {
        Set<String> set = excluded();
        for (TLRPC.Chat c : getChannels(account)) {
            if (included) set.remove(String.valueOf(c.id)); else set.add(String.valueOf(c.id));
        }
        prefs().edit().putStringSet("feed_excluded", set).apply();
        dirty = true;
    }

    /** Broadcast channels the user has joined (from the chat list). */
    public static ArrayList<TLRPC.Chat> getChannels(int account) {
        ArrayList<TLRPC.Chat> out = new ArrayList<>();
        MessagesController mc = MessagesController.getInstance(account);
        for (TLRPC.Dialog d : mc.getAllDialogs()) {
            if (d == null || d.id >= 0) continue;
            if (d.folder_id == 1 && !TrustedConfig.isFeedArchive()) continue;
            TLRPC.Chat chat = mc.getChat(-d.id);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || ChatObject.isNotInChat(chat)) continue;
            out.add(chat);
        }
        return out;
    }
}
