#!/usr/bin/env bash
# Checks commit messages against Conventional Commits 1.0.0 and branch names against Conventional
# Branch, the rules in CONTRIBUTING.md ("Commits, branches and the changelog").
#
#   bash .github/tools/check-commits.sh [--branch NAME] [RANGE | -- REVISION-ARGS...]
#   bash .github/tools/check-commits.sh --msg-file FILE      (as a commit-msg hook)
#   bash .github/tools/check-commits.sh --self-test
#
#   RANGE        the commits to check, passed to git log as it is, like origin/main..HEAD. After
#                "--", any revision arguments work, like: -- NEW --not --remotes=origin
#                With no range (and no --branch), it checks what your next push adds (the current
#                branch's upstream..HEAD), or origin/main..HEAD (what a pull request into main
#                adds) when the branch has no upstream.
#   --branch     checks a branch name too. A leading refs/heads/ is ignored.
#   --msg-file   checks one message, for .git/hooks/commit-msg. It lets git's own "Merge ...",
#                "Revert ..." and fixup!/squash!/amend! messages through, so merging, reverting and
#                autosquash keep working. The pull request check still asks for those to be
#                reworded ("revert: ...") or squashed before merging. It also lets an upstream
#                commit brought in with git cherry-pick -x through, as the range check skips it.
#   --self-test  runs planted good and bad messages, branch names and commits (in a throwaway repo
#                in a temporary folder) and says if any is classified wrong.
#
# In a range it skips merge commits, commits cherry-picked from upstream (the message's last line
# is "(cherry picked from commit <sha>)", which git cherry-pick -x writes) and commits whose
# committer date is before CUTOFF below, when these rules started.
# It only reads the repo: no network, no changes, and the same input always gives the same output.
# Exit 0 = fine, 1 = something to fix, 2 = usage error, or the commits couldn't be read.
#
# Needs bash and git (both in Git for Windows). It avoids bash 4 features, so macOS's bash works.

set -u
export LC_ALL=C

# The moment the rules start: the current UTC time, to the minute, when this script was written.
# Commits with an earlier committer date predate the rules and are skipped. The two must agree
# (the self-test checks that where GNU date is available).
CUTOFF_UTC="2026-10-23 18:08"
CUTOFF_EPOCH=1792778880

TYPES="feat fix docs test ci build refactor perf chore revert"
BRANCH_TYPES="feature bugfix hotfix release chore feat fix ai claude codex copilot cursor"

RE_HEADER='^([A-Za-z]+)(\(([^()]*)\))?(!)?:(.*)$'
RE_SCOPE='^[a-z0-9]+([._/-][a-z0-9]+)*$'
RE_PICK='^\(cherry picked from commit [0-9a-f]{7,40}\)$'
RE_BRANCH='^(feature|bugfix|hotfix|release|chore|feat|fix|ai|claude|codex|copilot|cursor)/[a-z0-9]+(\.[a-z0-9]+)*(-[a-z0-9]+(\.[a-z0-9]+)*)*$'
RE_BRANCH_BADCHAR='[^A-Za-z0-9._/[:space:]-]'

usage() {
  echo "usage: check-commits.sh [--branch NAME] [RANGE | -- REVISION-ARGS...]" >&2
  echo "       check-commits.sh --msg-file FILE" >&2
  echo "       check-commits.sh --self-test" >&2
  exit 2
}

in_list() { case " $2 " in *" $1 "*) return 0 ;; esac; return 1; }
lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }
bad() { VERDICT=bad; PROBLEMS+=("$1"); }

