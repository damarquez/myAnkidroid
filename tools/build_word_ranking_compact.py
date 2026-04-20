from __future__ import annotations

import gzip
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT.parent / "ankiMandarin" / "word_ranking.txt"
DEFAULT_TARGET = ROOT / "AnkiDroid" / "src" / "main" / "assets" / "ranking" / "word_ranking_compact.dat"

FORMAT_CHARS = re.compile(r"[\u200c\u200d\u2060\ufeff]")


def decode_key(raw: str) -> str:
    cleaned = FORMAT_CHARS.sub("", raw)
    return "".join(ch for ch in cleaned if ch.isalpha()).lower()


def build_compact_asset(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    rows = 0
    with source.open("r", encoding="utf-8") as fin, gzip.open(target, "wt", encoding="utf-8", newline="\n", compresslevel=9) as fout:
        for line in fin:
            line = line.rstrip("\n")
            if not line or "\t" not in line:
                continue
            term, rest = line.split("\t", 1)
            char_rank = ""
            global_rank = ""
            for part in rest.split("|"):
                part = part.strip()
                if ":" not in part:
                    continue
                key_raw, value_raw = part.split(":", 1)
                key = decode_key(key_raw.strip())
                value = value_raw.strip().replace(",", "")
                if key == "char":
                    char_rank = value
                elif key == "global":
                    global_rank = value
            if not global_rank:
                continue
            fout.write(f"{term}\t{char_rank}\t{global_rank}\n")
            rows += 1
    print(f"Wrote {rows} rows to {target}")
    print(f"Compressed size: {target.stat().st_size} bytes")


if __name__ == "__main__":
    build_compact_asset(DEFAULT_SOURCE, DEFAULT_TARGET)
