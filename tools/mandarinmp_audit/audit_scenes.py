import sys, json, re, sqlite3, unicodedata, collections
sys.stdout.reconfigure(encoding="utf-8")
db = sqlite3.connect(sys.argv[1])
OUT = sys.argv[2]

# ---------- helpers ----------
TONE = {}
for base, marks in {'a':'āáǎà','e':'ēéěè','i':'īíǐì','o':'ōóǒò','u':'ūúǔù','ü':'ǖǘǚǜ'}.items():
    for i, m in enumerate(marks): TONE[m] = (base, i + 1)
def strip_tone(s):
    t = 5; o = ''
    for ch in unicodedata.normalize('NFC', s):
        if ch in TONE: o += TONE[ch][0]; t = TONE[ch][1]
        else: o += ch
    return o, t
def fold(s):
    s = unicodedata.normalize('NFD', s.lower().replace('’', "'"))
    return ''.join(c for c in s if unicodedata.category(c) != 'Mn')
def rfold(s):  # room names: ignore apostrophes and repeated spaces ("kid's bedroom" = "kids bedroom")
    return re.sub(r'\s+', ' ', fold(s).replace("'", ''))
LINK = re.compile(r'\[link:"(.{6,12}?)"\|((?:<[^>]*>|[^\]])*)\]')
def clean(s):
    s = re.sub(r'<script.*?</script>', '', s, flags=re.S)
    s = LINK.sub(r'\2', s)
    s = re.sub(r'\[sound:[^\]]*\]', '', s); s = re.sub(r'\{[^{}]*\.mp3\}|\{\*+\}', '', s)
    s = re.sub(r'<br\s*/?>|</div>|<div>', ' | ', s); s = re.sub(r'<[^>]+>', '', s)
    s = s.replace('&nbsp;', ' ').replace('&amp;', '&')
    s = re.sub(r'(\s*\|\s*)+', ' | ', s)
    return unicodedata.normalize('NFC', s.strip(' |*'))

# ---------- pinyin -> (actor code, final, tone) ----------
INIT = ['zh','ch','sh','b','p','m','f','d','t','n','l','g','k','h','z','c','s','r','j','q','x']
FIN = {'':'null','a':'a','o':'o','e':'e','ai':'ai','ei':'ei','ao':'ao','ou':'ou','an':'an','en':'en','ang':'ang','eng':'eng','ong':'ong'}
I_REST = {'':'null','n':'en','ng':'eng','u':'ou','ou':'ou','ong':'ong','a':'a','ao':'ao','an':'an','ang':'ang','e':'e','o':'o'}
U_REST = {'':'null','a':'a','o':'o','ai':'ai','an':'an','ang':'ang','i':'ei','n':'en','e':'e'}
UE_REST = {'u':'null','ue':'e','uan':'an','un':'en','ü':'null','üe':'e'}
def decompose(s):
    s = s.replace('v', 'ü')
    if s == 'er': return 'null', 'null'
    if s.startswith('y'):
        r = s[1:]
        if r.startswith('u'): return 'yu', UE_REST[r]
        return 'yi', I_REST[r[1:] if r.startswith('i') else r]
    if s.startswith('w'):
        r = s[1:]; return 'wu', FIN[r[1:] if r.startswith('u') else r]
    ini = next((i for i in INIT if s.startswith(i)), '')
    r = s[len(ini):]
    if ini in ('j','q','x'):
        if r.startswith('i'): return ini+'i', I_REST[r[1:]]
        return ini+'u', UE_REST[r.replace('ü','u')]
    if ini in ('n','l') and r.startswith('ü'): return ini+'ü', UE_REST[r]
    if ini == '': return 'null', FIN[r]
    if r == 'i' and ini in ('zh','ch','sh','r','z','c','s'): return ini, 'null'
    if r.startswith('i'): return ini+'i', I_REST[r[1:]]
    if r.startswith('u') and not r.startswith('ou'): return ini+'u', U_REST[r[1:]]
    return ini, FIN[r]
