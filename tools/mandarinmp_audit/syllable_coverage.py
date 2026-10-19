import sys, json, collections, importlib.util
sys.stdout.reconfigure(encoding="utf-8")
# reuse decompose() from audit2 without running it
src = open(sys.argv[1], encoding='utf8').read()
start = src.index('INIT = ['); end = src.index('SYL = re.compile')
ns = {}; exec('import re, unicodedata\n' + src[start:end], ns)
decompose = ns['decompose']
a = json.load(open(sys.argv[2], encoding='utf8'))
ACT = a['actors']; LOC = a['locations']
cover = collections.defaultdict(list)
for rc in a['roomcards']:
    for f, t in rc['finals']: cover[(f, t)].append(rc['room'])

# Full standard Mandarin syllable inventory (Hanyu Pinyin, ~410 syllables + interjection-only ones)
SYLS = """a ai an ang ao e ei en eng er o ou
ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
fa fan fang fei fen feng fo fou fu
da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo
ta tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nun nuo nü nüe
la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lü lüe
ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo
ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
za zai zan zang zao ze zei zen zeng zi zong zou zu zuan zui zun zuo
ca cai can cang cao ce cen ceng ci cong cou cu cuan cui cun cuo
sa sai san sang sao se sen seng si song sou su suan sui sun suo
zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo
cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo
sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo
ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
wa wai wan wang wei wen weng wo wu""".split()
EXOTIC = ['ê', 'm', 'n', 'ng', 'hm', 'hng']   # interjections: 欸 ê, 呣 m, 嗯 n/ng, 噷 hm, 哼 hng
fails = []; combos = collections.defaultdict(list); act_use = collections.Counter(); fin_use = collections.Counter()
for s in SYLS:
    try:
        ac, fi = decompose(s)
    except Exception as e:
        fails.append(s); continue
    key = ac.replace('ü', 'v') if ac[0] in 'nl' else ac.replace('ü', 'u')
    if key not in ACT: fails.append(f'{s} -> actor {ac} missing'); continue
    if fi not in LOC: fails.append(f'{s} -> final {fi} missing'); continue
    combos[(key, fi)].append(s); act_use[key] += 1; fin_use[fi] += 1
print('syllables', len(SYLS), 'mapped', sum(len(v) for v in combos.values()), 'failures', fails)
dup = {k: v for k, v in combos.items() if len(v) > 1}
print('actor+set collisions (different syllables, same mnemonic):', dup)
print('unused actors:', [ACT[k]['raw'] for k in ACT if not act_use[k]])
print('syllables per actor:', {ACT[k]['raw']: n for k, n in act_use.most_common()})
print('syllables per location:', {LOC[k]: n for k, n in fin_use.most_common()})
for s in EXOTIC:
    try: print('exotic', s, decompose(s))
    except Exception: print('exotic', s, 'NOT COVERED')
gaps = [(LOC[f], t) for f in LOC for t in range(1, 6) if not cover.get((f, t))]
print('room gaps:', gaps)
# which real syllables fall into a gap (location+tone with no card): every syllable of that final
for f in LOC:
    for t in range(1, 6):
        if not cover.get((f, t)):
            print(f'  {LOC[f]} tone {t}: affects', ' '.join(s for (k, fi), ss in combos.items() if fi == f for s in ss)[:200])
