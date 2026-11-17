#!/usr/bin/env bash
# 把 third_party/miuix 里相对上游基线（mafari-base）的提交导出成补丁，覆盖 tools/miuix-patches。
# 工作流：在 third_party/miuix 里改代码并按功能分别 git commit → 运行本脚本 → 提交 tools/miuix-patches。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/third_party/miuix"
git rev-parse --verify --quiet mafari-base >/dev/null || { echo "没有 mafari-base，请先运行 tools/miuix-sync.sh"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "有未提交的改动，请先 git commit（每个功能一个提交）"; exit 1; }
OUT="$ROOT/tools/miuix-patches"
mkdir -p "$OUT"; rm -f "$OUT"/*.patch
git format-patch --quiet --no-signature -N -o "$OUT" mafari-base..HEAD
echo "已导出 $(ls "$OUT"/*.patch 2>/dev/null | wc -l) 个补丁到 tools/miuix-patches"
