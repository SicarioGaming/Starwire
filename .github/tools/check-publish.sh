#!/usr/bin/env bash
# Checks what a push to the public Starwire repo would add, before it goes out.
#
#   bash .github/tools/check-publish.sh <public-tip> <new-tip> [patterns-file]
#   bash .github/tools/check-publish.sh --pre-push <remote> <url>     (as a git pre-push hook)
#
#   public-tip     what the public repo has now, usually public/main. "-" means nothing, so all of
#                  new-tip is checked.
#   new-tip        the branch, commit or tag about to be pushed.
#   patterns-file  extra extended regexes, one per line, matched without case (default
#                  private/privacy-patterns.txt, which git ignores, so the patterns never get published).
#                  Lines starting with # are comments. "allow <commit> <path>" accepts a known hit in that
#                  commit and file, for things that are already public anyway.
#
# It looks only at commits that new-tip has and public-tip doesn't, and skips upstream ones (anything
# reachable from refs/remotes/moonlightx, artemide, turnip, nightjar or vplus, the forks Starwire takes
# commits from; set UPSTREAM_REMOTES to change the list).
# It only reads the repo: no network, no changes, and the same input always gives the same output.
# Findings say where something is, never what matched, so the output is safe to paste.
# Exit 0 = clean, 1 = something to fix, 2 = usage error.
#
# What it checks:
#  1. every new commit (merges too) and the tag, if new-tip is one, is by the publishing identity
#     (PUBLISH_IDENTITY, default below) and dated in UTC (+0000). An upstream commit brought in with
#     git cherry-pick -x keeps its own author and author date: that passes when the message's last line
#     is "(cherry picked from commit <full hash>)", that commit is on an upstream ref, and the author's
#     name, email and date are the same as its. The committer is still checked as above.
#  2. no keystores or key files, keystore.properties, local.properties, .env, private/ or Reddit drafts
#  3. in added lines, file names and commit and tag messages: access tokens, private keys, written-out passwords,
#     local user folders, private (RFC 1918) host addresses outside test files, and every pattern in
#     the patterns file
#  4. no DELAYTBD, TBD or <release date> placeholders left in README.md, docs, windows/README.md or
#     .github/release-notes at new-tip
#
# Needs bash, git, GNU grep and awk (all in Git for Windows).

set -u
export LC_ALL=C

ID_OK=${PUBLISH_IDENTITY:-SicarioGaming <stayf8dop@gmail.com>}
UPSTREAM=${UPSTREAM_REMOTES:-moonlightx artemide turnip nightjar vplus}
MAX_LINES=40
ZERO=0000000000000000000000000000000000000000

usage() { echo "usage: check-publish.sh <public-tip> <new-tip> [patterns-file]" >&2
          echo "       check-publish.sh --pre-push <remote> <url>" >&2; exit 2; }

# --- pre-push hook mode: only guards pushes to the public repo ------------------------------------
if [ "${1:-}" = --pre-push ]; then
  remote=${2:-} url=${3:-}
  case "$remote $url" in
    "public "*|*github.com[:/]SicarioGaming/Starwire|*github.com[:/]SicarioGaming/Starwire.git) ;;
    *) cat >/dev/null; exit 0 ;;
  esac
  self=$0; status=0
  base=$(git rev-parse -q --verify refs/remotes/public/main || echo -)
  while read -r lref lsha rref rsha; do
    if [ "$lsha" = "$ZERO" ]; then
      case "$rref" in
        refs/heads/history-preview) continue ;;
        *) echo "refusing to delete $rref on the public repo"; status=1; continue ;;
      esac
    fi
    case "$rref" in
      refs/heads/main|refs/heads/history-preview) ;;
      refs/tags/v[0-9]*)
        if [ "$rsha" != "$ZERO" ]; then
          echo "refusing to move $rref, which the public repo already has"; status=1; continue
        fi
        if [ "$(git cat-file -t "$lsha" 2>/dev/null)" != tag ]; then
          echo "refusing $rref: release tags must be annotated (git tag -a)"; status=1; continue
        fi ;;
      *) echo "refusing $rref: only main, history-preview and v* release tags go to the public repo"
         status=1; continue ;;
    esac
    old=$base
    if [ "$rsha" != "$ZERO" ] && git cat-file -e "$rsha^{commit}" 2>/dev/null; then old=$rsha; fi
    echo "== $rref"
    bash "$self" "$old" "$lsha" || status=1
  done
  [ $status = 0 ] || echo "push stopped by check-publish.sh"
  exit $status
