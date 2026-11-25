/*
 * TrustedGram feed action helpers.
 * The upstream feed attribution and licensing terms are documented in
 * THIRD_PARTY_NOTICES.md and ADDITIONAL_TERMS.md.
 */
package com.exteragram.messenger.feed.ui;

import org.telegram.tgnet.TLRPC;

final class FeedPostActions {
    private FeedPostActions() {
    }

    static String getPublicPostLink(TLRPC.Chat chat, int messageId) {
        if (chat == null || chat.username == null || chat.username.isEmpty() || messageId <= 0) {
            return null;
        }
        return "https://t.me/" + chat.username + "/" + messageId;
    }
}
