#!/usr/bin/env bash
#
# Downloads all required model and lexicon files into the data/ directory.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="${1:-$SCRIPT_DIR/../data}"

mkdir -p "$DATA_DIR"

URLS=(
  "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.int8.onnx"
  "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin"
  "https://huggingface.co/hexgrad/Kokoro-82M/resolve/main/config.json"
  "https://raw.githubusercontent.com/hexgrad/misaki/main/misaki/data/us_gold.json"
  "https://raw.githubusercontent.com/hexgrad/misaki/main/misaki/data/us_silver.json"
  "https://raw.githubusercontent.com/hexgrad/misaki/main/misaki/data/gb_gold.json"
  "https://raw.githubusercontent.com/hexgrad/misaki/main/misaki/data/gb_silver.json"
  "https://opennlp.sourceforge.net/models-1.5/en-pos-perceptron.bin"
)

for url in "${URLS[@]}"; do
  filename="$(basename "$url")"
  dest="$DATA_DIR/$filename"

  if [ -f "$dest" ]; then
    echo "Already exists: $filename"
    continue
  fi

  echo "Downloading $filename..."
  curl -fSL --progress-bar -o "$dest" "$url"
done

echo "Done. All files are in $DATA_DIR"