SYL = re.compile(r"[a-zA-ZāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüÜ]+")
def readings(p):
    toks = []
    for m in SYL.finditer(p):
        w = m.group(0); b, t = strip_tone(w.lower())
        try: toks.append((w, t) + decompose(b))
        except Exception: pass
    marked = [x for x in toks if x[1] != 5]
    if toks and toks[0][1] == 5: marked = [toks[0]] + marked   # neutral-tone primary (ma, le, de)
    return marked or toks[:1]
def norm_code(a):
    a = unicodedata.normalize('NFC', a)
    for ch in 'üǖǘǚǜ': a = a.replace(ch, 'v')
    a = fold(a).strip().strip('-').strip()
    if a[:1] in ('j', 'q', 'x', 'y'): a = a.replace('v', 'u')
    if a in ('0', 'null', 'zero'): return 'null'
    return a
def norm_exp(a):          # expected codes in folded form (nü -> nu? keep distinct)
    return a.replace('ü', 'v') if a[0] in 'nl' else a
def norm_fin(f):
    b, t = strip_tone(f.strip().lower())
    b = re.sub(r'[-() ]', '', b)
    if 'null' in b or 'none' in b or b in ('0', 'i', 'er', 'ir'): b = 'null'
    return {'ing':'eng','in':'en','iu':'ou','ui':'ei','un':'en','re':'e'}.get(b, b), t

# ---------- load notes ----------
names = {r[0]: r[1] for r in db.execute("select id,name from notetypes")}
decks = {r[0]: r[1].replace('\x1f', '::') for r in db.execute("select id,name from decks")}
fieldnames = collections.defaultdict(dict)
for ntid, o, n in db.execute("select ntid,ord,name from fields"): fieldnames[ntid][o] = n
notes = {}; byguid = {}
for nid, guid, mid, flds in db.execute("select id,guid,mid,flds from notes"):
    # cards in a filtered deck (Custom Study Session) keep their home deck in odid
    ds = sorted({decks[d] for (d,) in db.execute("select case when odid != 0 then odid else did end from cards where nid=?", (nid,))})
    f = {fieldnames[mid][i]: v for i, v in enumerate(flds.split('\x1f'))}
    n = dict(id=nid, guid=guid, type=names[mid], deck=ds[0] if ds else '', f=f)
    notes[nid] = n; byguid[guid] = n
    if len(ds) > 1 and ds[0].startswith('MandarinMP'):
        print(f"split deck: {clean(f.get('Front', ''))} has cards in {ds}")

# ---------- linked notes (fork feature, libanki LinkedNotes.kt) ----------
# "Linked Note" holds "{GUID} label" or "{guidhex:<utf-8 hex>} label". The reviewer shows the note
# merged: blank fields come from the linked note, Front always does. Audit what the reviewer shows,
# but keep the note's own Front as its label.
for n in notes.values():
    raw = n['f'].get('Linked Note', '').strip()
    if not raw: continue
    m = re.search(r'\{([^{}\r\n]+)\}', raw)
    tok = (m.group(1) if m else raw).strip()
    if tok.startswith('guidhex:'):
        try: tok = bytes.fromhex(tok[8:]).decode('utf-8')
        except ValueError: pass
    tgt = byguid.get(tok)
    if not tgt or tgt is n:
        print(f"broken linked note: {clean(n['f'].get('Front', ''))} -> {raw!r}"); continue
    n['linked'] = clean(tgt['f'].get('Front', ''))
    for k, v in tgt['f'].items():
        if k not in ('Front', 'Linked Note') and not n['f'].get(k, '').strip(): n['f'][k] = v
    n['f']['Linked Note'] = ''

# ---------- actor map from Meta-Actors ----------
ACTOR = {}          # code -> dict(name, guid)
meta_issues = []
for n in notes.values():
    if n['type'] != 'Cloze' or 'Actor for' not in n['f'].get('Text', ''): continue
    m = re.search(r'Actor for \{\{c1::(.*?)\}\} is \{\{c2::(.*?)\}\}', clean(n['f']['Text']))
    if not m: continue
    raw, name = m.group(1), m.group(2)
    code = raw.strip().strip('-').lower()
    code = unicodedata.normalize('NFC', code)
    code = {'null':'null','jü':'ju','qü':'qu','xü':'xu','nü':'nv','lü':'lv'}.get(code, code)
    if code in ACTOR: meta_issues.append(f"Meta-Actors: duplicate actor for {raw}")
    ACTOR[code] = dict(name=name, raw=raw, guid=n['guid'], extra=clean(n['f'].get('Back Extra', n['f'].get('Extra', ''))))