fi

[ $# -ge 2 ] && [ $# -le 3 ] || usage
OLD=$1 NEW=$2 PATTERNS=${3:-private/privacy-patterns.txt}

git rev-parse -q --verify "$NEW^{commit}" >/dev/null || { echo "not a commit: $NEW" >&2; exit 2; }
not=()
if [ "$OLD" != - ]; then
  git rev-parse -q --verify "$OLD^{commit}" >/dev/null || { echo "not a commit: $OLD" >&2; exit 2; }
  not+=("$OLD")
fi
skipped_up="" up_refs=()
for r in $UPSTREAM; do
  if [ -n "$(git for-each-ref --count=1 "refs/remotes/$r/")" ]; then not+=("--remotes=$r"); up_refs+=("--remotes=$r")
  else skipped_up="$skipped_up $r"; fi
done
[ -z "$skipped_up" ] || echo "note: no refs for upstream remote(s)$skipped_up, so their commits are checked too"

tmp=$(mktemp -d) || exit 2
trap 'rm -rf "$tmp"' EXIT
: > "$tmp/out"

git rev-list "$NEW" --not "${not[@]}" > "$tmp/commits"
echo "checking $(wc -l < "$tmp/commits" | tr -d ' ') new commit(s) in $NEW"

# Private patterns and allow entries
: > "$tmp/patterns"; : > "$tmp/allow"
if [ -f "$PATTERNS" ]; then
  n=0
  while IFS= read -r p || [ -n "$p" ]; do
    n=$((n + 1)); p=${p%$'\r'}
    case "$p" in ''|'#'*) continue ;; esac
    if [ "${p#allow }" != "$p" ]; then
      read -r a_commit a_path <<< "${p#allow }"
      if full=$(git rev-parse -q --verify "${a_commit:-x}^{commit}") && [ -n "$a_path" ]; then
        printf '%s\t%s\n' "$full" "$a_path" >> "$tmp/allow"
      else
        echo "note: ignoring allow line $n, which doesn't name a commit here and a path"
      fi
      continue
    fi
    printf '%s\t%s\n' "$n" "$p" >> "$tmp/patterns"
  done < "$PATTERNS"
  echo "private patterns: $(wc -l < "$tmp/patterns" | tr -d ' ') from $PATTERNS"
else
  echo "note: no patterns file at $PATTERNS, so only the built-in checks run"
fi

