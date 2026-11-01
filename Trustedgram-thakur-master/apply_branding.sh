#!/usr/bin/env bash
# TrustedGram branding fixer. Run ONCE from the root of your project (the folder that contains TMessagesProj/).
# Why: the chat-list title is TrustedGram, but when a story row is added the story cell draws its own title
# from the AppName string ("Telegram"). That class (DialogStoriesCell) and strings.xml were not in the zip,
# so this script fixes them in your real tree instead of shipping huge files.
set -e
ROOT="${1:-.}"
SRC="$ROOT/TMessagesProj/src/main"
[ -d "$SRC" ] || { echo "Run from the project root or pass it as an argument."; exit 1; }

echo "1) AppName string in every values*/ folder -> TrustedGram"
find "$SRC/res" -name "strings*.xml" | while read -r f; do
  if grep -q 'name="AppName"' "$f"; then
    sed -i -E 's#(<string name="AppName"[^>]*>)[^<]*(</string>)#\1TrustedGram\2#' "$f"
    echo "   patched $f"
  fi
done

echo "2) Java code that reads AppName -> literal TrustedGram"
grep -rlE 'R\.string\.AppName' "$SRC/java" | while read -r f; do
  sed -i -E \
    -e 's#LocaleController\.getString\("AppName",[ ]*R\.string\.AppName\)#"TrustedGram"#g' \
    -e 's#LocaleController\.getString\(R\.string\.AppName\)#"TrustedGram"#g' \
    -e 's#(^|[^.A-Za-z])getString\(R\.string\.AppName\)#\1"TrustedGram"#g' "$f"
  echo "   patched $f"
done

echo
echo "Still referencing AppName (check by hand - these use it as a resource id, which is fine):"
grep -rn 'R\.string\.AppName' "$SRC/java" || echo "   none"
echo
echo "Visible 'Telegram' title strings left in code (review):"
grep -rn '"Telegram"' "$SRC/java" | grep -v "package\|import\|org.telegram" | head -20 || true
echo "Done."