ACTOR_BY_GUID = {v['guid']: k for k, v in ACTOR.items()}

# ---------- set map from Meta-Sets ----------
LOCATION = {}       # final -> location name
TONEROOM = {}       # tone -> generic description
ROOMCARDS = []      # dicts: guid, loc, room, finals[(fin,tone)]
for n in notes.values():
    if not n['deck'].startswith('MandarinMP::Meta-Sets') and not any('Meta-Sets' in d for d in [n['deck']]): continue
    if n['type'] == 'Cloze':
        t = clean(n['f']['Text'])
        m = re.search(r'Location for \{\{c1::(.*?)\}\} is \{\{c2::(.*?)\}\}', t)
        if m: LOCATION[norm_fin(m.group(1))[0]] = m.group(2); continue
        m = re.search(r'Set for the \{\{c1::(\d)\w*\}\} tone is \{\{c2::(.*?)\}\}', t)
        if m: TONEROOM[int(m.group(1))] = m.group(2)
    else:
        front, back = clean(n['f']['Front']), clean(n['f']['Back'])
        if ' - ' not in front: continue
        loc, room = [x.strip() for x in front.split(' - ', 1)]
        fins = []
        for part in re.split(r',', back):
            part = part.strip()
            if part.startswith('-'): fins.append(norm_fin(part))
        # one note per room; other names for the same room go in the same Front, separated by '/'
        names = [x.strip() for x in room.split('/') if x.strip()]
        ROOMCARDS.append(dict(guid=n['guid'], loc=loc, room=room, names=names, front=front, finals=fins))
LOC_OF_FINAL = LOCATION
# location aliases: distinctive part of the official name + every room-card spelling
STOP = {'home', 'house', "'s", 'pilates', 'my'}
def loc_patterns(fin):
    names_ = {LOCATION[fin]} | {rc['loc'] for rc in ROOMCARDS if any(f == fin for f, _ in rc['finals'])}
    pats = set()
    for nm in names_:
        words = [w for w in re.split(r"\s+", fold(nm).replace("'s", ' ').replace("'", ' ')) if w and w not in STOP]
        if words:
            core = ' '.join(words)
            pats.add(r'\b' + re.escape(core))
    return pats, names_
LOCPATS = {f: loc_patterns(f) for f in LOCATION}
# nicknames seen in scenes (reported separately as "alias")
LOC_ALIAS = {'ou': [r'southwest', r'sudoeste', '西南'], 'ao': ['圣保罗', r'\bsp\b'], 'an': ['北翼'], 'ei': ['埃托尔'],
             'null': ['童年'], 'a': [r'anexo i\b'], 'ang': [r'anexo iv\b']}

# generic tone classifier (from your "Set for the Nth tone" cards + room names used on room cards)
GENERIC = {1: ['outside the entrance', 'outside', 'entrance', 'hallway', 'corridor', 'door'],
           2: ['kitchen', 'dining', 'refectory', 'cafeteria', 'restaurant', 'snack bar', 'lanchonete', 'lunch room', 'inside the entrance'],
           3: ['living room', 'lobby', 'patio', 'bedroom', 'salao branco', 'middle of the studio', 'studio'],
           4: ['bathroom', 'backyard', 'back yard', 'office', 'toilet', 'shower'],
           5: ['roof']}
def generic_tone(txt):
    t = fold(txt); best = None
    for tone, kws in GENERIC.items():
        for k in kws:
            if re.search(r'\b' + re.escape(k) + r'\b', t) and (best is None or len(k) > len(best[1])): best = (tone, k)
    return best

