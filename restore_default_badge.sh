#!/usr/bin/env bash
# Puts Telegram's DEFAULT verified badge back (removes the custom TrustedGram logo badge).
# Run in Git Bash from your project root (the folder that contains TMessagesProj and .git).
# It restores every res/*/verified_* image from the very first commit of your repo (the original Telegram source).
set -e
[ -d .git ] || { echo "Run this inside your project folder (where .git is)."; exit 1; }
ROOT_COMMIT=$(git rev-list --max-parents=0 HEAD | tail -1)
echo "Original source commit: $ROOT_COMMIT"
echo
echo "Badge images currently in your project:"
git ls-files | grep -E "/res/.*/verified_" || echo "  (none tracked)"
echo
restored=0
for f in $(git ls-tree -r --name-only "$ROOT_COMMIT" | grep -E "/res/.*/verified_"); do
  git checkout "$ROOT_COMMIT" -- "$f" && echo "restored $f" && restored=$((restored+1))
done
echo
# custom badge files that did not exist in the original source (extra names you added)
for f in $(git ls-files | grep -E "/res/.*/(verified|trusted_verified|tg_verified)[A-Za-z0-9_]*\.(png|webp|xml)$"); do
  if ! git cat-file -e "$ROOT_COMMIT:$f" 2>/dev/null; then
    echo "NOT in original source (custom file, delete it if it is a badge): $f"
  fi
done
echo
echo "Restored $restored file(s). Now rebuild the app."
echo "If 0 were restored, the original images are not in your repo history - tell me and I will send them."
