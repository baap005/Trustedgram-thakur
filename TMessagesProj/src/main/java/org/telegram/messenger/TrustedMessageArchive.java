package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

/**
 * Local, account-scoped snapshots of messages immediately before Telegram applies
 * an edit or a channel deletion update. This never changes server state.
 */
public final class TrustedMessageArchive {
    private static final String PREFS = "trusted_message_archive";
    private static final String KEY_PREFIX = "account_";
    private static final int MAX_ITEMS = 500;

    private TrustedMessageArchive() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    public static void archiveBeforeEdit(int account, long dialogId, int messageId) {
        archive(account, dialogId, messageId, "edited");
    }

    public static void archiveBeforeDelete(int account, long dialogId, int messageId) {
        archive(account, dialogId, messageId, "deleted");
    }

    private static void archive(int account, long dialogId, int messageId, String event) {
        try {
            if (ApplicationLoader.applicationContext == null || messageId == 0) return;
            TLRPC.Message message = MessagesStorage.getInstance(account).getMessage(dialogId, messageId);
            if (message == null) return;

            String text = message.message == null ? "" : message.message;
            if (text.trim().isEmpty() && message.media == null) return;

            String key = KEY_PREFIX + account;
            JSONArray old = new JSONArray(prefs().getString(key, "[]"));
            JSONObject item = new JSONObject();
            item.put("dialog_id", dialogId);
            item.put("message_id", messageId);
            item.put("date", message.date);
            item.put("event", event);
            item.put("text", text);
            item.put("has_media", message.media != null);
            item.put("sender_id", DialogObject.getPeerDialogId(message.from_id));

            // Avoid duplicate snapshots when Telegram replays an update.
            for (int i = old.length() - 1; i >= 0; i--) {
                JSONObject previous = old.optJSONObject(i);
                if (previous != null
                        && previous.optLong("dialog_id") == dialogId
                        && previous.optInt("message_id") == messageId
                        && event.equals(previous.optString("event"))) {
                    return;
                }
            }
            JSONArray next = new JSONArray();
            next.put(item);
            int start = Math.max(0, old.length() - (MAX_ITEMS - 1));
            for (int i = start; i < old.length(); i++) {
                JSONObject previous = old.optJSONObject(i);
                if (previous != null) next.put(previous);
            }
            prefs().edit().putString(key, next.toString()).apply();
        } catch (Throwable error) {
            FileLog.e(error);
        }
    }

    public static JSONArray getArchivedMessages(int account) {
        try {
            return new JSONArray(prefs().getString(KEY_PREFIX + account, "[]"));
        } catch (Exception e) {
            FileLog.e(e);
            return new JSONArray();
        }
    }

    public static void clear(int account) {
        prefs().edit().remove(KEY_PREFIX + account).apply();
    }
}
