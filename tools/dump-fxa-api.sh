#!/usr/bin/env bash
# 打印 Mozilla android-components 里本项目用到的类的真实公开签名（写 Fxa* 代码时对照用）。
# CI 里 always() 运行：构建成败都会输出，贴日志即可看到当前解析到的版本的 API。
set +e
PAT='(^|/)(FxaAccountManager|DeviceConfig|SyncConfig|ServerConfig|SyncEngine|GlobalSyncableStoreProvider|FxaAuthData|AuthType|FxAEntryPoint|FxaServer|DeviceCommandOutgoing(\$[A-Za-z]+)?|DeviceCommandIncoming(\$[A-Za-z]+)?|AccountEvent(\$[A-Za-z]+)?|PlacesBookmarksStorage|PlacesHistoryStorage|RemoteTabsStorage|BookmarkNode|BookmarkNodeType|VisitInfo|PageVisit|PageObservation|RedirectSource|VisitType|Tab|TabEntry|SyncClient|Device|DeviceConstellation|TabData|TabPrivacy|SyncableLoginsStorage|LoginsStorage|Login|LoginEntry|SecureAbove22Preferences|PeriodicSyncConfig|FxaConfig|OAuthAccount|Profile|AccountObserver|SyncStatusObserver)\.class$'
# viaduct（Rust 网络后端）：FxaApi.OkHttpBackend 用到的整个包，类名太通用（Request/Response），按包路径匹配
PAT_VIADUCT='mozilla/appservices/(viaduct|init_rust_components)/[A-Za-z]+(\$[A-Za-z]+)?\.class$'
WORK=$(mktemp -d)
echo "=== resolved mozilla artifacts ==="
find ~/.gradle -type f \( -name '*.aar' -o -name '*.jar' \) \( -path '*mozilla*' -o -path '*appservices*' \) 2>/dev/null | sed 's#.*/##' | sort -u | head -80
echo "=== signatures ==="
find ~/.gradle -type f -name '*.aar' \( -path '*org.mozilla.components*' -o -path '*org.mozilla.appservices*' \) 2>/dev/null | while read -r aar; do
  d="$WORK/$(basename "$aar" .aar)"; mkdir -p "$d"
  unzip -q -o "$aar" classes.jar -d "$d" 2>/dev/null || continue
  jar="$d/classes.jar"
  unzip -Z1 "$jar" 2>/dev/null | grep -E "$PAT|$PAT_VIADUCT" | grep -v 'Companion\|\$\$' | sed 's/\.class$//; s#/#.#g' | while read -r cls; do
    echo "----- $cls   ($(basename "$aar"))"
    javap -public -cp "$jar" "$cls" 2>/dev/null | head -80
  done
done
