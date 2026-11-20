#!/usr/bin/env bash
# The device installer also uses this list; never trust a digest from the download.
model_pins() {
  printf '%s\n' \
    'encoder.int8.onnx b43072e1b721f18a3df03fcfeb763ff7c4debdf29843598a3aba2f703e2780a3' \
    'decoder.int8.onnx 1fab98fe6c12aded87d2da66272cc9e148d0a0044ce3850a12fe56302ec4a922' \
    'joiner.int8.onnx 8a71aaccfdba3d451775507a889afd2257d2e754463fd81095a8c8f290f924c2' \
    'tokens.txt d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d'
}
sha256() { shasum -a 256 < "$1" | cut -d' ' -f1; }
[[ ${BASH_SOURCE[0]} == "$0" ]] || return 0
set -euo pipefail
local_root=
if [[ $# == 2 && $1 == --from-local ]]; then
  local_root=$(cd "$2" && pwd -P)
elif [[ $# != 0 ]]; then
  echo 'usage: fetch-deps.sh [--from-local LOCAL_VOICE_ROOT]' >&2; exit 2
fi
cd "$(dirname "$0")/.."
base=https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra-int8/resolve/402e1190ce85b9793118ee223db21fb0d12f52db
mkdir -p app/libs model
tmp=''; trap '[[ -z $tmp ]] || rm -f "$tmp"' EXIT
fetch() {
  local path=$1 digest=$2 url=$3
  if [[ -f $path && $(sha256 "$path") == "$digest" ]]; then return 0; fi
  tmp=$(mktemp "$path.XXXXXX")
  if [[ -n $local_root ]]; then cp "$local_root/$path" "$tmp"
  else curl -fLsS --retry 2 "$url" -o "$tmp"; fi
  [[ $(sha256 "$tmp") == "$digest" ]] || { echo "Checksum mismatch: $path" >&2; exit 1; }
  mv "$tmp" "$path"; tmp=''
}
fetch app/libs/sherpa.aar a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7 https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
while read -r name digest; do fetch "model/$name" "$digest" "$base/$name"; done < <(model_pins)
