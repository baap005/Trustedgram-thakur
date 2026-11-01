package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TrustedGram verified badges.
 *
 * The admin panel stores user ids and channel ids in the app_config row. Because we do not know
 * the exact column names you used, every key of the row that contains "verif" (or "badge") is read:
 *   - an array of numbers / strings / objects ({"id": 123})
 *   - a comma / space / newline separated string
 *   - a jsonb object ({"users": [...], "channels": [...]}) - nested keys are scanned too
 * Channel ids may be written as 123456789, -123456789 or -100123456789, all of them match.
 *
 * Every user / chat that arrives from the network gets verified=true when it is on the list
 * (see ConnectionsManager hooks), so the normal Telegram badge shows everywhere: chat list,
 * chat header, profile, search, messages.
 */
public class TrustedBadges {

    private static volatile Set<Long> ids = new HashSet<>();
    private static volatile boolean loaded;
    private static final ConcurrentHashMap<Class<?>, Field[]> fieldCache = new ConcurrentHashMap<>();
    private static final Field[] NONE = new Field[0];

    /** Rebuilds the id list from the cached config row. Called after every config refresh. */
    public static void reload() {
        Set<Long> out = new HashSet<>();
        try {
            String raw = ApplicationLoader.applicationContext
                .getSharedPreferences("tgram_prefs", android.content.Context.MODE_PRIVATE).getString("config_row", null);
            if (raw != null) {
                JSONObject row = new JSONObject(raw);
                Iterator<String> keys = row.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String lk = k.toLowerCase();
                    if (lk.contains("verif") || lk.contains("badge")) {
                        // skip plain on/off flags
                        Object v = row.opt(k);
                        if (v instanceof Boolean) continue;
                        collect(v, out);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        ids = out;
        loaded = true;
    }

    private static void collect(Object v, Set<Long> out) {
        if (v == null || v == JSONObject.NULL) return;
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out);
        } else if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object child = o.opt(k);
                if (child instanceof Boolean) {
                    // {"12345": true}
                    if ((Boolean) child) addId(k, out);
                } else if (child instanceof Number || child instanceof String || child instanceof JSONArray || child instanceof JSONObject) {
                    // keys like "id", "user_id", "channel_id", "users", "channels"
                    String lk = k.toLowerCase();
                    if (lk.contains("id") || lk.contains("user") || lk.contains("channel") || lk.contains("chat")) collect(child, out);
                    else addId(k, out);
                }
            }
        } else if (v instanceof Number) {
            addId(String.valueOf(((Number) v).longValue()), out);
        } else if (v instanceof String) {
            for (String part : ((String) v).split("[,;\\s\\[\\]\"]+")) addId(part, out);
        }
    }

    private static void addId(String s, Set<Long> out) {
        if (s == null) return;
        s = s.trim().replace("@", "");
        if (s.isEmpty()) return;
        try {
            long id = Long.parseLong(s);
            if (id < 0) id = -id;
            // -100xxxxxxxxxx -> xxxxxxxxxx
            if (id > 1000000000000L) id -= 1000000000000L;
            if (id != 0) out.add(id);
        } catch (NumberFormatException ignore) {
        }
    }

    public static boolean isVerifiedId(long id) {
        if (!loaded) reload();
        if (id < 0) id = -id;
        if (id > 1000000000000L) id -= 1000000000000L;
        return ids.contains(id);
    }

    public static boolean hasAny() {
        if (!loaded) reload();
        return !ids.isEmpty();
    }

    public static void apply(TLRPC.User user) {
        if (user != null && !user.verified && isVerifiedId(user.id)) user.verified = true;
    }

    public static void apply(TLRPC.Chat chat) {
        if (chat != null && !chat.verified && isVerifiedId(chat.id)) chat.verified = true;
    }

    /** Walks a server response (or update) and marks every listed user / chat as verified. */
    public static void apply(TLObject response) {
        if (response == null || !hasAny()) return;
        try {
            Field[] fields = fieldCache.get(response.getClass());
            if (fields == null) {
                ArrayList<Field> list = new ArrayList<>();
                Class<?> c = response.getClass();
                while (c != null && c != Object.class) {
                    for (Field f : c.getDeclaredFields()) {
                        String n = f.getName();
                        if ((n.equals("users") || n.equals("chats") || n.equals("user") || n.equals("chat")
                            || n.equals("updates") || n.equals("update") || n.equals("full_chat") || n.equals("full_user")
                            || n.equals("participants") || n.equals("results") || n.equals("dialogs") || n.equals("peer"))
                            && !f.getType().isPrimitive()) {
                            f.setAccessible(true);
                            list.add(f);
                        }
                    }
                    c = c.getSuperclass();
                }
                fields = list.isEmpty() ? NONE : list.toArray(new Field[0]);
                fieldCache.put(response.getClass(), fields);
            }
            for (Field f : fields) walk(f.get(response), 0);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void walk(Object o, int depth) {
        if (o == null || depth > 3) return;
        if (o instanceof TLRPC.User) {
            apply((TLRPC.User) o);
        } else if (o instanceof TLRPC.Chat) {
            apply((TLRPC.Chat) o);
        } else if (o instanceof List) {
            for (Object x : (List<?>) o) {
                if (x instanceof TLRPC.User) apply((TLRPC.User) x);
                else if (x instanceof TLRPC.Chat) apply((TLRPC.Chat) x);
                else if (x instanceof TLObject && depth < 3) apply((TLObject) x);
            }
        } else if (o instanceof TLObject && depth < 3) {
            apply((TLObject) o);
        }
    }

    /** Public helper for UI code that wants to draw its own badge. */
    public static boolean isVerified(TLRPC.Chat chat) {
        return chat != null && (chat.verified || isVerifiedId(chat.id));
    }

    public static boolean isVerified(TLRPC.User user) {
        return user != null && (user.verified || isVerifiedId(user.id));
    }

    /** Marks users / channels that are already in memory (cache) so the badge shows without waiting for the network. */
    public static void sweep() {
        try {
            if (!hasAny()) return;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (!UserConfig.getInstance(a).isClientActivated()) continue;
                MessagesController mc = MessagesController.getInstance(a);
                boolean changed = false;
                for (long id : ids) {
                    TLRPC.User u = mc.getUser(id);
                    if (u != null && !u.verified) { u.verified = true; changed = true; }
                    TLRPC.Chat c = mc.getChat(id);
                    if (c != null && !c.verified) { c.verified = true; changed = true; }
                }
                if (changed) {
                    final int acc = a;
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(acc)
                        .postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_CHAT_NAME));
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
