"""Find repetitions in the MandarinMP Meta decks (props, actors, sets).

Usage: python -I meta_duplicates.py <copy of collection.anki2>

Read-only. Cards in a filtered deck (Custom Study Session) are counted in their home deck.
"""
import sys, sqlite3, re, collections, unicodedata
sys.stdout.reconfigure(encoding="utf-8")
db = sqlite3.connect(sys.argv[1])
db.create_collation('unicase', lambda a, b: (a.lower() > b.lower()) - (a.lower() < b.lower()))
DECK = {i: n.replace('\x1f', '::') for i, n in db.execute('select id, name from decks')}
LINK = re.compile(r'\[link:"(.{6,12}?)"\|((?:<[^>]*>|[^\]])*)\]')

def fold(s):
    s = unicodedata.normalize('NFD', s.lower().replace('’', "'"))
    return ''.join(c for c in s if unicodedata.category(c) != 'Mn')
def text(s):
    return re.sub(r'\s+', ' ', re.sub(r'<[^>]+>|&nbsp;', ' ', s)).strip()
def stem(p):  # prop name without parenthetical, article, plural
    p = fold(re.sub(r'\(.*?\)', '', p)).strip(' .')
    p = re.sub(r'^(a|an|the) ', '', p)
    return re.sub(r'(?<=[a-z]{3})(es|s)$', '', p)

notes = []
for nid, guid, flds in db.execute('select id, guid, flds from notes'):
    cards = db.execute('select case when odid != 0 then odid else did end, did, type from cards where nid=?', (nid,)).fetchall()
    home = sorted({DECK.get(c[0], '?') for c in cards})
    if not any(d.startswith('MandarinMP') for d in home): continue
    notes.append(dict(guid=guid, f=flds.split('\x1f'), deck=home[0], learned=any(c[2] > 0 for c in cards),
                      filtered=any(c[0] != c[1] for c in cards)))

props, actors, locs, tones, rooms = [], [], [], [], []
for n in notes:
    t = n['f'][0]
    m = re.search(r'Prop for:? \{\{c1::(.*?)\}\} is \{\{c2::(.*?)\}\}', t)
    if m:
        img = re.search(r'src="([^"]+)"', m.group(1))
        comp = img.group(1) if img else text(m.group(1)).split('::')[0].strip()
        props.append(dict(n, comp=comp, name=text(m.group(2)))); continue
    m = re.search(r'Actor for \{\{c1::(.*?)\}\} is \{\{c2::(.*?)\}\}', text(t))
    if m: actors.append(dict(n, code=m.group(1), name=m.group(2))); continue
    m = re.search(r'Location for \{\{c1::(.*?)\}\} is \{\{c2::(.*?)\}\}', text(t))
    if m: locs.append(dict(n, code=m.group(1), name=m.group(2))); continue
    m = re.search(r'Set for the \{\{c1::(.*?)\}\} tone is \{\{c2::(.*?)\}\}', text(t))
    if m: tones.append(dict(n, code=m.group(1), name=m.group(2))); continue
    if 'Meta-Sets' in n['deck'] and ' - ' in text(t): rooms.append(dict(n, name=text(t), back=text(n['f'][1])))

# which scene notes link to each Meta note
uses = collections.defaultdict(list)
for n in notes:
    if n['deck'].endswith(('Characters', 'Vocabulary')):
        for g, _ in LINK.findall('\x1f'.join(n['f'])): uses[g].append(text(n['f'][0]))
def who(p):
    st = ('learned' if p['learned'] else 'new') + (', in Custom Study' if p['filtered'] else '')
    u = uses.get(p['guid'], [])
    return f"{p['guid']}  [{st}]  linked from {len(u)} scene(s){': ' + ' '.join(u[:12]) if u else ''}"

print(f"props {len(props)}  actors {len(actors)}  locations {len(locs)}  tone cards {len(tones)}  room cards {len(rooms)}")
print(f"  ({sum(p['filtered'] for p in props)} prop notes have cards in a filtered deck)")

def groups(items, key, title, show):
    g = collections.defaultdict(list)
    for it in items: g[key(it)].append(it)
    hits = {k: v for k, v in g.items() if k and len(v) > 1}
    print(f"\n== {title}: {len(hits)} ==")
    for k, v in sorted(hits.items()):
        print(f"  {k!r}")
        for it in v: print('     ', show(it))

groups(props, lambda p: stem(p['name']), 'Props: same name, different notes',
       lambda p: f"{p['comp']} = {p['name']}  {who(p)}")
groups(props, lambda p: p['comp'], 'Props: same component on several notes',
       lambda p: f"{p['comp']} = {p['name']}  {who(p)}")

print('\n== Props: one name inside another (may be intended, e.g. sea horse → sea horse skewer) ==')
names = sorted({stem(p['name']) for p in props})
for a in names:
    for b in names:
        if a != b and len(a) >= 4 and re.search(r'\b' + re.escape(a) + r'\b', b): print(f'  {a!r} inside {b!r}')

print('\n== Props named like an actor or a location ==')
others = [(fold(a['name']), 'actor ' + a['code']) for a in actors] + [(fold(l['name']), 'location ' + l['code']) for l in locs]
for p in props:
    s = stem(p['name'])
    for k, what in others:
        if s == k or any(len(x) > 3 and re.search(r'\b' + re.escape(x) + r'\b', y) for x, y in ((s, k), (k, s))):
            print(f"  prop {p['comp']} '{p['name']}' ~ {what} '{k}'")

groups(actors, lambda a: unicodedata.normalize('NFC', a['code'].strip().lower()), 'Actors: same code', lambda a: f"{a['code']} {a['name']} {a['guid']}")
groups(actors, lambda a: fold(a['name']).replace(' ', ''), 'Actors: same name', lambda a: f"{a['code']} {a['name']} {a['guid']}")
groups(locs, lambda l: l['code'].strip(), 'Locations: same final', lambda l: f"{l['code']} {l['name']} {l['guid']}")
groups(locs, lambda l: fold(l['name']), 'Locations: same name', lambda l: f"{l['code']} {l['name']} {l['guid']}")
groups(tones, lambda t: t['code'], 'Tone cards: same tone', lambda t: f"{t['code']} {t['name']} {t['guid']}")
groups(rooms, lambda r: fold(r['name']).replace(' ', ''), 'Room cards: same Front', lambda r: f"{r['name']} {r['back']} {who(r)}")
