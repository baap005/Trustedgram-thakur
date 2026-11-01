# TrustedGram v5 - what changed

NEW files: TrustedBadges.java, ui/Components/TrustedPopup.java
REPLACED: TrustedConfig, TrustedFeed, TGramFeedActivity, TGramFeedChannelsActivity (now "Feed Settings")
EDITED: ConnectionsManager (2 one-line badge hooks), DialogsActivity (Feed label shows unread), TGramSettingsActivity
RUN ONCE: apply_branding.sh  (fixes "Telegram" title shown after adding a story)

Verified badge: put ids in ANY app_config column whose name contains "verif" or "badge"
(array, comma string, or jsonb with users/channels). Boolean flags are ignored.

ADDED: TrustedAppearance + TGramAppearanceActivity (TGram Settings > Appearance): Title Text (TrustedGram/Username/First Name/Chats),
Hide Stories, Hide Floating Button - wired into DialogsActivity.

RELEASE NUMBER RULE: TrustedConfig.VERSION_CODE is the only number to change (now 3). Admin panel "Latest version code" = same number.
The installed app shows it at the bottom of TGram Settings ("Build 3").
