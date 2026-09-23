#!/system/bin/sh

FOREGROUND_PACKAGE=$(dumpsys activity activities 2>/dev/null | sed -n \
    's/.*mResumedActivity.* \([^/ ]*\)\/.*/\1/p' | head -n 1)
[ -n "$FOREGROUND_PACKAGE" ] || FOREGROUND_PACKAGE=$(dumpsys window windows 2>/dev/null | sed -n \
    's/.*mCurrentFocus.* \([^/ ]*\)\/.*/\1/p' | head -n 1)
[ -n "$FOREGROUND_PACKAGE" ] || exit 1

for PACKAGE_NAME in tv.danmaku.bili com.ss.android.ugc.aweme com.smile.gifmaker; do
    [ "$PACKAGE_NAME" = "$FOREGROUND_PACKAGE" ] && continue
    if ps -A 2>/dev/null | awk -v package="$PACKAGE_NAME" '
        $NF == package || index($NF, package ":") == 1 { found=1 }
        END { exit found ? 0 : 1 }
    '; then
        am force-stop "$PACKAGE_NAME" >/dev/null 2>&1
    fi
done
