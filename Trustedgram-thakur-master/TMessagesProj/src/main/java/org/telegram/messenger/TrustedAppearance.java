package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

/** TrustedGram appearance options for the chat list (title text, hide stories, hide floating button). */
public class TrustedAppearance {

    public static final String[] TITLE_NAMES = {"TrustedGram", "Username", "First Name", "Chats"};

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("tgram_prefs", Context.MODE_PRIVATE);
    }

    public static int getTitleMode() { return prefs().getInt("ap_title", 0); }
    public static void setTitleMode(int v) { prefs().edit().putInt("ap_title", v).apply(); }

    public static boolean isHideStories() { return prefs().getBoolean("ap_hide_stories", false); }
    public static void setHideStories(boolean v) { prefs().edit().putBoolean("ap_hide_stories", v).apply(); }

    public static boolean isHideFloating() { return prefs().getBoolean("ap_hide_fab", false); }
    public static void setHideFloating(boolean v) { prefs().edit().putBoolean("ap_hide_fab", v).apply(); }

    /** Text shown as the chat list title. */
    public static String getTitle(int account) {
        TLRPC.User u = UserConfig.getInstance(account).getCurrentUser();
        switch (getTitleMode()) {
            case 1:
                if (u != null && !android.text.TextUtils.isEmpty(u.username)) return "@" + u.username;
                break;
            case 2:
                if (u != null && !android.text.TextUtils.isEmpty(u.first_name)) return u.first_name;
                break;
            case 3:
                return "Chats";
        }
        return "TrustedGram";
    }
}
