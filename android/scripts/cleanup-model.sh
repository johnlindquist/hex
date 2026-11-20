#!/usr/bin/env bash
# Provision optional cleanup weights; installing never enables cleanup.
set -euo pipefail
usage() { echo 'usage: cleanup-model.sh SERIAL status | SERIAL install MODEL_FILE' >&2; exit 2; }
[[ $# -ge 2 && -n $1 ]] || usage
ad=(adb -s "$1" shell -T)
pkg=anomaly.hex.android
dest=no_backup/gemma-4-E2B-it.litertlm
sum=181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
case $2 in
  status) [[ $# == 2 ]] || usage
    "${ad[@]}" run-as "$pkg" ls -ln "$dest"; exit;;
  install) [[ $# == 3 ]] || usage;;
  *) usage;;
esac
source "$(dirname "$0")/fetch-deps.sh"
[[ -f $3 && $(sha256 "$3") == "$sum" ]] || { echo 'Incorrect local model digest' >&2; exit 1; }
"${ad[@]}" run-as "$pkg" mkdir -p no_backup
if [[ $("${ad[@]}" run-as "$pkg" sha256sum "$dest" 2>/dev/null | cut -d' ' -f1) == "$sum" ]]; then echo 'Model already verified'; exit 0; fi
tmp=$dest.staging.$$
trap '"${ad[@]}" run-as "$pkg" rm -f "$tmp" >/dev/null || true' EXIT
"${ad[@]}" "run-as $pkg sh -c 'umask 077; cat > $tmp'" < "$3"
[[ $("${ad[@]}" run-as "$pkg" sha256sum "$tmp" | cut -d' ' -f1) == "$sum" ]] || { echo 'Transfer verification failed' >&2; exit 1; }
"${ad[@]}" run-as "$pkg" mv "$tmp" "$dest"
echo "Installed and verified $sum"
