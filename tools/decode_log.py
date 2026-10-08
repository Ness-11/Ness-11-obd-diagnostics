#!/usr/bin/env python3
"""Restore byte-exact TX/RX/EVENT stream from a session export."""
import argparse
import base64
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("log", type=Path)
parser.add_argument("--direction", choices=["RX", "TX", "EVENT"], default="RX")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
with args.log.open(encoding="utf-8") as source, args.output.open("wb") as destination:
    for index, line in enumerate(source, 1):
        if line.startswith("#") or not line.strip():
            continue
        fields = line.rstrip("\n").split("\t")
        if len(fields) != 3:
            raise ValueError(f"Invalid TSV at line {index}")
        if fields[1] == args.direction:
            destination.write(base64.b64decode(fields[2], validate=True))