# --- one message ----------------------------------------------------------------------------------
# check_message MODE MESSAGE sets VERDICT (good, skip or bad) and PROBLEMS (what to change, one per
# entry). MODE is "range" for a commit already made, or "hook" for a message about to be committed.
check_message() {
  local mode=$1 msg=$2 subject rest second type scope_part scope bang after desc first word base t
  local types_list last
  VERDICT=good; PROBLEMS=()
  msg=${msg//$'\r'/}
  subject=${msg%%$'\n'*}
  rest=""; second=""
  if [ "$subject" != "$msg" ]; then rest=${msg#*$'\n'}; second=${rest%%$'\n'*}; fi
  types_list=${TYPES// /, }

  if [ -z "$subject" ]; then bad "the message is empty"; return; fi
  if [ "$mode" = hook ]; then
    case $subject in "Merge "*|"Revert "*|"fixup!"*|"squash!"*|"amend!"*) VERDICT=skip; return ;; esac
    # git cherry-pick -x from upstream: the range check skips these by the same last line
    last=${msg%$'\n'}
    last=${last##*$'\n'}
    if [[ $last =~ $RE_PICK ]]; then VERDICT=skip; return; fi
  fi
  case $subject in
    "fixup!"*|"squash!"*|"amend!"*)
      bad "fold this into the commit it fixes: git rebase -i --autosquash <base>"; return ;;
    'Revert "'*)
      bad "reword git's revert message as \"revert: <what it undoes>\", with a \"Refs: <sha>\" footer"
      return ;;
  esac

  if [[ ! $subject =~ $RE_HEADER ]]; then
    bad "start with a type and a colon, like \"fix: ...\" or \"feat(mic): ...\" (types: $types_list)"
  else
    type=${BASH_REMATCH[1]} scope_part=${BASH_REMATCH[2]} scope=${BASH_REMATCH[3]}
    bang=${BASH_REMATCH[4]} after=${BASH_REMATCH[5]}
    t=$type
    [[ $type != *[[:upper:]]* ]] || t=$(lower "$type")
    if ! in_list "$t" "$TYPES"; then
      base=""
      for word in $TYPES; do case $t in "$word"*) base=$word; break ;; esac; done
      if [ -n "$base" ]; then
        bad "\"$type\" isn't one of the types used here; did you mean \"$base\"? (types: $types_list)"
      else
        bad "\"$type\" isn't one of the types used here: $types_list"
      fi
    elif [ "$t" != "$type" ]; then
      bad "write the type in lower case: \"$t$scope_part$bang:\", not \"$type$scope_part$bang:\""
    fi
    if [ -n "$scope_part" ] && [[ ! $scope =~ $RE_SCOPE ]]; then
      bad "write the scope in lower case, with hyphens between words, like (mic) or (game-screen)"
    fi
    if [ -z "${after// /}" ]; then
      bad "add a short description after the colon"
    elif [ "${after:0:1}" != " " ] || [ "${after:1:1}" = " " ]; then
      bad "put one space after the colon, then the description"
    else
      desc=${after:1}
      first=${desc:0:1}
      if [[ $first == [[:upper:]] ]]; then
        bad "start the description in lower case: \"$t$scope_part$bang: $(lower "$first")${desc:1}\""
      fi
      word=${desc%% *}
      case $word in
        [Aa]dded|[Aa]dds|[Aa]dding) base=add ;;
        [Ff]ixed|[Ff]ixes|[Ff]ixing) base=fix ;;
        [Uu]pdated|[Uu]pdates|[Uu]pdating) base=update ;;
        [Rr]emoved|[Rr]emoves|[Rr]emoving) base=remove ;;
        [Cc]hanged|[Cc]hanges|[Cc]hanging) base=change ;;
        [Ii]mproved|[Ii]mproves|[Ii]mproving) base=improve ;;
        [Rr]enamed|[Rr]enames|[Rr]enaming) base=rename ;;
        [Mm]oved|[Mm]oves|[Mm]oving) base=move ;;
        *) base="" ;;
      esac
      [ -z "$base" ] || bad "write the description as a command: \"$base ...\", not \"$word ...\""
    fi
  fi

  if [ -n "$second" ]; then bad "leave a blank line between the subject and the body"; fi
  while IFS= read -r word; do
    case $word in
      [Bb][Rr][Ee][Aa][Kk][Ii][Nn][Gg][\ -][Cc][Hh][Aa][Nn][Gg][Ee]:*)
        case $word in
          "BREAKING CHANGE: "[!\ ]*|"BREAKING-CHANGE: "[!\ ]*) ;;
          *) bad "write the footer as \"BREAKING CHANGE: <what breaks>\", in capitals"; break ;;
        esac ;;
    esac
  done <<< "$rest"
}

