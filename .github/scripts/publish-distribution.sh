#!/usr/bin/env bash
# Publish a signed APK and its immutable SHA-256 metadata to the public website.
set -euo pipefail
: "${GH_TOKEN:?MRADIO_DISTRIBUTION_TOKEN secret must be configured}"
: "${APP:?APP must be set}"
: "${VERSION:?VERSION must be set}"
: "${VERSION_CODE:?VERSION_CODE must be set}"
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || { echo "::error::Invalid APK versionCode"; exit 1; }
TARGET="N2K02/n2k02.github.io"
SLUG=$(echo "$APP" | tr '[:upper:]' '[:lower:]')
APK="$APP-$VERSION.apk"
test -s "$APK"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]$ ]] || { echo "::error::Invalid app version"; exit 1; }
CHANNEL=stable
TAG="$SLUG-v$VERSION"
MANIFEST="$SLUG/updates/stable.json"
if [ "$GITHUB_EVENT_NAME" = "pull_request" ]; then
  : "${PR_NUMBER:?Missing PR_NUMBER}"
  CHANNEL=experimental
  TAG="$SLUG-experimental-pr-$PR_NUMBER-run-$GITHUB_RUN_NUMBER"
  MANIFEST="$SLUG/updates/experimental.json"
fi
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")
if gh release view "$TAG" --repo "$TARGET" >/dev/null 2>&1; then
  echo "::error::Public distribution tag $TAG already exists; reruns must not replace signed binaries."
  exit 1
fi
gh release create "$TAG" "$APK" --repo "$TARGET" --target main --title "$APP $VERSION ($CHANNEL)" --notes-file release-notes.md --latest=false
URL="https://github.com/$TARGET/releases/download/$TAG/$APK"
NOTES=$( { grep '^- ' release-notes.md || true; } | head -10 | sed 's/^- //' | jq -R -s 'split("\n") | map(select(length > 0))')
jq -n --arg channel "$CHANNEL" --arg tag "$TAG" --arg version "$VERSION" --arg apkName "$APK" --arg apkUrl "$URL" --arg pageUrl "https://github.com/$TARGET/releases/tag/$TAG" --arg sha256 "$SHA" --argjson versionCode "$VERSION_CODE" --argjson apkSize "$SIZE" --argjson notes "$NOTES" '{channel:$channel,tag:$tag,versionName:$version,versionCode:$versionCode,apkName:$apkName,apkUrl:$apkUrl,apkSize:$apkSize,sha256:$sha256,pageUrl:$pageUrl,notes:$notes}' > manifest.json
CURRENT_JSON=$(gh api "repos/$TARGET/contents/$MANIFEST" --jq .content 2>/dev/null | tr -d '\n' | base64 -d 2>/dev/null || true)
if [ -n "$CURRENT_JSON" ]; then
  OLD=$(jq -r '.versionCode // 0' <<<"$CURRENT_JSON")
  if [[ "$OLD" =~ ^[0-9]+$ ]] && (( OLD >= VERSION_CODE )); then
    echo "An equal or newer $CHANNEL manifest is already published; not replacing it."
    exit 0
  fi
fi
ENC=$(base64 -w0 manifest.json)
CURRENT_SHA=$(gh api "repos/$TARGET/contents/$MANIFEST" --jq .sha 2>/dev/null || true)
ARGS=(--method PUT "repos/$TARGET/contents/$MANIFEST" -f "message=Publish $APP $VERSION ($CHANNEL)" -f "content=$ENC" -f "branch=main")
[ -z "$CURRENT_SHA" ] || ARGS+=(-f "sha=$CURRENT_SHA")
gh api "${ARGS[@]}" >/dev/null
echo "Published $CHANNEL $APP $VERSION code=$VERSION_CODE to $MANIFEST"
