#!/bin/bash
# Claude Code 전역(~/.claude) 설정: 한국어 + attention-span + 스킬 4종.
# 검토한 커밋으로 고정. 실패해도 세션 시작은 막지 않는다.
set -u
C="$HOME/.claude"; T="$(mktemp -d)"
get() { # repo sha dir
  git init -q "$T/$3" && git -C "$T/$3" fetch -q --depth 1 "https://github.com/$1" "$2" && git -C "$T/$3" checkout -q FETCH_HEAD
}
get alexgreensh/attention-span          2714c965e6be1fa2597510e66651e63bc67cb448 as   || exit 0
get multica-ai/andrej-karpathy-skills   2c606141936f1eeef17fa3043a72095b4765b9c2 ks   || exit 0
get dmmulroy/anti-slop                  c44ef22ca116d0ba62a3ff663a0bd13a3f3fa40b slop || exit 0
get obra/superpowers                    bb92a77741419a4ab5f06e711a283343f1ada0c3 sp   || exit 0
mkdir -p "$C/output-styles" "$C/commands" "$C/skills"
cp "$T"/as/output-styles/*.md "$C/output-styles/"
cp "$T/as/commands/style.md" "$C/commands/"
cp -r "$T"/as/skills/* "$T/ks/skills/karpathy-guidelines" \
      "$T/slop/skills/install-anti-slop" "$T/sp/skills/verification-before-completion" "$C/skills/"
python3 - "$C/settings.json" <<'EOF'
import json, os, sys
p = sys.argv[1]
s = json.load(open(p)) if os.path.exists(p) and os.path.getsize(p) else {}
s.update(language="korean", outputStyle="Attention-kind")
json.dump(s, open(p, "w"), ensure_ascii=False, indent=2)
EOF
rm -rf "$T"
echo "claude-setup: done"
