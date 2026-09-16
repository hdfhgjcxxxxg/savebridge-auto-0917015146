#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
command -v git >/dev/null || { pkg update -y; pkg install -y git; }
command -v gh >/dev/null || { echo '先にTermuxで gh をインストールしてください: pkg install gh'; exit 2; }
if ! gh auth status >/dev/null 2>&1; then gh auth login; fi
REPO="${1:-}"
if [ -z "$REPO" ]; then read -r -p 'GitHubリポジトリ(owner/name): ' REPO; fi
cd "$ROOT"
if [ ! -d .git ]; then git init -b main; fi
git add android ctr tests README.md BUILD.md build-all.sh build-all.ps1 termux-auto-build.sh .github
git -c user.name=SaveBridge -c user.email=savebridge@localhost commit -m "Build SaveBridge CIA and APK" >/dev/null 2>&1 || true
git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/$REPO.git"
git push -u origin main
echo 'GitHub ActionsでCIA/APKをビルド中…'
sleep 5
gh run watch --repo "$REPO" --exit-status
mkdir -p "$ROOT/termux-artifacts"
gh run download --repo "$REPO" --name SaveBridgeMulti-CIA --dir "$ROOT/termux-artifacts" || true
gh run download --repo "$REPO" --name SaveBridgeMulti-APK --dir "$ROOT/termux-artifacts" || true
echo "完了: $ROOT/termux-artifacts"
