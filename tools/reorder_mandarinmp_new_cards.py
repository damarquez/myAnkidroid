"""Reorder MandarinMP new cards by frequency rank, prerequisites first.

Order: (1) new mnemonics already used by reviewed cards, (2) new Phrases/Listening,
(3) Characters + Vocabulary by "HSK Frequency" rank, each preceded by its still-new
prerequisites (Reason links / props / sets; a word's characters), (4) unreferenced rest.
With --apply it also sets gather = ascending position and sort = order gathered on the
"MandarinMP" and "MandarinMP Meta" presets. No schema change, so it syncs normally.

Run with Anki desktop's own Python while Anki is CLOSED (sync first, back up first):
  "$LOCALAPPDATA/AnkiProgramFiles/.venv/Scripts/python.exe" -I tools/reorder_mandarinmp_new_cards.py \
      "$APPDATA/Anki2/User 1/collection.anki2" [--apply]
Without --apply it's a dry run that prints the planned order."""
import sys, re; sys.stdout.reconfigure(encoding="utf-8")
from anki.collection import Collection
from anki import deck_config_pb2 as dc

path, apply = sys.argv[1], "--apply" in sys.argv
col = Collection(path)
scm_before = col.db.scalar("select scm from col")
decks = {d.id: d.name for d in col.decks.all_names_and_ids() if d.name.startswith("MandarinMP")}
dids = ",".join(map(str, decks))
strip = lambda s: re.sub(r"<[^>]+>", "", s).replace("&nbsp;", " ").strip()

notes = {}
for nid, guid, mid, flds in col.db.all(f"select id, guid, mid, flds from notes where id in (select nid from cards where did in ({dids}))"):
    nt = col.models.get(mid)
    notes[nid] = dict(guid=guid, nt=nt["name"], f=dict(zip([x["name"] for x in nt["flds"]], flds.split("\x1f"))), cards=[])
for cid, nid, did, queue, due in col.db.all(f"select id, nid, did, queue, due from cards where did in ({dids})"):
    notes[nid]["cards"].append((cid, did, queue, due))
for n in notes.values():
    n["deck"] = decks[n["cards"][0][1]].split("::")[-1]
    n["new"] = any(q == 0 for _, _, q, _ in n["cards"])
    n["pos"] = min((d for _, _, q, d in n["cards"] if q == 0), default=0)

guid2nid = {n["guid"]: k for k, n in notes.items()}
MAIN = ("Characters", "Vocabulary")
def rank(n):
    m = re.match(r"\s*(\d+)", strip(n["f"].get("HSK Frequency", "")))
    return int(m.group(1)) if m else 10**9

# lookup tables for plain-text references in Reason
prop_by_char, set_by_final, char_by_front = {}, {}, {}
for nid, n in notes.items():
    if n["deck"] == "Meta-Props":
        m = re.search(r"\{\{c1::(.*?)\}\}", n["f"].get("Text", ""))
        if m and strip(m.group(1)): prop_by_char[strip(m.group(1))] = nid
    elif n["deck"] == "Meta-Sets" and n["nt"] == "Basic":
        b = strip(n["f"].get("Back", ""))
        if b: set_by_final[b] = nid
    elif n["deck"] == "Characters":
        char_by_front[strip(n["f"]["Front"]).rstrip(".")] = nid

def deps(nid):
    n = notes[nid]; reason = n["f"].get("Reason", "")
    out = []
    out += [guid2nid.get((m.group(1) or m.group(2)).strip()) for m in re.finditer(r'\[link:(?:"([^"]+)"|([^|\]]+))\|', reason)]
    plain = strip(re.sub(r"\[link:[^\]]*\]", " ", reason))
    out += [v for k, v in prop_by_char.items() if k in plain]
    out += [v for k, v in set_by_final.items() if re.search(r"(?<![\w-])" + re.escape(k) + r"(?!\w)", plain)]
    if n["deck"] == "Vocabulary":
        out += [char_by_front.get(ch) for ch in strip(n["f"]["Front"])]
    return [d for d in dict.fromkeys(out) if d and d != nid and notes[d]["new"]]

order, placed, why = [], set(), {}
def place(nid, reason):
    if nid in placed: return
    placed.add(nid)
    for d in deps(nid): place(d, f"needed by {strip(notes[nid]['f'].get('Front', ''))}")
    order.append(nid); why[nid] = reason

# 1. mnemonics already needed by cards in review
for nid in sorted((k for k, n in notes.items() if n["deck"] in MAIN and not n["new"]), key=lambda k: rank(notes[k])):
    for d in deps(nid): place(d, f"gap: used by reviewed {strip(notes[nid]['f']['Front'])}")
# 2. the few Phrases/Listening notes
for nid in sorted((k for k, n in notes.items() if n["new"] and n["deck"] in ("Phrases", "Listening")), key=lambda k: notes[k]["pos"]):
    place(nid, notes[nid]["deck"])
# 3. characters + words by frequency rank
for nid in sorted((k for k, n in notes.items() if n["new"] and n["deck"] in MAIN), key=lambda k: (rank(notes[k]), k)):
    place(nid, f"rank {rank(notes[nid])}")
# 4. everything else (unreferenced mnemonics), keeping current order
for nid in sorted((k for k, n in notes.items() if n["new"]), key=lambda k: notes[k]["pos"]):
    place(nid, "unreferenced")

label = lambda n: strip(n["f"].get("Front") or n["f"].get("Text") or "")[:40]
for i, nid in enumerate(order, 1):
    n = notes[nid]
    if i <= 45 or i > len(order) - 5: print(f"{i:4} {n['deck']:11} {label(n):42} {why[nid]}")
    elif i == 46: print("   ...")
print("notes:", len(order), "cards:", sum(1 for nid in order for c in notes[nid]["cards"] if c[2] == 0))

if apply:
    cards = []
    for pos, nid in enumerate(order, 1):
        for cid, _, q, _ in notes[nid]["cards"]:
            if q == 0:
                c = col.get_card(cid); c.due = pos; cards.append(c)
    col.update_cards(cards)
    for name in ("MandarinMP", "MandarinMP Meta"):
        conf = next(c for c in col.decks.all_config() if c["name"] == name)
        conf["newGatherPriority"] = dc.DeckConfig.Config.NEW_CARD_GATHER_PRIORITY_LOWEST_POSITION
        conf["newSortOrder"] = dc.DeckConfig.Config.NEW_CARD_SORT_ORDER_NO_SORT
        col.decks.update_config(conf)
    assert col.db.scalar("select scm from col") == scm_before, "schema changed!"
    print("applied", len(cards), "cards; presets updated; schema unchanged")
col.close(downgrade=False)
