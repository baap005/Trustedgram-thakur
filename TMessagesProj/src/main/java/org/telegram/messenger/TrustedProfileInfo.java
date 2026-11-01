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
        int dc = 0;
        if (user != null && user.photo != null) dc = user.photo.dc_id;
        else if (chat != null && chat.photo != null) dc = chat.photo.dc_id;
        if (dc <= 0) return "DC unknown";
        String name = dc < DC_NAMES.length ? DC_NAMES[dc] : "";
        return "DC" + dc + (name.isEmpty() ? "" : ", " + name);
    }
}
