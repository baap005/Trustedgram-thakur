package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** TrustedGram feed: which joined channels appear in the feed + the feed's display options. */
public class TrustedFeed {

    /** Set to true when the channel selection / options changed, so the feed reloads. */
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

    // ---- display options (same names as the exteraGram feed settings)

    public static boolean isWidePosts() { return prefs().getBoolean("feed_wide", true); }
    public static void setWidePosts(boolean v) { prefs().edit().putBoolean("feed_wide", v).apply(); dirty = true; }

    public static boolean isUnreadCounter() { return prefs().getBoolean("feed_unread_counter", true); }
    public static void setUnreadCounter(boolean v) { prefs().edit().putBoolean("feed_unread_counter", v).apply(); dirty = true; }

    public static boolean isShowArchive() { return prefs().getBoolean("feed_archive", false); }
    public static void setShowArchive(boolean v) { prefs().edit().putBoolean("feed_archive", v).apply(); dirty = true; }

    /** Hide the text of posts and show only the media (compact mode). */
    public static boolean isCompact() { return prefs().getBoolean("feed_compact", false); }
    public static void setCompact(boolean v) { prefs().edit().putBoolean("feed_compact", v).apply(); dirty = true; }

    /** Newest posts first (default) or oldest first like a chat. */
    public static boolean isNewestFirst() { return prefs().getBoolean("feed_newest_first", true); }
    public static void setNewestFirst(boolean v) { prefs().edit().putBoolean("feed_newest_first", v).apply(); dirty = true; }

    /** Broadcast channels the user has joined (from the chat list). */
    public static ArrayList<TLRPC.Chat> getChannels(int account) {
        ArrayList<TLRPC.Chat> out = new ArrayList<>();
        MessagesController mc = MessagesController.getInstance(account);
        boolean archive = isShowArchive();
        for (TLRPC.Dialog d : mc.getAllDialogs()) {
            if (d == null || d.id >= 0) continue;
            if (!archive && d.folder_id == 1) continue;
            TLRPC.Chat chat = mc.getChat(-d.id);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || ChatObject.isNotInChat(chat)) continue;
            out.add(chat);
        }
        return out;
    }

    /** Channels that are actually shown in the feed (selected and visible under the archive option). */
    public static ArrayList<TLRPC.Chat> getIncludedChannels(int account) {
        ArrayList<TLRPC.Chat> out = new ArrayList<>();
        for (TLRPC.Chat c : getChannels(account)) if (isIncluded(c.id)) out.add(c);
        return out;
    }

    public static int getUnreadCount(int account) {
        int total = 0;
        MessagesController mc = MessagesController.getInstance(account);
        for (TLRPC.Chat c : getIncludedChannels(account)) {
            TLRPC.Dialog d = mc.dialogs_dict.get(-c.id);
            if (d != null) total += Math.max(0, d.unread_count);
        }
        return total;
    }

    /** Marks every included channel as read, in the feed and in the original channel. */
    public static void markAllRead(int account) {
        MessagesController mc = MessagesController.getInstance(account);
        for (TLRPC.Chat c : getIncludedChannels(account)) {
            TLRPC.Dialog d = mc.dialogs_dict.get(-c.id);
            if (d == null || d.unread_count <= 0) continue;
            // explicit user action: let it through even when ghost mode blocks read receipts
            if (TrustedGhost.isBlockRead()) TrustedGhost.allowNextRead();
            mc.markDialogAsRead(d.id, d.top_message, d.top_message, d.last_message_date, false, 0, 0, true, 0);
        }
        dirty = true;
    }
}
