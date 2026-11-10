#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
# Builds and signs the Zeffin release APK without GitHub Actions (docs/android-app.md, "Publish a version").
#
#   android/scripts/release-apk.sh --rc     # a release candidate apk-v<fb.version>-rc.<n>, from release/<fb.version>
#   android/scripts/release-apk.sh --final  # the release apk-v<fb.version>, from main
#
# It reads the release key from the environment, never from the repo, and never prints it:
#   ANDROID_KEYSTORE_B64       the keystore (PKCS12, alias flipper-bridge) in base64
#   ANDROID_KEYSTORE_PASSWORD  its password
#
# The checks are the Release APK workflow's: the branch matches fb.version, the tag does not exist yet, the Gradle
# wrapper has Gradle's published hash, the APK is not debuggable, and it is signed with the stable key (its
# certificate's SHA-256 below), and a release has its docs/release-notes/<version>.md. It writes the APK, its SHA-256
# and the release notes (built by android/scripts/release-notes.py, as the workflow does) to
# android/app/build/outputs/release/; publishing them on GitHub is done by hand, with the notes as the description.
#
# Needs JDK 21 (JAVA_HOME) and the Android SDK (ANDROID_HOME, or sdk.dir in android/local.properties) with the
# build tools below.
set -euo pipefail

GRADLE_WRAPPER_SHA256=3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98  # as in the workflows
BUILD_TOOLS=36.0.0
# The stable release key's certificate, printed in the notes of every release since apk-v0.1.0.
RELEASE_CERT_SHA256=5b063b1639be972474c0a755d3fb183caa5a9b12de260d6e7324cfbb132be31e

die() { echo "release-apk: $*" >&2; exit 1; }

case "${1:-}" in
  --rc) KIND=rc ;;
  --final) KIND=final ;;
  *) die "usage: $0 --rc | --final" ;;
esac

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"

VERSION="$(grep -E '^fb.version=' android/gradle.properties | cut -d= -f2)"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "fb.version '$VERSION' is not X.Y.Z"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
COMMIT="$(git rev-parse HEAD)"

# The APK must be the commit, nothing else.
[ -z "$(git status --porcelain)" ] || die "the working tree has changes: commit or drop them first"
git fetch -q origin "$BRANCH" || die "cannot fetch $BRANCH from origin"
[ "$COMMIT" = "$(git rev-parse "origin/$BRANCH")" ] || die "HEAD is not origin/$BRANCH: push or pull first"

TAGS="$(git ls-remote --tags origin "apk-v$VERSION*" | awk '{print $2}' | sed 's#refs/tags/##; s#\^{}##' | sort -u)"
grep -qx "apk-v$VERSION" <<<"$TAGS" && die "apk-v$VERSION is already released"
if [ "$KIND" = rc ]; then
  [ "$BRANCH" = "release/$VERSION" ] || die "a candidate comes from release/$VERSION, not $BRANCH"
  N=$(( $(grep -c "^apk-v$VERSION-rc\.[0-9]*$" <<<"$TAGS" || true) + 1 ))
  TAG="apk-v$VERSION-rc.$N"
  SUFFIX="-rc.$N"
else
  [ "$BRANCH" = main ] || die "the release comes from main, not $BRANCH"
  TAG="apk-v$VERSION"
  SUFFIX=""
fi
[[ "$TAG" =~ ^apk-v[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$ ]] || die "invalid tag $TAG"

# The notes' "What's new" (docs/release-notes/<version>.md): a release without it stops here, before anything is built.
python3 -I android/scripts/release-notes.py --check --version "$VERSION" --kind "$KIND" \
  || die "write docs/release-notes/$VERSION.md first"

[ -n "${ANDROID_KEYSTORE_B64:-}" ] && [ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ] \
  || die "the release key is not set: ANDROID_KEYSTORE_B64 and ANDROID_KEYSTORE_PASSWORD (docs/android-app.md)"

echo "$GRADLE_WRAPPER_SHA256  android/gradle/wrapper/gradle-wrapper.jar" | sha256sum -c --quiet - \
  || die "the Gradle wrapper does not have Gradle's published hash"

SDK="${ANDROID_HOME:-$(grep -E '^sdk.dir=' android/local.properties 2>/dev/null | cut -d= -f2- || true)}"
BT="$SDK/build-tools/$BUILD_TOOLS"
[ -x "$BT/apksigner" ] && [ -x "$BT/zipalign" ] && [ -x "$BT/aapt2" ] \
  || die "build tools $BUILD_TOOLS not found in '$SDK' (sdkmanager \"build-tools;$BUILD_TOOLS\")"

echo "Building $TAG from $BRANCH at ${COMMIT:0:7}"
(cd android && ./gradlew --no-daemon -q -Pfb.releaseSuffix="$SUFFIX" :app:assembleRelease)

OUT_DIR=android/app/build/outputs/release
APK="zeffin-${TAG#apk-}.apk"
mkdir -p "$OUT_DIR"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
chmod 700 "$WORK"

"$BT/zipalign" -p -f 4 android/app/build/outputs/apk/release/app-release-unsigned.apk "$WORK/aligned.apk"
(
  umask 077
  base64 -d <<<"$ANDROID_KEYSTORE_B64" > "$WORK/release.jks" 2>/dev/null || die "ANDROID_KEYSTORE_B64 is not base64"
  KS_PASS="$ANDROID_KEYSTORE_PASSWORD" "$BT/apksigner" sign --v4-signing-enabled false --ks "$WORK/release.jks" \
    --ks-pass env:KS_PASS --ks-key-alias flipper-bridge --out "$OUT_DIR/$APK" "$WORK/aligned.apk"
)
rm -f "$WORK/release.jks"

CERT="$("$BT/apksigner" verify --print-certs "$OUT_DIR/$APK" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')"
if [ "$CERT" != "$RELEASE_CERT_SHA256" ]; then
  rm -f "$OUT_DIR/$APK"
  die "signed with another key (certificate $CERT): updates would not install over the released app"
fi
if "$BT/aapt2" dump badging "$OUT_DIR/$APK" | grep -q "application-debuggable"; then
  rm -f "$OUT_DIR/$APK"
  die "refusing a debuggable APK"
fi
(cd "$OUT_DIR" && sha256sum "$APK" > "$APK.sha256")
APK_SHA="$(cut -d' ' -f1 "$OUT_DIR/$APK.sha256")"

# The same builder as the Release APK workflow: the version's "What's new", the candidate's paragraph, the APK's part.
python3 -I android/scripts/release-notes.py --version "$VERSION" --kind "$KIND" --built-by script --branch "$BRANCH" \
  --commit "$COMMIT" --apk "$APK" --apk-sha256 "$APK_SHA" --cert-sha256 "$CERT" > "$OUT_DIR/$TAG-notes.md"

cat <<DONE
Done: $TAG
  APK:          $OUT_DIR/$APK
  SHA-256:      $APK_SHA
  Certificate:  $CERT (the stable key)
  Notes:        $OUT_DIR/$TAG-notes.md
Publish: GitHub, Releases, Draft a new release, tag $TAG on $BRANCH (commit ${COMMIT:0:7}),$( [ "$KIND" = rc ] && echo " pre-release," ) the notes, the APK and its .sha256.
DONE
