#!/usr/bin/env bash
# 拉取 miuix 上游到 third_party/miuix，并依次 git am tools/miuix-patches/*.patch。
# 用法：tools/miuix-sync.sh [--pin]   --pin：把解析到的上游提交写回 miuix.ref 的 commit=
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party/miuix"
PATCHES="$ROOT/tools/miuix-patches"
get() { sed -n "s/^$1=//p" "$ROOT/miuix.ref" | head -n1; }
REPO="$(get repo)"; REF="$(get ref)"; PIN="$(get commit)"
[ -n "$REPO" ] || { echo "miuix.ref 缺少 repo="; exit 1; }
REF="${REF:-main}"
if [ ! -d "$DEST/.git" ]; then
  mkdir -p "$ROOT/third_party"
  git clone --quiet "$REPO" "$DEST"
fi
cd "$DEST"
git remote set-url origin "$REPO"
git fetch --quiet --tags origin
git am --abort >/dev/null 2>&1 || true
TARGET="${PIN:-}"
[ -n "$TARGET" ] || TARGET="$(git rev-parse --verify --quiet "origin/$REF" || git rev-parse --verify "$REF^{commit}")"
git checkout --quiet --detach --force "$TARGET"
git reset --quiet --hard "$TARGET"
git clean -qfd
git tag -f mafari-base "$TARGET" >/dev/null
echo "miuix 上游: $(git rev-parse --short HEAD)  ($REF)"
shopt -s nullglob
PS=("$PATCHES"/*.patch)
if [ ${#PS[@]} -gt 0 ]; then
  if ! git -c user.name=mafari -c user.email=mafari@local am --quiet --3way "${PS[@]}"; then
    echo "补丁未能干净应用，已停在冲突处：" >&2
    git status --short >&2 || true
    echo "在 third_party/miuix 里解决冲突后 git am --continue，再运行 tools/miuix-export-patches.sh 更新补丁；" >&2
    echo "或先在 miuix.ref 里用 commit= 锁回旧版本。" >&2
    exit 2
  fi
  echo "已应用 ${#PS[@]} 个本地补丁"
  # 兜底：immersive 补丁用到 WindowInsets.navigationBars / .asPaddingValues()，上游若未导入则补上（幂等）
  F="miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/layout/BottomSheetContentLayout.kt"
  if [ -f "$F" ]; then
    CHANGED=0
    for SYM in navigationBars asPaddingValues; do
      if grep -q "\\.$SYM\\b" "$F" && ! grep -q "^import androidx.compose.foundation.layout.$SYM\$" "$F"; then
        sed -i "0,/^import androidx.compose.foundation.layout.WindowInsets\$/s//import androidx.compose.foundation.layout.WindowInsets\nimport androidx.compose.foundation.layout.$SYM/" "$F"
        grep -q "^import androidx.compose.foundation.layout.$SYM\$" "$F" || sed -i "0,/^import androidx.compose/s//import androidx.compose.foundation.layout.$SYM\nimport androidx.compose/" "$F"
        CHANGED=1
      fi
    done
    [ "$CHANGED" = 1 ] && git -c user.name=mafari -c user.email=mafari@local commit -qam "fix: import navigationBars/asPaddingValues" || true
  fi
fi
if [ "${1:-}" = "--pin" ]; then
  H="$(git rev-parse mafari-base)"
  if grep -q '^commit=' "$ROOT/miuix.ref"; then sed -i "s/^commit=.*/commit=$H/" "$ROOT/miuix.ref"; else echo "commit=$H" >> "$ROOT/miuix.ref"; fi
  echo "已锁定 commit=$H"
fi
