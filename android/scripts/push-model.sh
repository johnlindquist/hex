#!/usr/bin/env bash
# Install into a debuggable app's private storage without a shared-storage copy.
set -euo pipefail
[[ $# == 1 && -n $1 ]] || { echo 'usage: push-model.sh SERIAL' >&2; exit 2; }
cd "$(dirname "$0")/.."
source scripts/fetch-deps.sh
ad=(adb -s "$1" shell -T)
pkg=anomaly.hex.android
dir=no_backup/models/parakeet-tdt-0.6b-ultra-int8
while read -r name digest; do
  [[ -f model/$name && $(sha256 "model/$name") == "$digest" ]] || { echo "Missing or corrupt model/$name; run scripts/fetch-deps.sh" >&2; exit 1; }
done < <(model_pins)
"${ad[@]}" run-as "$pkg" mkdir -p "$dir"
tmp=''; trap '[[ -z $tmp ]] || "${ad[@]}" run-as "$pkg" rm -f "$tmp" >/dev/null || true' EXIT
while read -r name digest; do
  dest=$dir/$name
  if [[ $("${ad[@]}" run-as "$pkg" sha256sum "$dest" </dev/null 2>/dev/null | cut -d' ' -f1) == "$digest" ]]; then continue; fi
  tmp=$dest.staging.$$
  "${ad[@]}" "run-as $pkg sh -c 'umask 077; cat > $tmp'" < "model/$name"
  [[ $("${ad[@]}" run-as "$pkg" sha256sum "$tmp" </dev/null | cut -d' ' -f1) == "$digest" ]] || { echo "Transfer verification failed: $name" >&2; exit 1; }
  "${ad[@]}" run-as "$pkg" mv "$tmp" "$dest" </dev/null; tmp=''
done < <(model_pins)
echo "Verified speech model on $1"