# --- one branch name ------------------------------------------------------------------------------
# check_branch NAME sets VERDICT (good or bad), PROBLEMS and SUGGESTION (a name that passes, or "").
check_branch() {
  local b=${1#refs/heads/} type desc s t d
  VERDICT=good; PROBLEMS=(); SUGGESTION=""
  case $b in main|master|develop) return ;; esac
  if [ -z "$b" ]; then bad "the branch name is empty"; return; fi

  if [[ $b != */* ]]; then
    bad "start with a type and a slash: feature/, bugfix/, hotfix/, release/ or chore/ (or use main)"
    type="" desc=$b
  else
    type=${b%%/*} desc=${b#*/}
    t=$type
    [[ $type != *[[:upper:]]* ]] || t=$(lower "$type")
    in_list "$t" "$BRANCH_TYPES" ||
      bad "\"$type/\" isn't a branch type: use feature/, bugfix/, hotfix/, release/ or chore/"
  fi
  if [ -z "$desc" ]; then bad "add a short description after the slash, like feature/dualsense-triggers"; fi
  if [[ $b == *[[:upper:]]* ]]; then bad "use lower case only"; fi
  if [[ $desc == *[_[:space:]]* ]]; then bad "use hyphens between words, not underscores or spaces"; fi
  if [[ $desc == */* ]]; then bad "use only one slash, the one after the type"; fi
  if [[ $desc =~ $RE_BRANCH_BADCHAR ]]; then bad "use only letters, digits, hyphens and dots"; fi
  if [[ $desc == [-.]* || $desc == *[-.] || $desc == *[-.][-.]* ]]; then
    bad "don't start or end with a hyphen or a dot, or put two of them side by side (like -- or -.)"
  fi
  if [ "$VERDICT" = good ] && [[ ! $b =~ $RE_BRANCH ]]; then
    bad "use <type>/<description>, like feature/dualsense-triggers or release/v0.2.1"
  fi
  [ "$VERDICT" = bad ] || return

  # A name that would pass: lower case, hyphens for separators, nothing else changed.
  s=$(lower "$b")
  if [[ $s == */* ]]; then t=${s%%/*} d=${s#*/}; else t=feature d=$s; fi
  in_list "$t" "$BRANCH_TYPES" || t=feature
  d=${d//[_[:space:]\/]/-}
  d=${d//[!a-z0-9.-]/}
  while [[ $d == *--* || $d == *-.* || $d == *.-* || $d == *..* ]]; do
    d=${d//--/-}; d=${d//-./.}; d=${d//.-/.}; d=${d//../.}
  done
  while [[ $d == [-.]* ]]; do d=${d:1}; done
  while [[ $d == *[-.] ]]; do d=${d%?}; done
  if [ -n "$d" ] && [[ $t/$d =~ $RE_BRANCH ]]; then SUGGESTION=$t/$d; fi
}

report_branch() { # NAME; prints what's wrong and returns 1, or returns 0
  local p
  check_branch "$1"
  if [ "$VERDICT" = good ]; then echo "branch \"$1\" is fine"; return 0; fi
  echo "branch \"$1\" doesn't follow Conventional Branch:"
  for p in "${PROBLEMS[@]}"; do echo "  - $p"; done
  if [ -n "$SUGGESTION" ]; then
    echo "  to fix: git branch -m \"$1\" $SUGGESTION and push that. A pull request can't change"
    echo "  its branch, so open a new one from it and close the old one."
  fi
  gh_error "Branch name" "\"$1\": ${PROBLEMS[0]}"
  return 1
}

# GitHub Actions annotation, so the problem shows on the pull request too.
gh_error() {
  [ "${GITHUB_ACTIONS:-}" = true ] || return 0
  local m=$2
  m=${m//%/%25}; m=${m//$'\r'/%0D}; m=${m//$'\n'/%0A}
  printf '::error title=%s::%s\n' "$1" "$m"
}

# --- commits --------------------------------------------------------------------------------------
# classify_commit SHA COMMITTER-EPOCH PARENTS... sets VERDICT (good, bad, merge, cherry-pick or
# early), PROBLEMS and SUBJECT.
classify_commit() {
  local sha=$1 ct=$2 msg last
  shift 2
  SUBJECT=""; PROBLEMS=()
  if [ $# -gt 1 ]; then VERDICT=merge; return; fi
  if [ "$ct" -lt "$CUTOFF_EPOCH" ]; then VERDICT=early; return; fi
  msg=$(git log -1 --no-show-signature --format=%B "$sha")
  msg=${msg//$'\r'/}
  SUBJECT=${msg%%$'\n'*}
  last=${msg##*$'\n'}
  if [[ $last =~ $RE_PICK ]]; then VERDICT=cherry-pick; return; fi
  check_message range "$msg"
}

check_range() { # revision arguments for git log
  local list sha ct parents n=0 good=0 nbad=0 merges=0 picks=0 early=0 p list_read=0
  if ! list=$(git log --reverse --no-show-signature --format='%H %ct %P' "$@"); then
    echo "can't read the commits in: $*" >&2
    exit 2
  fi
  echo "checking the commits in: $*"
  # The loop has to reach the "end" line after the commits. Bash keeps a here-string over 64 KiB
  # in a temp file, and on a full disk the loop would read nothing and pass.
  while read -r sha ct parents; do
    if [ "$sha" = end ]; then list_read=1; break; fi
    [ -n "$sha" ] || continue
    n=$((n + 1))
    # shellcheck disable=SC2086
    classify_commit "$sha" "$ct" $parents
    case $VERDICT in
      good) good=$((good + 1)) ;;
      merge) merges=$((merges + 1)) ;;
      cherry-pick) picks=$((picks + 1)) ;;
      early) early=$((early + 1)) ;;
      bad)
        nbad=$((nbad + 1))
        echo "${sha:0:10} \"$SUBJECT\""
        for p in "${PROBLEMS[@]}"; do echo "  - $p"; done
        gh_error "Commit ${sha:0:10}" "\"$SUBJECT\": ${PROBLEMS[0]}" ;;
    esac
  done <<< "$list"$'\n'"end"
  if [ $list_read = 0 ]; then
    echo "couldn't read the list of commits in: $* (is the disk full?), so they weren't checked" >&2
    exit 2
  fi
  echo "$n commit(s): $good fine, $nbad to fix; skipped $merges merge(s), $picks cherry-pick(s) from" \
       "upstream and $early from before the rules started ($CUTOFF_UTC UTC)"
  if [ $nbad -gt 0 ]; then
    echo "To fix: git commit --amend for the latest commit, or git rebase -i <base> and \"reword\" for"
    echo "older ones, then git push --force-with-lease. CONTRIBUTING.md has the rules."
    return 1
  fi
  return 0
}

# --- commit-msg hook ------------------------------------------------------------------------------
check_msg_file() { # FILE
  local file=$1 text="" line started=0 p
  [ -f "$file" ] || { echo "no such file: $file" >&2; exit 2; }
  # What git will keep: comment lines and everything below the scissors line go.
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    case $line in
      "# ------------------------ >8 ------------------------"*) break ;;
      "#"*) continue ;;
    esac
    if [ $started = 0 ] && [ -z "${line//[[:space:]]/}" ]; then continue; fi
    started=1
    text=$text$line$'\n'
  done < "$file"
  while [[ $text == *$'\n\n' ]]; do text=${text%$'\n'}; done
  check_message hook "$text"
  [ "$VERDICT" = bad ] || return 0
  {
    echo "commit message \"${text%%$'\n'*}\" doesn't follow Conventional Commits:"
    for p in "${PROBLEMS[@]}"; do echo "  - $p"; done
    echo "Nothing was committed. To try again with this message: git commit -e -F \"$file\""
  } >&2
  return 1
}

# --- self-test ------------------------------------------------------------------------------------
self_test() {
  local total=0 wrong=0 tmp root s side2 c_early c_edge c_good c_bad c_pick c_late c_side c_merge
  local out status sha

  result() { # expected got label
    total=$((total + 1))
    if [ "$1" = "$2" ]; then printf 'ok     %-12s %s\n' "$2" "$3"
    else printf 'WRONG  %-12s %s (expected %s)\n' "$2" "$3" "$1"; wrong=$((wrong + 1)); fi
  }
  msg() { # mode expected message
    check_message "$1" "$3"
    local label=${3//$'\n'/\\n}
    result "$2" "$VERDICT" "$1: $label"
  }
  branch() { # expected name
    check_branch "$2"
    result "$1" "$VERDICT" "branch: $2"
  }

  if s=$(date -u -d "$CUTOFF_UTC" +%s 2>/dev/null); then
    result "$CUTOFF_EPOCH" "$s" "CUTOFF_UTC and CUTOFF_EPOCH agree"
  fi

  # Messages, as a commit already made
  msg range good "fix: stop the crash when the games screen resizes"
  msg range good "feat(mic)!: encrypt the microphone and accept only paired devices"
  msg range good "docs: add CHANGELOG.md in the Keep a Changelog format"
  msg range good "fix(game-screen): stop the \"PC not paired\" flicker"
  msg range good "perf: decode on two threads"
  msg range good "test(stream): cover the resume button on android 5"
  msg range good "fix: 2 crashes on unusual host replies"
  msg range good $'ci: check commit messages\n\nThe body says why.\n\nRefs: #12'
  msg range good $'revert: bring back the old stream menu\n\nRefs: 676104e'
  msg range good $'feat(dualsense): add trigger effects\n\nBREAKING CHANGE: the old trigger setting is gone'
  msg range good $'fix: stop the echo\r\n\r\nWindows line endings.\r\n'
  msg range bad "Fix the thing"
  msg range bad "fixed: the thing"
  msg range bad "Fix: the thing"
  msg range bad "FEAT: add a thing"
  msg range bad "style: reformat the settings screen"
  msg range bad "fix:the thing"
  msg range bad "fix:  the thing"
  msg range bad "fix: "
  msg range bad "fix: Stop the crash"
  msg range bad "fix(Mic): stop the echo"
  msg range bad "fix(): stop the echo"
  msg range bad "fix(mic) : stop the echo"
  msg range bad "feat: added a setting"
  msg range bad "fix: fixes the crash"
  msg range bad $'fix: stop the crash\nthe body without a blank line'
  msg range bad $'feat!: drop android 4\n\nbreaking change: android 4 is gone'
  msg range bad ""
  msg range bad "fixup! fix: stop the crash"
  msg range bad "squash! fix: stop the crash"
  msg range bad 'Revert "feat: add trigger effects"'
  msg range bad "Merge branch 'main' into feature/x"
  # Messages about to be committed (commit-msg hook)
  msg hook skip "Merge branch 'feature/dualsense-triggers'"
  msg hook skip 'Revert "feat: add trigger effects"'
  msg hook skip "fixup! fix: stop the crash"
  msg hook skip "squash! fix: stop the crash"
  msg hook good "fix: stop the crash"
  msg hook bad "Fix the thing"
  msg hook bad "fixed: the thing"
  msg hook skip $'Port the fix\n\nCo-Authored-By: Test <test@example.invalid>\n(cherry picked from commit 0123456789abcdef0123456789abcdef01234567)\n'
  msg hook bad $'Port the fix\n\n(cherry picked from commit 0123456)\n\nA note below the line'
  msg range bad $'Port the fix\n\n(cherry picked from commit 0123456)\n\nA note below the line'

  # Branch names
  for s in main master develop release/v0.2.1 feature/dualsense-triggers bugfix/mic-echo \
           hotfix/security-patch chore/upgrade-node-v20.11 feat/add-login-page fix/header-bug \
           feature/issue-123-new-login refs/heads/release/v0.2.1; do
    branch good "$s"
  done
  for s in feature/Bad_Name Feature/add-login feature/new--login feature/-new-login \
           feature/new-login- release/v1.-2.0 release/v1.2.0. "fix/header bug" fix/header_bug \
           unknown/some-task feature/ staging feature/a/b "" "main " feature/new..login; do
    branch bad "$s"
  done
  check_branch feature/Bad_Name
  result feature/bad-name "$SUGGESTION" "suggested name for feature/Bad_Name"

  # Commits, in a throwaway repo
  tmp=$(mktemp -d) || { echo "self-test: can't make a temporary folder" >&2; return 2; }
  root=$tmp/repo
  g() {
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null git -C "$root" -c user.name=Test \
      -c user.email=test@example.invalid -c commit.gpgsign=false -c core.hooksPath=/dev/null "$@"
  }
  at() { # epoch git-arguments...
    local when="@$1 +0000"; shift
    GIT_AUTHOR_DATE=$when GIT_COMMITTER_DATE=$when g "$@"
  }
  if ! GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null git -c init.defaultBranch=main init -q "$root"; then
    rm -rf "$tmp"; echo "self-test: git init failed" >&2; return 2
  fi
  local before=$((CUTOFF_EPOCH - 86400)) after=$((CUTOFF_EPOCH + 3600))
  at $before commit -q --allow-empty -m "Update stuff"
  c_early=$(g rev-parse HEAD)
  at $before checkout -q -b side
  at $((after + 1)) commit -q --allow-empty -m "Port the upstream fix"
  c_side=$(g rev-parse HEAD)
  at $before checkout -q main
  at $CUTOFF_EPOCH commit -q --allow-empty -m "Fix the thing at the exact cutoff"
  c_edge=$(g rev-parse HEAD)
  at $((after + 2)) commit -q --allow-empty -m "fix: stop the crash"
  c_good=$(g rev-parse HEAD)
  at $((after + 3)) commit -q --allow-empty -m "Fix the thing"
  c_bad=$(g rev-parse HEAD)
  at $((after + 4)) cherry-pick -x --allow-empty "$c_side" >/dev/null
  c_pick=$(g rev-parse HEAD)
  at $((after + 5)) commit -q --allow-empty -m "Port another fix" \
    -m "(cherry picked from commit $c_side)" -m "Signed-off-by: Test <test@example.invalid>"
  c_late=$(g rev-parse HEAD)
  at $((after + 6)) checkout -q -b side2
  at $((after + 7)) commit -q --allow-empty -m "feat: add the side feature"
  side2=$(g rev-parse HEAD)
  at $((after + 8)) checkout -q main
  at $((after + 9)) merge -q --no-ff -m "Merge branch 'side2'" side2
  c_merge=$(g rev-parse HEAD)

  for s in "early $c_early before the cutoff, bad subject" \
           "bad $c_edge at the exact cutoff, bad subject" \
           "good $c_good after the cutoff, good subject" \
           "bad $c_bad after the cutoff, bad subject" \
           "cherry-pick $c_pick cherry-picked with -x, bad subject" \
           "bad $c_late cherry-pick line not last, bad subject" \
           "good $side2 on a merged branch, good subject" \
           "merge $c_merge merge commit"; do
    set -- $s
    sha=$2
    # shellcheck disable=SC2046
    (cd "$root" && classify_commit "$sha" $(g log -1 --format='%ct %P' "$sha") && echo "$VERDICT") > "$tmp/v"
    result "$1" "$(cat "$tmp/v")" "commit: ${s#* * }"
  done

  out=$(cd "$root" && GITHUB_ACTIONS= check_range main); status=$?
  result 1 "$status" "range main exits 1"
  case $out in *"8 commit(s): 2 fine, 3 to fix; skipped 1 merge(s), 1 cherry-pick(s) from upstream and 1 from"*)
    s=summary-ok ;; *) s=summary-wrong ;; esac
  result summary-ok "$s" "range main counts 2 fine, 3 to fix and 3 skipped"
  out=$(cd "$root" && GITHUB_ACTIONS= check_range "$c_bad..$c_pick"); status=$?
  result 0 "$status" "range with only a cherry-pick exits 0"

  # A list of commits over 64 KiB, which bash keeps in a temp file. On a full disk it can't write
  # it and the loop gets nothing. This BASH_ENV plays that: a read from a temp file gets nothing,
  # a read from a pipe works.
  printf '%s\n' 'read() { if [ -f /dev/stdin ]; then return 1; fi; builtin read "$@"; }' > "$tmp/full-disk.sh"
  s=0
  while [ $s -lt 1000 ]; do
    printf 'commit refs/heads/long\ncommitter Test <test@example.invalid> %s +0000\ndata <<END\nFix the thing, part %s\nEND\n' \
      $((after + 10 + s)) $s
    [ $s -gt 0 ] || printf 'from %s\n' "$c_merge"
    s=$((s + 1))
  done > "$tmp/long.txt"
  g fast-import --quiet < "$tmp/long.txt"
  out=$(g log --format='%H %ct %P' main..long)
  if { [ -p /dev/stdin ]; } <<< "short" && { [ -f /dev/stdin ]; } <<< "$out"; then
    s=$(cd "$(dirname "$SELF")" && pwd)/$(basename "$SELF")
    (cd "$root" && BASH_ENV=$tmp/full-disk.sh GITHUB_ACTIONS= bash "$s" main..long >/dev/null 2>&1); status=$?
    result 2 "$status" "range main..long (1000 commits) on a full disk exits 2"
  else
    echo "skipped: a range on a full disk (this bash doesn't keep a long here-string in a temp file)"
  fi

  # The commit-msg hook and --branch, end to end
  printf '%s\n' "fix: stop the crash" "" "# Please enter the commit message for your changes." \
    "# ------------------------ >8 ------------------------" "Fix the thing below the scissors" > "$tmp/m1"
  printf '%s\n' "Fix the thing" "# a comment" > "$tmp/m2"
  printf '%s\n' "Merge branch 'side2'" > "$tmp/m3"
  printf '%s\r\n' "" "fix: stop the crash" "" "Windows line endings." > "$tmp/m4"
  # What git cherry-pick -x leaves for git cherry-pick --continue after a conflict
  printf '%s\n' "Port the fix" "" "(cherry picked from commit $c_side)" "" "# Conflicts:" \
    "#	app/src/main/res/values/strings.xml" > "$tmp/m5"
  for s in "0 m1" "1 m2" "0 m3" "0 m4" "0 m5"; do
    set -- $s
    bash "$SELF" --msg-file "$tmp/$2" >/dev/null 2>&1; status=$?
    result "$1" "$status" "--msg-file $2 exits $1"
  done
  GITHUB_ACTIONS= bash "$SELF" --branch release/v0.2.1 >/dev/null 2>&1; status=$?
  result 0 "$status" "--branch release/v0.2.1 exits 0"
  GITHUB_ACTIONS= bash "$SELF" --branch feature/Bad_Name >/dev/null 2>&1; status=$?
  result 1 "$status" "--branch feature/Bad_Name exits 1"

  rm -rf "$tmp"
  if [ $wrong -gt 0 ]; then echo "self-test: $wrong of $total wrong"; return 1; fi
  echo "self-test: all $total classified correctly"
  return 0
}

