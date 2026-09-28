#!/usr/bin/env bash
# List releases so unpublished drafts are visible; /releases/tags/{tag} returns 404 for drafts.
set -euo pipefail

: "${REPO:?Repository is required}"
: "${TAG:?Release tag is required}"
if [[ ! "$TAG" =~ ^v[0-9]+\.[0-9]+-vot\.[0-9]+$ ]]; then
  echo "::error::Invalid SmartTube VOX release tag: $TAG" >&2
  exit 1
fi

matches=$(gh api --paginate "repos/$REPO/releases?per_page=100" \
  --jq ".[] | select(.tag_name == \"$TAG\") | [.id, .draft, .tag_name] | @tsv")
if [ -n "$matches" ] && [ "$(printf '%s\n' "$matches" | wc -l)" -ne 1 ]; then
  echo "::error::Multiple releases found for $TAG; refusing ambiguous recovery." >&2
  exit 1
fi
if [ -n "$matches" ]; then
  printf '%s\n' "$matches"
fi
