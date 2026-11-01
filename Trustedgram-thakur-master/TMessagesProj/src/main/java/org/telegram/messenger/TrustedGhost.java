package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TrustedGram ghost mode. Works at the network layer: certain requests are dropped or changed
 * before they leave the phone (idea borrowed from AyuGram's request hook).
 */
public class TrustedGhost {

    private static final String PREFS = "tgram_prefs";
    private static volatile boolean loaded;
    private static volatile boolean blockRead, hideOnline, hideTyping, readAfterSend = true;
    private static final AtomicInteger allowRead = new AtomicInteger();

    private static void load() {
        if (loaded) return;
        SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        blockRead = p.getBoolean("ghost_read", false);
        hideOnline = p.getBoolean("ghost_online", false);
        hideTyping = p.getBoolean("ghost_typing", false);
        readAfterSend = p.getBoolean("ghost_read_after_send", true);
        loaded = true;
    }

    private static void save(String key, boolean v) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, v).apply();
    }

    public static boolean isBlockRead() { load(); return blockRead; }
    public static boolean isHideOnline() { load(); return hideOnline; }
    public static boolean isHideTyping() { load(); return hideTyping; }
    public static boolean isReadAfterSend() { load(); return readAfterSend; }
    public static boolean isActive() { load(); return blockRead && hideOnline && hideTyping; }

    public static void setBlockRead(boolean v) { load(); blockRead = v; save("ghost_read", v); }
    public static void setHideTyping(boolean v) { load(); hideTyping = v; save("ghost_typing", v); }
    public static void setReadAfterSend(boolean v) { load(); readAfterSend = v; save("ghost_read_after_send", v); }

    public static void setHideOnline(boolean v) {
        load();
        hideOnline = v;
        save("ghost_online", v);
        if (v) {
            // tell the server right away that we are offline
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    TL_account.updateStatus req = new TL_account.updateStatus();
                    req.offline = true;
                    ConnectionsManager.getInstance(a).sendRequest(req, (r, e) -> {});
                }
            }
        }
    }

    /** Master switch: turns the three hiding options on or off together. */
    public static void setActive(boolean v) {
        setBlockRead(v);
        setHideTyping(v);
        setHideOnline(v);
    }

    /** Lets exactly one read request through (used by "read after I send"). */
    public static void allowNextRead() {
        allowRead.incrementAndGet();
    }

    private static boolean isReadRequest(TLObject o) {
        return o instanceof TLRPC.TL_messages_readHistory
            || o instanceof TLRPC.TL_messages_readEncryptedHistory
            || o instanceof TLRPC.TL_messages_readDiscussion
            || o instanceof TLRPC.TL_messages_readMessageContents
            || o instanceof TLRPC.TL_channels_readHistory
            || o instanceof TLRPC.TL_channels_readMessageContents;
    }

    /**
     * Called for every outgoing request. Returns true if the request must NOT be sent.
     * If it is dropped, the caller's callback is completed with a harmless fake answer.
     */
    public static boolean shouldDrop(TLObject object, RequestDelegate onComplete) {
        load();
        if (hideTyping && (object instanceof TLRPC.TL_messages_setTyping || object instanceof TLRPC.TL_messages_setEncryptedTyping)) {
            return true;
        }
        if (blockRead && isReadRequest(object)) {
            if (allowRead.get() > 0) {
                allowRead.decrementAndGet();
                return false;
            }
            if (onComplete != null) {
                try {
                    if (object instanceof TLRPC.TL_messages_readHistory || object instanceof TLRPC.TL_messages_readMessageContents) {
                        TLRPC.TL_messages_affectedMessages fake = new TLRPC.TL_messages_affectedMessages();
                        fake.pts = -1;
                        fake.pts_count = 0;
                        onComplete.run(fake, null);
                    } else {
                        onComplete.run(new TLRPC.TL_boolTrue(), null);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            return true;
        }
        return false;
    }

    /** Keeps "online" from reaching the server: every status update becomes "offline". */
    public static void patch(TLObject object) {
        load();
        if (hideOnline && object instanceof TL_account.updateStatus) {
            ((TL_account.updateStatus) object).offline = true;
        }
    }

    /** When we send a message in ghost mode, mark that chat as read afterwards (like a normal chat would). */
    public static RequestDelegate afterSend(int account, TLObject object, RequestDelegate orig) {
        load();
        if (!blockRead || !readAfterSend) return orig;
        TLRPC.InputPeer peer = null;
        if (object instanceof TLRPC.TL_messages_sendMessage) peer = ((TLRPC.TL_messages_sendMessage) object).peer;
        else if (object instanceof TLRPC.TL_messages_sendMedia) peer = ((TLRPC.TL_messages_sendMedia) object).peer;
        else if (object instanceof TLRPC.TL_messages_sendMultiMedia) peer = ((TLRPC.TL_messages_sendMultiMedia) object).peer;
        if (peer == null || orig == null) return orig;

        final TLRPC.InputPeer p = peer;
        final long dialogId = p.channel_id != 0 ? -p.channel_id : (p.chat_id != 0 ? -p.chat_id : p.user_id);
        return (response, error) -> {
            if (orig != null) orig.run(response, error);
            if (error != null) return;
            MessagesStorage.getInstance(account).getDialogMaxMessageId(dialogId, maxId -> {
                TLObject req;
                if (p instanceof TLRPC.TL_inputPeerChannel) {
                    TLRPC.TL_channels_readHistory r = new TLRPC.TL_channels_readHistory();
                    r.channel = MessagesController.getInputChannel(p);
                    r.max_id = maxId;
                    req = r;
                } else {
                    TLRPC.TL_messages_readHistory r = new TLRPC.TL_messages_readHistory();
                    r.peer = p;
                    r.max_id = maxId;
                    req = r;
                }
                allowNextRead();
                ConnectionsManager.getInstance(account).sendRequest(req, (a, b) -> {});
            });
        };
    }
}
