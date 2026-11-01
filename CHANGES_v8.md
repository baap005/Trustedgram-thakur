# TrustedGram v8 (fixes the v7 build failure)
- DialogsActivity: stories visibility variable is now final (3 "effectively final" errors).
- TGramFeedActivity: removed didPressBotButton (TLRPC.KeyboardButton / TL_keyboardButtonUrl don't exist in this Telegram version; 3 errors).
  Inline URL buttons under feed posts no longer open from the feed; long-press > Open post still works.

# iOS style (new)
- TGram Settings > Appearance > "iOS Look" (on by default): iOS system blue, grouped #F2F2F7 background, iOS dividers/greys, green switches,
  blue unread badges; liquid-glass floating bars forced on (Android 12+); titles centered by default. Restart after toggling.
- Floating glass bottom tab bar (Chats / Contacts / Settings / Calls / Profile) comes from the base project's MainTabsActivity.

# v10 additions
- iOS back chevron (no stem), semibold (600) bold font, light haptic tick on switches.
- "Feed" button in the floating glass tab bar (Appearance > Feed in Bottom Bar; restart).
- iOS dark palette baked into the Night theme (black bg, #1C1C1E cells, #0A84FF blue, green switches).
# v11
- iOS defaults (only if you never changed them): 2-line message preview in chat list, bubble corner radius 19.
# v12
- iOS large title that collapses into the action bar on TGram settings pages (UniversalFragment).
