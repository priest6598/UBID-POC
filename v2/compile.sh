#!/bin/bash
set -e
mkdir -p out
echo "Compiling UBID Platform..."
javac --add-modules jdk.httpserver -d out $(find src -name "*.java")
echo "Compilation successful"
