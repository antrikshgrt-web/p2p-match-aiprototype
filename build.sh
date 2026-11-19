#!/bin/bash
# Compiles all Java sources into the out/ directory.
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
find src -name "*.java" > sources.txt
javac -d out @sources.txt
echo "Build successful. Run ./run.sh to start the server."
