#!/bin/bash
mkdir -p landing data/archive web out
java --add-modules jdk.httpserver -cp out com.ubid.Main
