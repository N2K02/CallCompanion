#!/usr/bin/env bash
# Publish the signed stable APK from this workflow to the public website repository.
set -euo pipefail
: "${GH_TOKEN:?Set the repository secret MRadioAppkey}"
: "${APP:?Missing APP}"
: "${VERSION:?Missing VERSION}"
REPO="N2K02/n2k02.github.io"
SLUG=$(echo "$APP" | tr '[:upper:]' '[:lower:]')
FILE="$APP-$VERSION.apk"
test -s "$FILE"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]$ ]] || { echo "Invalid version"; exit 1; }
CODE=$(( ${VERSION%.*} * 10 + ${VERSION#*.} ))
TAG="$SLUG-v$VERSION"
SHA=$(sha256sum "$FILE" | cut -d' ' -f1)
SIZE=$(stat -c %s "$FILE")
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" "$FILE" --repo "$REPO" --clobber
else
  gh release create "$TAG" "$FILE" --repo "$REPO" --target main --title "$APP $VERSION" --notes-file release-notes.md --latest=false
fi
URL="https://github.com/$REPO/releases/download/$TAG/$FILE"
NOTES=$( { grep '^- ' release-notes.md || true; } | head -10 | sed 's/^- //' | jq -R -s 'split("\\n") | map(select(length > 0))')
jq -n --arg tag "$TAG" --arg versionName "$VERSION" --arg apkName "$FILE" --arg apkUrl "$URL" --arg pageUrl "https://github.com/$REPO/releases/tag/$TAG" --arg sha256 "$SHA" --argjson versionCode "$CODE" --argjson apkSize "$SIZE" --argjson notes "$NOTES" '{channel:"stable",tag:$tag,versionName:$versionName,versionCode:$versionCode,apkName:$apkName,apkUrl:$apkUrl,apkSize:$apkSize,sha256:$sha256,pageUrl:$pageUrl,notes:$notes}' > manifest.json
PATH_IN_REPO="$SLUG/updates/stable.json"
ENC=$(base64 -w0 manifest.json)
PREV=$(gh api "repos/$REPO/contents/$PATH_IN_REPO" --jq .content 2>/dev/null | tr -d '\n' | base64 -d 2>/dev/null || true)
if [ -n "$PREV" ]; then
  OLD=$(jq -r '.versionCode // 0' <<<"$PREV")
  if [[ "$OLD" =~ ^[0-9]+$ ]] && (( OLD >= CODE )); then echo "Skipping older manifest"; exit 0; fi
fi
CURRENT=$(gh api "repos/$REPO/contents/$PATH_IN_REPO" --jq .sha 2>/dev/null || true)
ARGS=(--method PUT "repos/$REPO/contents/$PATH_IN_REPO" -f "message=Publish $APP $VERSION stable manifest" -f "content=$ENC" -f "branch=main")
[ -z "$CURRENT" ] || ARGS+=(-f "sha=$CURRENT")
gh api "${ARGS[@]}" >/dev/null