# ---------- meta deck consistency ----------
for rc in ROOMCARDS:
    locs = {LOCATION.get(f) for f, _ in rc['finals']}
    if len(locs) != 1: meta_issues.append(f"Meta-Sets: '{rc['front']}' mixes finals of different locations {rc['finals']}")
    tones = {t for _, t in rc['finals']}
    g = generic_tone(rc['names'][0])
    if g and g[0] not in tones: meta_issues.append(f"Meta-Sets: '{rc['front']}' is tone {sorted(tones)} but '{g[1]}' is a tone-{g[0]} room by your general rule")
    if not g: meta_issues.append(f"Meta-Sets: '{rc['front']}' (tone {sorted(tones)}) — room type not covered by your general tone rule")
fronts = collections.Counter(fold(rc['front']) for rc in ROOMCARDS)
for k, c in fronts.items():
    if c > 1: meta_issues.append(f"Meta-Sets: room card '{k}' exists {c} times")
seen_names = collections.defaultdict(list)
for rc in ROOMCARDS:
    for nm in rc['names']: seen_names[(fold(rc['loc']), fold(nm))].append(rc['front'])
for (l, nm), fs in seen_names.items():
    if len(fs) > 1: meta_issues.append(f"Meta-Sets: room name '{nm}' at {l} is on {len(fs)} notes {fs}")
coverage = collections.defaultdict(list)
for rc in ROOMCARDS:
    for f, t in rc['finals']:
        if rc['room'] not in coverage[(f, t)]: coverage[(f, t)].append(rc['room'])
missing = [(f, t) for f in LOCATION for t in range(1, 6) if not coverage.get((f, t))]

# ---------- check characters ----------
ORD = {'1st':1,'2nd':2,'3rd':3,'3rf':3,'4th':4,'5th':5}
def actor_pattern(code):
    nm = fold(re.sub(r'(?<=[a-zà-ÿ])(?=[A-Z])', ' ', ACTOR[code]['name'])).replace('-', ' ')
    pats = [r'\b' + re.escape(nm) + r'\b', r'\b' + re.escape(nm.replace(' ', '')) + r'\b']
    others = {c: fold(re.sub(r'(?<=[a-zà-ÿ])(?=[A-Z])', ' ', v['name'])).replace('-', ' ') for c, v in ACTOR.items() if c != code}
    for w in nm.split():
        if len(w) >= 3 and not any(re.search(r'\b' + re.escape(w) + r'\b', o) for o in others.values()):
            pats.append(r'\b' + re.escape(w) + r'\b')
    longer = [o for o in others.values() if re.search(r'\b' + re.escape(nm) + r'\b', o)]
    return pats, longer
APAT = {c: actor_pattern(c) for c in ACTOR}
def actors_in(t):
    found = set()
    for c, (pats, longer) in APAT.items():
        tt = t
        for l in longer: tt = re.sub(r'\b' + re.escape(l) + r'\b', ' ', tt)
        if any(re.search(p, tt) for p in pats): found.add(c)
    return found