# --- main -----------------------------------------------------------------------------------------
SELF=${BASH_SOURCE[0]}
branch="" have_branch=0 msg_file="" self_test=0 nargs=0
args=()
while [ $# -gt 0 ]; do
  case $1 in
    --branch) [ $# -ge 2 ] || usage; branch=$2; have_branch=1; shift 2 ;;
    --branch=*) branch=${1#*=}; have_branch=1; shift ;;
    --msg-file) [ $# -ge 2 ] || usage; msg_file=$2; shift 2 ;;
    --msg-file=*) msg_file=${1#*=}; shift ;;
    --self-test) self_test=1; shift ;;
    -h|--help) sed -n '2,/^$/s/^# \{0,1\}//p' "$SELF"; exit 0 ;;
    --) shift; while [ $# -gt 0 ]; do args+=("$1"); nargs=$((nargs + 1)); shift; done ;;
    -*) echo "unknown option: $1" >&2; usage ;;
    *) args+=("$1"); nargs=$((nargs + 1)); shift ;;
  esac
done

if [ $self_test = 1 ]; then self_test; exit $?; fi
if [ -n "$msg_file" ]; then check_msg_file "$msg_file"; exit $?; fi

status=0
if [ $have_branch = 1 ]; then report_branch "$branch" || status=1; fi
if [ $nargs -gt 0 ] || [ $have_branch = 0 ]; then
  git rev-parse --git-dir >/dev/null 2>&1 || { echo "not in a git repository" >&2; exit 2; }
fi
if [ $nargs -eq 0 ] && [ $have_branch = 0 ]; then
  if git rev-parse -q --verify '@{upstream}' >/dev/null 2>&1; then args=('@{upstream}..HEAD')
  elif git rev-parse -q --verify refs/remotes/origin/main >/dev/null; then args=(origin/main..HEAD)
  else echo "no upstream branch and no origin/main: give a range, like main..HEAD" >&2; exit 2
  fi
  nargs=1
fi
if [ $nargs -gt 0 ]; then
  [ $have_branch = 0 ] || echo
  check_range "${args[@]}" || status=1
fi
exit $status
