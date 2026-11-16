#!/usr/bin/env bash
# Lists what's new in each Moonlight version Starwire follows (.github/tools/upstream/sources.txt) since
# the commit last reviewed, as a Markdown report on stdout. Needs only git.
#
#   .github/tools/upstream/check.sh            report for every source
#   .github/tools/upstream/check.sh artemide   one source
#
# After porting or deciding to skip new commits, put the newest reviewed commit in sources.txt.
set -euo pipefail

cd "$(dirname "$0")"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git init -q --bare "$work/repo"
# git fetch ends by starting "git maintenance run --auto", which on Linux goes on in the
# background writing commit-graph files. On 9 Nov 2026 it was still writing when the trap above
# removed the folder, so rm failed and the run failed with it. A repo thrown away at the end needs
# no upkeep, so nothing is left running when the trap runs. (gc.auto is for a git older than 2.29,
# whose fetch ran gc --auto instead.) A failed rm still fails the run, as it would mean something
# else is writing there after git has returned.
git -C "$work/repo" config maintenance.auto false
git -C "$work/repo" config gc.auto 0

total=0
echo "# Upstream changes"
echo
while read -r name repo branch last kind; do
    [[ -z "$name" || "$name" == \#* ]] && continue
    [[ $# -gt 0 && "$name" != "$1" ]] && continue

    url="https://github.com/$repo.git"
    if ! git -C "$work/repo" fetch -q --filter=blob:none "$url" "$branch:refs/src/$name" 2>/dev/null; then
        echo "## $name ($repo)"
        echo "Couldn't fetch \`$branch\`."
        echo
        continue
    fi
    if ! git -C "$work/repo" cat-file -e "$last^{commit}" 2>/dev/null; then
        echo "## $name ($repo)"
        echo "The reviewed commit \`${last:0:8}\` is no longer on \`$branch\` (history was rewritten)."
        echo "Compare by hand: https://github.com/$repo/commits/$branch"
        echo
        continue
    fi

    count=$(git -C "$work/repo" rev-list --count "$last..refs/src/$name")
    [[ "$count" -eq 0 ]] && continue
    total=$((total + count))
    head=$(git -C "$work/repo" rev-parse "refs/src/$name")
    echo "## $name: $count new commit(s) ($kind)"
    echo "https://github.com/$repo/compare/${last:0:12}...${head:0:12}"
    echo
    git -C "$work/repo" log --no-merges --format='- %s (%an, %ad, `%h`)' --date=short -n 25 "$last..refs/src/$name"
    [[ "$count" -gt 25 ]] && echo "- …and $((count - 25)) more"
    echo
done < sources.txt

if [[ "$total" -eq 0 ]]; then
    echo "Nothing new since the last review."
fi
