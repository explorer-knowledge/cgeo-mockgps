#!/usr/bin/env bash
# One-time: creates the GitHub repo from this folder and pushes it. Everything else runs in GitHub Actions.
set -euo pipefail
NAME="${1:-cgeo-mockgps}"
command -v gh >/dev/null || { echo "Install the GitHub CLI first: https://cli.github.com"; exit 1; }
gh auth status >/dev/null 2>&1 || gh auth login -s workflow
gh auth refresh -h github.com -s workflow
cd "$(dirname "$0")"
if [ ! -d .git ]; then git init -q -b main && git add -A && git commit -qm "c:geo + Mock GPS patch"; fi
gh repo create "$NAME" --public --source . --push
echo
echo "Done. The first build starts now: $(gh repo view --json url -q .url)/actions"
echo "When it finishes, download: $(gh repo view --json url -q .url)/releases/latest/download/cgeo-mockgps.apk"