rows = []
for n in notes.values():
    if n['deck'] != 'MandarinMP::Characters': continue
    f = n['f']; front = clean(f['Front']); pin = clean(f['Pinyin'])
    rraw = f['Reason']; rea = clean(rraw); scene = clean(f['Scene'])
    rds = readings(pin)
    row = dict(front=front, pin=pin, reason=rea, scene=scene, R=[], S=[], N=[])
    rows.append(row)
    if not rds: row['R'].append('no pinyin'); continue
    w, t, ea, ef = rds[0]
    ea_n = norm_exp(ea).replace('ü', 'u')
    row.update(syl=w, tone=t, exp_actor=ea, exp_fin=ef, exp_name=ACTOR[ea_n]['name'], exp_loc=LOCATION[ef])
    alt_codes = {norm_exp(x[2]).replace('ü','u') for x in rds[1:]}
    # --- Reason: actor ---
    seg = re.split(r'<br\s*/?>|</div>', rraw)
    actor_seg = next((s for s in seg if re.search(r'Act\w*\s*:', s)), '')
    set_seg = ' '.join(s for s in seg if re.search(r'(Set|Location)\s*:', s))
    m = LINK.search(actor_seg)
    if m:
        tgt = ACTOR_BY_GUID.get(m.group(1))
        lab = norm_code(clean(m.group(2)))
        if tgt is None: row['R'].append(f"actor link points to a non-actor note ({clean(byguid[m.group(1)]['f'].get('Text', byguid[m.group(1)]['f'].get('Front','')))[:40] if m.group(1) in byguid else 'missing note'})")
        else:
            if tgt != ea_n: row['R'].append(f"actor link → {ACTOR[tgt]['raw']} {ACTOR[tgt]['name']}, expected {ea}- {ACTOR[ea_n]['name']}" + (' (other reading)' if tgt in alt_codes else ''))
            if lab and lab != tgt: row['N'].append(f"actor link label '{clean(m.group(2))}' but target is {ACTOR[tgt]['raw']}")
    ac = clean(actor_seg)
    mm = re.match(r'\s*Act\w*\s*:\s*(-?[A-Za-zÀ-ÿü0]+-?)\s*(?:\(?([^)|]*)\)?)?', ac)
    code = norm_code(mm.group(1)) if mm else None
    if not mm or code not in ACTOR and code not in ('y', 'w'):
        if not m: row['R'].append(f"actor code '{mm.group(1)}' is not in Meta-Actors, expected {ea}- {ACTOR[ea_n]['name']}" if mm else 'no actor code in Reason')
    else:
        canon = {'y':'yi','w':'wu'}.get(code, code)
        if code in ('y','w'): row['N'].append(f"actor written {mm.group(1)} (= {canon}-)")
        if canon != ea_n and not m: row['R'].append(f"actor {mm.group(1)} ({ACTOR[canon]['name']}), expected {ea}- {ACTOR[ea_n]['name']}" + (' (other reading)' if canon in alt_codes else ''))
        nm = (mm.group(2) or '').strip()
        if nm and fold(nm).replace(' ', '') != fold(ACTOR[canon]['name']).replace(' ', '') and nm.lower() not in ('', 'reason'):
            row['N'].append(f"actor name written '{nm}' but {ACTOR[canon]['raw']} is '{ACTOR[canon]['name']}'")
        if not nm and not m: pass
    # --- Reason: set ---
    sm = LINK.search(set_seg)
    rcard = next((rc for rc in ROOMCARDS if sm and rc['guid'] == sm.group(1)), None)
    if sm and not rcard:
        row['R'].append('set link points to a deleted note' if sm.group(1) not in byguid else 'set link points to a non-room note')
    if rcard:
        if (ef, t) not in rcard['finals']:
            ok_alt = any((x[3], x[1]) in rcard['finals'] for x in rds[1:])
            row['R'].append(f"set link → '{rcard['front']}' {rcard['finals']}, expected -{ef} tone {t} ({LOCATION[ef]})" + (' (other reading)' if ok_alt else ''))
    st = clean(set_seg)
    fin_codes = re.findall(r'(?:Set|Location)\s*:\s*(-\s?[^\s(|]+(?:\s*Null)?)', st)
    tone_txt = re.search(r'(\d\w\w)\s*tone', st)
    rtone = ORD.get(tone_txt.group(1)) if tone_txt else None
    if fin_codes:
        fc, ft = norm_fin(fin_codes[0])
        if not rcard and fc != ef: row['R'].append(f"set {fin_codes[0]} ({LOCATION.get(fc,'?')}), expected -{ef} ({LOCATION[ef]})" + (' (other reading)' if any(x[3]==fc for x in rds[1:]) else ''))
        if ft != 5 and rtone and ft != rtone: row['R'].append(f"set mark says tone {ft} but text says '{tone_txt.group(0)}'")
        rtone = rtone or (ft if ft != 5 or fc == ef and t == 5 else None)
    elif not rcard: row['R'].append('no set code in Reason')
    if rcard: rtone = rtone or None
    roomtxt = re.search(r'\(([^)]*-[^)]*)\)', st)
    if roomtxt and not rcard:
        rc2 = next((rc for rc in ROOMCARDS if fold(rc['front']) == fold(roomtxt.group(1))), None)
        if rc2 and (ef, t) not in rc2['finals']: row['R'].append(f"set text names '{rc2['front']}' {rc2['finals']}, expected -{ef} tone {t}")
        if rc2: rtone = rtone or rc2['finals'][0][1]
    if not rcard:
        if rtone is None: row['N'].append('Reason has no tone')
        elif rtone != t: row['R'].append(f"Reason tone {rtone}, pinyin {w} is tone {t}" + (' (other reading)' if any(x[1]==rtone for x in rds[1:]) else ''))
    elif tone_txt and ORD.get(tone_txt.group(1)) != t:
        row['R'].append(f"Reason text '{tone_txt.group(0)}', pinyin {w} is tone {t}")
    # --- Scene ---
    if not scene: row['S'].append('no scene'); continue
    parts = re.split(r'(?:^|\|)\s*\d\.\s', scene) if len(rds) > 1 and re.search(r'(^|\|)\s*1\.', scene) else [scene]
    parts = [p for p in parts if p.strip()]
    for i, part in enumerate(parts):
        rd = rds[i] if i < len(rds) else rds[0]
        pw, pt, pa, pf = rd; pa_n = norm_exp(pa).replace('ü', 'u')
        tag = f"[{pw}] " if len(parts) > 1 else ''
        txt = fold(part)
        acts = actors_in(txt)
        if pa_n not in acts:
            row['S'].append(tag + f"actor {ACTOR[pa_n]['name']} ({pa}-) not named" + (f"; names {', '.join(ACTOR[a]['name'] + ' (' + ACTOR[a]['raw'] + ')' for a in sorted(acts))}" if acts else ''))
        pats, lnames = LOCPATS[pf]
        hit = any(re.search(p, txt) for p in pats)
        alias = not hit and any(re.search(p, part.lower() if not p.isascii() else txt) for p in LOC_ALIAS.get(pf, []))
        other = [LOCATION[f2] for f2, (ps, _) in LOCPATS.items() if f2 != pf and any(re.search(p, txt) for p in ps)]
        if alias: row['N'].append(tag + f"location named by nickname for {LOCATION[pf]}")
        elif not hit: row['S'].append(tag + f"location {LOCATION[pf]} (-{pf}) not named" + (f"; names {', '.join(other)}" if other else ''))
        # room: prefer room-card names for the expected location
        cards = [rc for rc in ROOMCARDS if any(f2 == pf for f2, _ in rc['finals'])]
        rtxt = rfold(part)
        found = [(len(nm), nm, rc) for rc in cards for nm in rc['names'] if re.search(r'\b' + re.escape(rfold(nm)) + r'\b', rtxt)]
        # a story may mention another room in passing (D5); the right-tone room wins if it is there
        right = [x for x in found if (pf, pt) in x[2]['finals']]
        if found:
            _, nm, best = max(right or found, key=lambda x: x[0])
            if (pf, pt) not in best['finals']:
                row['S'].append(tag + f"room '{nm}' is {[x[1] for x in best['finals']]} on your card '{best['front']}', pinyin tone {pt}")
        else:
            g = generic_tone(part)
            if not g: row['S'].append(tag + f"no room named (tone {pt})")
            else:
                exp_rooms = coverage.get((pf, pt), [])
                if g[0] != pt: row['S'].append(tag + f"room '{g[1]}' is a tone-{g[0]} room, pinyin tone {pt} (your cards for {LOCATION[pf]} tone {pt}: {', '.join(exp_rooms) or 'none'})")
                elif len([r for r in exp_rooms if g[1] in fold(r)]) > 1: row['N'].append(tag + f"room '{g[1]}' is ambiguous at {LOCATION[pf]}: say which one ({', '.join(r for r in exp_rooms if g[1] in fold(r))})")
                else: row['N'].append(tag + f"room '{g[1]}' has no card at {LOCATION[pf]} (cards for tone {pt}: {', '.join(exp_rooms) or 'none'})")

json.dump(dict(actors=ACTOR, locations=LOCATION, toneroom=TONEROOM, roomcards=ROOMCARDS, missing=missing,
               meta_issues=meta_issues, rows=rows), open(OUT, 'w', encoding='utf8'), ensure_ascii=False, indent=1)
print('actors', len(ACTOR), 'locations', len(LOCATION), 'room cards', len(ROOMCARDS), 'missing combos', len(missing))
print('rows', len(rows), 'R', sum(bool(r['R']) for r in rows), 'S', sum(bool(r['S']) for r in rows), 'N', sum(bool(r['N']) for r in rows))