# --- 1. identity and UTC dates ---------------------------------------------------------------------
# picks gets "commit TAB verdict" for each commit whose message ends with a cherry-pick line: ok, or
# "upstream" when the commit it names isn't on an upstream ref, or "author" when the author differs.
# Authors are compared as written in the commits, without .mailmap.
: > "$tmp/picks"
author_of() { git log -1 --no-use-mailmap --format='%an%n%ae%n%ad' --date=raw "$1"; }
if [ -s "$tmp/commits" ]; then
  git log --no-walk=unsorted --stdin --format='%x01%H%n%B' < "$tmp/commits" |
    awk 'function pick() { if (c != "" && length(last) == 68 && last ~ /^\(cherry picked from commit [0-9a-f]+\)$/)
                             print c "\t" substr(last, 28, 40) }
         /^\001/ { pick(); c = substr($0, 2); last = ""; next }
         NF { last = $0 }
         END { pick() }' |
    while IFS=$'\t' read -r c up; do
      verdict=upstream
      if [ ${#up_refs[@]} -gt 0 ] && git cat-file -e "$up^{commit}" 2>/dev/null &&
         left=$(git rev-list -n 1 "$up" --not "${up_refs[@]}") && [ -z "$left" ]; then
        verdict=author
        [ "$(author_of "$c")" = "$(author_of "$up")" ] && verdict=ok
      fi
      printf '%s\t%s\n' "$c" "$verdict"
    done > "$tmp/picks"
  git log --no-walk=unsorted --stdin --format='%H%x09%an <%ae>%x09%cn <%ce>%x09%ad%x09%cd' \
      --date=format:%z < "$tmp/commits" |
    awk -F'\t' -v ok="$ID_OK" -v picks="$tmp/picks" '
      BEGIN { while ((getline p < picks) > 0) { split(p, x, "\t"); pick[x[1]] = x[2] } }
      { id = substr($1, 1, 10); v = ($1 in pick) ? pick[$1] : ""
        why = ""
        if (v == "upstream") why = ", and its cherry-pick line names no upstream commit"
        if (v == "author") why = ", nor the author and date of the upstream commit it names"
        if ($2 != ok && v != "ok") print "identity: " id " author is not " ok why
        if ($3 != ok) print "identity: " id " committer is not " ok
        if ($4 != "+0000" && v != "ok") print "timezone: " id " author date is not +0000"
        if ($5 != "+0000") print "timezone: " id " committer date is not +0000"
      }' >> "$tmp/out"
fi
if [ "$(git cat-file -t "$NEW")" = tag ]; then
  tagger=$(git cat-file tag "$NEW" | sed -n 's/^tagger //p' | head -n 1)
  tag_id=${tagger% * *}; tag_zone=${tagger##* }
  [ "$tag_id" = "$ID_OK" ] || echo "identity: tag $NEW tagger is not $ID_OK" >> "$tmp/out"
  [ "$tag_zone" = "+0000" ] || echo "timezone: tag $NEW date is not +0000" >> "$tmp/out"
fi

# --- added lines, file by file (merges as combined diffs, so only what the merge itself adds) -------
# idx gets "commit TAB path TAB line TAB is-test-file" and txt the line itself, one line each. The
# file's name gets a line of its own ("name"), so names are checked too.
fixture='(^|/)(test|tests|androidTest|testdata|fixtures?|__tests__)/|Tests?[.](java|kt)$|_test[.][A-Za-z]+$'
{
  if [ -s "$tmp/commits" ]; then
    git -c core.quotePath=false log --no-walk=unsorted --stdin -p --cc --no-renames --diff-filter=d \
        --text --no-color --no-ext-diff --no-textconv --format='%x01C %H%n%B%x01E' < "$tmp/commits"
  fi
  if [ "$(git cat-file -t "$NEW")" = tag ]; then
    printf '\001C %s\n' "$(git rev-parse "$NEW")"
    git cat-file tag "$NEW" | sed '1,/^$/d'
    printf '\001E\n'
  fi
} | awk -v idx="$tmp/idx" -v txt="$tmp/txt" -v paths="$tmp/paths" -v fixture="$fixture" '
  function out(s) { print c "\t" f "\t" ln "\t" fx > idx; print s > txt }
  /^\001C / { c = substr($0, 4); f = "(message)"; fx = 0; ln = 0; mode = "msg"; next }
  /^\001E$/ { mode = "pre"; next }
  mode == "msg" { ln++; out($0); next }
  /^diff --(git|cc|combined) / { mode = "hdr"; f = "?"; next }
  mode == "hdr" && /^\+\+\+ / {
    f = substr($0, 5); sub(/^b\//, "", f); fx = (f ~ fixture) ? 1 : 0
    print c "\t" f > paths; ln = "name"; out(f); next }
  /^@@/ && (mode == "hdr" || mode == "hunk") {
    np = 0; while (substr($0, np + 1, 1) == "@") np++; np--
    match($0, / \+[0-9]+/); ln = substr($0, RSTART + 2, RLENGTH - 2) - 1; mode = "hunk"; next }
  mode == "hunk" {
    if (substr($0, 1, 1) == "\\") next
    p = substr($0, 1, np); if (p ~ /-/) next
    ln++; if (p ~ /\+/) out(substr($0, np + 1)); next }
'
touch "$tmp/idx" "$tmp/txt" "$tmp/paths"

# Prints "check: commit path:line [rule]" once per commit, file and rule, with a count of extra lines.
report() { # check rule  (line numbers of txt on stdin)
  awk -F'\t' -v check="$1" -v rule="$2" -v allowf="$tmp/allow" '
    BEGIN { while ((getline a < allowf) > 0) { split(a, x, "\t"); allow[x[1] "\t" x[2]] = 1 } }
    NR == FNR { want[$1] = 1; next }
    (FNR in want) && !(($1 "\t" $2) in allow) {
      k = $1 "\t" $2
      if (!(k in first)) { first[k] = $3; order[++n] = k } else more[k]++
    }
    END { for (i = 1; i <= n; i++) { k = order[i]; split(k, x, "\t")
            printf "%s: %s %s:%s [%s]%s\n", check, substr(x[1], 1, 10), x[2], first[k], rule,
                   (more[k] ? " and " more[k] " more line(s)" : "") } }
  ' - "$tmp/idx" >> "$tmp/out"
}
rule() { # check rule grep-flags regex
  grep -n -a $3 -E -e "$4" "$tmp/txt" | cut -d: -f1 | report "$1" "$2"
}

# --- 2. files that must never be public ------------------------------------------------------------
awk -F'\t' -v allowf="$tmp/allow" '
  BEGIN { while ((getline a < allowf) > 0) { split(a, x, "\t"); allow[x[1] "\t" x[2]] = 1 } }
  !(($1 "\t" $2) in allow) && ($2 ~ /(^|\/)(keystore|local)\.properties$|\.(jks|keystore|p12|pfx|pem|key|bks)$|(^|\/)id_(rsa|dsa|ecdsa|ed25519)$|(^|\/)\.env(\.|$)|^private\/|^docs\/release\/reddit/) {
    print "forbidden file: " substr($1, 1, 10) " " $2 }' "$tmp/paths" | sort -u >> "$tmp/out"

# --- 3. secrets, local paths, private addresses, private patterns ----------------------------------
q="[\"']?"
rule secret "GitHub token" "" 'gh[pousr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{22,}'
rule secret "AWS key" "" 'AKIA[0-9A-Z]{16}'
rule secret "Google API key" "" 'AIza[0-9A-Za-z_-]{35}'
rule secret "Slack token" "" 'xox[abposr]-[A-Za-z0-9-]{10,}'
rule secret "private key" "" '-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY'
rule secret "password" "-i" "(store|key)_?pass(word)?[[:blank:]]*[=:][[:blank:]]*${q}[A-Za-z0-9!@#%^&*_+.,;~-]{4,}${q}[[:blank:]]*,?\$"
rule secret "password" "-i" "(password|passwd|secret|api_?key|auth_?token)[\"']?[[:blank:]]*[=:][[:blank:]]*[\"'][^\"'[:blank:]\$%{}<>]{8,}[\"']"
rule secret "keystore or key in base64" "" 'MII[A-Za-z0-9+/]{120,}'
rule "local path" "user folder" "-i" '[A-Za-z]:[\\/]+(Users|Documents and Settings)[\\/]+[A-Za-z0-9._-]|(^|[^A-Za-z0-9])/c/Users/[A-Za-z0-9._-]|(^|[ "'"'"'=(])/(Users|home)/[A-Za-z0-9._-]+/'

# Private IPv4 host addresses (not network ranges like 192.168.0.0/16 or .0/.255), outside test files
awk -F'\t' -v txt="$tmp/txt" '
  { getline s < txt; if ($4 == 1) next
    while (match(s, /[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/)) {
      a = substr(s, RSTART, RLENGTH); pre = substr(s, RSTART - 1, 1); post = substr(s, RSTART + RLENGTH, 2)
      s = substr(s, RSTART + RLENGTH)
      if (RSTART > 1 && pre ~ /[0-9.]/) continue
      if (post ~ /^[0-9]/ || post ~ /^\.[0-9]/ || post ~ /^\//) continue
      split(a, o, "."); if (o[1] > 255 || o[2] > 255 || o[3] > 255 || o[4] > 255) continue
      if (o[4] == 0 || o[4] == 255) continue
      if (o[1] == 10 || (o[1] == 172 && o[2] >= 16 && o[2] <= 31) || (o[1] == 192 && o[2] == 168)) {
        print NR; break }
    } }' "$tmp/idx" | report "private address" "RFC 1918 host address outside a test file"

while IFS=$'\t' read -r n p; do
  rule "personal" "private pattern on line $n" "-i" "$p"
done < "$tmp/patterns"

# --- 4. placeholders left in public text ------------------------------------------------------------
git grep -n -I -E 'DELAYTBD|(^|[^A-Za-z0-9_])TBD([^A-Za-z0-9_]|$)|<release date>' "$NEW" -- \
    README.md docs windows/README.md .github/release-notes 2>/dev/null |
  sed -e 's/^[^:]*://' -e 's/^\([^:]*:[0-9]*\):.*/placeholder: \1/' >> "$tmp/out"

if [ -s "$tmp/out" ]; then
  sort "$tmp/out" | awk -v max=$MAX_LINES '{ if (NR <= max) print; n = NR }
    END { if (n > max) print "... and " n - max " more" }'
  echo "FAIL: fix the lines above before pushing"
  exit 1
fi
echo "clean"
