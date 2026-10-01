#!/bin/sh
# Fetches the speech model Shebang Voice ships with (OpenAI Whisper base.en, quantised to 5 bits by the
# whisper.cpp project; MIT licence) into the add-on's assets, and checks it. Not kept in git: 57 MB.
set -e
dir="$(dirname "$0")/../voice/src/main/assets/models"
file="$dir/ggml-base.en-q5_1.bin"
sum="4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
mkdir -p "$dir"
if [ ! -f "$file" ] || ! echo "$sum  $file" | sha256sum -c --quiet 2>/dev/null; then
    curl -fL -o "$file.part" https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin
    echo "$sum  $file.part" | sha256sum -c --quiet
    mv "$file.part" "$file"
fi
echo "ok: $file"
