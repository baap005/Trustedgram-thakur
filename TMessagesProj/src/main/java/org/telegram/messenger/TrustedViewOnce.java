package org.telegram.messenger;

import android.content.SharedPreferences;

public final class TrustedViewOnce {
    private static final String PREFS = "tgram_prefs";
    private static final String KEY_SAVE = "trusted_save_view_once_media";

    private TrustedViewOnce() {}

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    public static boolean isSaveEnabled() {
        return preferences().getBoolean(KEY_SAVE, false);
    }

    public static void setSaveEnabled(boolean enabled) {
        preferences().edit().putBoolean(KEY_SAVE, enabled).apply();
    }
}
