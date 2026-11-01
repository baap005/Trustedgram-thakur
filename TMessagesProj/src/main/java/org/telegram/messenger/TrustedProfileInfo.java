package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

/** ID and DC text for profiles ("Show ID and DC" in TGram Settings > General). */
public class TrustedProfileInfo {

    private static final String[] DC_NAMES = {
        "", "Miami FL, USA", "Amsterdam, NL", "Miami FL, USA", "Amsterdam, NL", "Singapore, SG"
    };

    public static boolean isEnabled() {
        return TrustedConfig.getIdDcMode() != 0;
    }

    /** First line: the id. Mode 1 = Telegram API id, mode 2 = Bot API id (channels / supergroups get the -100 prefix). */
    public static String getId(TLRPC.User user, TLRPC.Chat chat) {
        boolean bot = TrustedConfig.getIdDcMode() == 2;
        if (user != null) return String.valueOf(user.id);
        if (chat != null) {
            if (!bot) return String.valueOf(chat.id);
            if (ChatObject.isChannel(chat)) return "-100" + chat.id;
            return "-" + chat.id;
        }
        return "";
    }

    /** Second line, e.g. "DC1, Miami FL, USA". */
    public static String getDc(TLRPC.User user, TLRPC.Chat chat) {
        return getDc(user, chat, UserConfig.selectedAccount);
    }

    public static String getDc(TLRPC.User user, TLRPC.Chat chat, int account) {
        int dc = 0;
        if (user != null && user.photo != null) dc = user.photo.dc_id;
        else if (chat != null && chat.photo != null) dc = chat.photo.dc_id;
        if (dc <= 0 && user != null && UserObject.isUserSelf(user)) {
            try {
                dc = org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentDatacenterId();
            } catch (Throwable ignore) {
            }
        }
        if (dc <= 0) return "DC unknown (no profile photo)";
        String name = dc < DC_NAMES.length ? DC_NAMES[dc] : "";
        return "DC" + dc + (name.isEmpty() ? "" : ", " + name);
    }

    /**
     * Rough "created after" checkpoints (user id -> unix time). Telegram does not expose the registration date,
     * so it is estimated from the id: we only claim "later than" the newest checkpoint below the id.
     * Newer checkpoints are deliberately conservative; add more here to make the estimate tighter.
     */
    private static final long[][] ID_CHECKPOINTS = {
        {2768409L, 1383264000L},     // 2013-11-01
        {7679610L, 1388448000L},     // 2013-12-31
        {11538514L, 1391212000L},    // 2014-02-01
        {15835244L, 1392940000L},    // 2014-02-21
        {38015510L, 1393632000L},    // 2014-03-01
        {63263518L, 1414454000L},    // 2014-10-28
        {101260938L, 1425600000L},   // 2015-03-06
        {130029930L, 1441324000L},   // 2015-09-04
        {200000000L, 1451606400L},   // 2016-01-01
        {500000000L, 1483228800L},   // 2017-01-01
        {1000000000L, 1577836800L},  // 2020-01-01
        {2000000000L, 1609459200L},  // 2021-01-01
        {5000000000L, 1622505600L},  // 2021-06-01
        {6000000000L, 1640995200L},  // 2022-01-01
        {7000000000L, 1701388800L},  // 2023-12-01
        {8000000000L, 1733011200L},  // 2024-12-01
    };

    /** "You created your account later than December 1." (or the same for another user); null for chats / unknown. */
    public static String getCreatedText(TLRPC.User user, TLRPC.Chat chat) {
        if (user == null) {
            return null;
        }
        long ts = 0;
        for (long[] c : ID_CHECKPOINTS) {
            if (user.id >= c[0]) ts = c[1];
        }
        if (ts == 0) {
            return UserObject.isUserSelf(user) ? "You created your account a long time ago." : "This account was created a long time ago.";
        }
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(ts * 1000L);
        boolean sameYear = then.get(java.util.Calendar.YEAR) == java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        String date = new java.text.SimpleDateFormat(sameYear ? "MMMM d" : "MMMM d, yyyy", java.util.Locale.ENGLISH).format(then.getTime());
        return (UserObject.isUserSelf(user) ? "You created your account later than " : "This account was created later than ") + date + ".";
    }
}
