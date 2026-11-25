/*
 * TrustedGram feed action helpers.
 * Derived feed implementation attribution and license terms are documented
 * in THIRD_PARTY_NOTICES.md and ADDITIONAL_TERMS.md.
 */
package com.exteragram.messenger.feed;

import org.telegram.tgnet.TLRPC;

public final class FeedPostActions {
    private FeedPostActions() {
    }

    public static String getPublicPostLink(TLRPC.Chat chat, int messageId) {
        if (chat == null || chat.username == null || chat.username.isEmpty() || messageId <= 0) {
            return null;
        }
        return "https://t.me/" + chat.username + "/" + messageId;
    }
}
