"""Preview the fork's implicit word→character links and the Browser action "Empty fields that match linked note".

Usage: python -I simulate_implicit_links.py <copy of collection.anki2>
Read-only. Mirrors LinkedNotes.kt (implicitLinkKey, normalizeForLinkedComparison) for the MandarinMP rule.
"""
import sys, sqlite3, re, collections
sys.stdout.reconfigure(encoding='utf-8')
db = sqlite3.connect(sys.argv[1])
db.create_collation('unicase', lambda a,b:(a.lower()>b.lower())-(a.lower()<b.lower()))
D = {i: n.replace('\x1f', '::') for i, n in db.execute('select id,name from decks')}
FN = collections.defaultdict(list)
for ntid, name in db.execute('select ntid,name from fields order by ntid,ord'): FN[ntid].append(name)
notes = {}
for nid, guid, mid, flds in db.execute('select id,guid,mid,flds from notes'):
    ds = {D[d] for (d,) in db.execute('select case when odid!=0 then odid else did end from cards where nid=?', (nid,))}
    notes[nid] = dict(id=nid, mid=mid, f=dict(zip(FN[mid], flds.split('\x1f'))), decks=ds)
def in_deck(n, d): return any(x == d or x.startswith(d + '::') for x in n['decks'])
def key_of(v):
    t = re.sub(r'<[^>]*>', '', v).replace('&nbsp;', ' ').strip()
    m = re.search(r'^(.)\.$', t); return m.group(1).strip() if m else None
def norm(v):
    v = re.sub(r'\{([^{}\r\n]+)\}\{\*?\[sound:([^\]\r\n]+)\]\*?\}', lambda m: f'[sound:{m.group(2)}]' if m.group(1) == m.group(2) else m.group(0), v)
    v = re.sub(r'<br\s*/?>|</?div>|&nbsp;', ' ', v, flags=re.I)
    v = re.sub(r'\(\*\d*\)', '', v)
    return re.sub(r'\s+', ' ', v).strip()
chars = [n for n in notes.values() if in_deck(n, 'MandarinMP::Characters')]
KEEP = {'Front', 'Linked Note'}
res = collections.Counter(); emptied_fields = collections.Counter(); differ_fields = collections.Counter()
pure, partial, nolink = [], [], []
for n in notes.values():
    if not in_deck(n, 'MandarinMP::Vocabulary') or 'Linked Note' not in n['f'] or n['f']['Linked Note'].strip(): continue
    k = key_of(n['f'].get('Front', ''))
    if not k: continue
    tgt = None
    for fld in ('Front', 'Traditional'):
        hits = [c for c in chars if c['f'].get(fld, '').strip().lower() == k.lower() and c['id'] != n['id']]
        if len(hits) == 1: tgt = hits[0]; break
        if len(hits) > 1: tgt = 'ambiguous'; break
    if not isinstance(tgt, dict): nolink.append((n['f']['Front'], tgt or 'no character note')); continue
    em, di = [], []
    for fld, own in n['f'].items():
        if fld in KEEP or not own.strip(): continue
        (em if norm(own) == norm(tgt['f'].get(fld, '')) else di).append(fld)
    emptied_fields.update(em); differ_fields.update(di)
    (pure if set(di) <= {'HSK Frequency'} else partial).append((n['f']['Front'].strip(), em, di))
print(f'implicitly linked: {len(pure)+len(partial)}   not linked: {len(nolink)} {nolink}')
print(f'pure stubs after cleanup (only Front + HSK Frequency left): {len(pure)}: ' + ' '.join(p[0] for p in pure))
print(f'still differing in other fields: {len(partial)}')
print('fields emptied:', dict(emptied_fields)); print('fields still differing:', dict(differ_fields))
for p in partial: print('  ', p[0], 'differs:', [d for d in p[2] if d != 'HSK Frequency'])

# Reason: is the character note's version simply the newer, linked form of the word note's plain one?
def plain(v): return re.sub(r'\s+', ' ', re.sub(r'<[^>]*>', ' ', re.sub(r'\[link:"(.{6,12}?)"\|((?:<[^>]*>|[^\]])*)\]', r'\2', v))).replace(' :', ':').strip()
older = []; other = []
cmap = {}
for c in chars: cmap.setdefault(c['f'].get('Front', '').strip(), c)
for front, em, di in partial:
    if 'Reason' not in di: continue
    w = next(n for n in notes.values() if n['f'].get('Front', '').strip() == front and in_deck(n, 'MandarinMP::Vocabulary'))
    c = cmap.get(front[0])
    if not c: continue
    wl, cl = '[link:' in w['f']['Reason'], '[link:' in c['f']['Reason']
    (older if cl and not wl else other).append(front)
print(f'\nReason differs: word has plain Reason, character has linked Reason: {len(older)}; other: {len(other)}: {" ".join(other)}')
