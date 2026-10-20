# MandarinMP: actor / set rules and audit findings

Living reference for the **Mandarin MemPalace Scene** notes in `MandarinMP::Characters`.
It records the rules that turn a character's pinyin into an **actor** (from the start of the syllable) and a **set** (location from the final + room from the tone), the decisions that are still open, and every note that broke the rules on the first audit.
Props are chosen by hand and are out of scope.

Tick the boxes as items get fixed, and add a line to the [change log](#change-log) when something changes.
To check the current state again, re-run the audit ([how](#re-running-the-audit)).

| | |
|---|---|
| First audit | 2026-10-17, on a read-only copy of the desktop collection (synced 2026-10-16) |
| Notes checked | 503 notes in `MandarinMP::Characters`, 55 Meta-Actors, 13 Meta-Sets locations, 95 room cards |
| Database writes | None. The fixes are done by hand in Anki. |
| Scripts | `tools/mandarinmp_audit/audit_scenes.py`, `tools/mandarinmp_audit/syllable_coverage.py` |

---

## 1. The rules

### 1.1 Actor = initial + medial

The actor encodes the initial consonant plus a medial vowel **i**, **u** or **ü**, if the syllable has one. Everything after the medial goes to the set.

| Syllable shape | Actor | Example |
|---|---|---|
| Initial with no medial | initial (`b-`, `zh-` …) | 八 bā → b- Bruce Willis |
| Initial + i… | initial + i (`bi-`, `ji-` …) | 千 qiān → qi- Queen Latifah |
| Initial + u… | initial + u (`gu-`, `zhu-` …) | 顾 gù → gu- Gumball |
| j/q/x + u… (this u is really ü) | `jü-`, `qü-`, `xü-` | 决 jué → jü- Jax |
| n/l + ü… | `nü-`, `lü-` (not nu-/lu-) | 旅 lǚ → lü- Liu Kang |
| zhi chi shi ri zi ci si | initial only, set **Null** | 值 zhí → zh- George Clooney + Null |
| No initial (a, ai, ou, er …) | **Null-** Jackie Chan | 爱 ài → Null- + -ai |
| y + i… (yi, ya, yao, yan, yang, ye, yin, ying, yo, **you**, **yong**) | `yi-` Ivete Sangalo | 有 yǒu → yi- + -ou |
| y + u… (yu, yue, yuan, yun) | `yu-` Cyrax | 雨 yǔ → yu- + Null |
| w… (wu, wa, wo, wai, wei, wan, wen, wang, weng) | `wu-` Woody Woodpecker | 外 wài → wu- + -ai |
| bo po mo fo | plain b/p/m/f, set `-o` (spelling wins over the "buo" sound) | 波 bō → b- + -o |

Write the actor code in full in the Reason field: `yi-` and `wu-`, not `y-` or `w-`.

### 1.2 Set = location (from the final) + room (from the tone)

**What's left of the final** after the medial is removed always becomes one of 13 finals:

| Written final(s) | Set final | Location |
|---|---|---|
| *(nothing)*: zhi/chi/shi/ri/zi/ci/si, er, bare i/u/ü (bi, gu, ju, nü …) | Null | Childhood home |
| a, ia, ua | -a | Anexo 1 |
| o, uo, io (yo) | -o | CONOF |
| e, ie, üe | -e | Eupídio |
| ai, uai | -ai | Dad's house |
| ei, ui (=uei) | -ei | Heitor's house |
| ao, iao | -ao | São Paulo home |
| ou, iu (=iou) | -ou | SW home |
| an, ian, uan, üan | -an | Asa Norte home |
| en, in, un (=uen), ün | -en | Lene's home |
| ang, iang, uang | -ang | Anexo 4 |
| eng, ing, ueng (weng) | -eng | In-laws' house |
| ong, iong (yong) | -ong | Along |

Points to remember: `-ian` stays `-an` even though it sounds like "ien". `iong`/`yong` take the **i**-actor + `-ong` (兄 xiōng → xi- Sharon Stone at Along; 用 yòng → yi-), not the ü-actor.

**The tone picks the room.** These are the definitions on your Meta-Sets tone cards:

| Tone | Room type |
|---|---|
| 1 | outside the entrance, hallway, corridor |
| 2 | kitchen, dining room, refectory (or inside the entrance, if no other place related to food exists) |
| 3 | living room, lobby, patio or similar place; bedroom |
| 4 | bathroom, backyard, office |
| 5 (neutral) | roof |

**Several rooms per tone is intended.** A location can have more than one room for a tone (hallway *and* outside the entrance; kitchen *and* dining room), so that scenes don't all happen in the same spot. Three conditions keep this unambiguous:
1. A room type means the same tone in every location. Hallway is always 1, lobby always 3, and so on. This was true for all 101 cards on 2026-10-18.
2. Every room a scene uses has its own room card. If a scene needs a new room, add the card. Don't rewrite the scene.
3. **One note per room.** Different names for the *same* room go on that room's single note, separated by `/` in the Front: `CONOF - Refectory / lunch room`. A *different* room gets its own note (CONOF Kitchen and CONOF Refectory are two rooms, so two notes). The audit reads every name after the `/` and accepts any of them in a scene. It also warns when the same name sits on two notes of one location.

### 1.3 Actor map (Meta-Actors, 55 notes)

| Code | Actor | Code | Actor | Code | Actor |
|---|---|---|---|---|---|
| Null- | Jackie Chan | b- | Bruce Willis | bi- | Britney Spears |
| bu- | Butters | p- | Prince | pi- | Paula Abdul |
| pu- | Patrick | m- | Michael Jackson | mi- | Michelle Pfeiffer |
| mu- | Muno | f- | Phil Collins | fu- | Foofa |
| d- | Dan Aykroyd | di- | Demi Moore | du- | Darwin |
| t- | Tom Hanks | ti- | Taylor Swift | tu- | Tom |
| n- | Nicholas Cage | ni- | Nicole Kidman | nu- | Nemo |
| nü- | Noob Saibot | l- | Lô Borges | li- | Lady Gaga |
| lu- | Lotso | lü- | Liu Kang | g- | Gary Oldman |
| gu- | Gumball | k- | Keanu Reeves | ku- | Kung-fu Panda |
| h- | Harrison Ford | hu- | Human Torch | ji- | Gisele Bündchen |
| jü- | Jax | qi- | Queen Latifah | qü- | Kung Lao |
| xi- | Sharon Stone | xü- | Sheeva | zh- | George Clooney |
| zhu- | Judy Hops | ch- | Chaplin | chu- | Chupacabra |
| sh- | Sean Connery | shu- | Chewbacca | r- | Richard Dawkins |
| ru- | Roo | z- | Zé Ramalho | zu- | Zazu |
| c- | Charles Bronson | cu- | Cursor | s- | Stallone |
| su- | Scooby Doo | yi- | Ivete Sangalo | yu- | Cyrax |
| wu- | Woody Woodpecker | | | | |

### 1.4 Room cards per location and tone (Meta-Sets, 101 cards on 2026-10-18)

A ❌ means no room card exists for that combination (a "gap").

| Location (final) | Tone 1 | Tone 2 | Tone 3 | Tone 4 | Tone 5 |
|---|---|---|---|---|---|
| Childhood home (Null) | outside the entrance, hallway | kitchen, dining room | living room, main bedroom, kids bedroom | bathroom, backyard | ❌ |
| Anexo 1 (-a) | outside the entrance, hallway | Kitchen, Cafeteria | Lobby, Salão Branco | bathroom, backyard | roof |
| CONOF (-o) | Outside the entrance, Hallway | Kitchen, Refectory | Lobby | Bathroom, Office | ❌ |
| Eupídio (-e) | outside the entrance | cafeteria | patio | bathroom, backyard | roof |
| Dad's house (-ai) | outside the entrance, hallway | kitchen, dining room | living room, bedroom | bathroom, backyard | ❌ |
| Heitor's house (-ei) | outside the entrance | kitchen, dining room | living room, bedroom | bathroom, backyard | roof |
| São Paulo home (-ao) | outside the entrance, hallway | kitchen, dining room | living room | bathroom, backyard | roof |
| SW home (-ou) | outside the entrance, hallway | kitchen, dining room | living room, bedroom | bathroom ⚠, backyard ⚠ | roof |
| Asa Norte home (-an) | outside the entrance, hallway | kitchen, dining room | living room | bathroom, backyard | roof |
| Lene's home (-en) | outside the entrance | kitchen | living room, bedroom | bathroom, backyard | roof |
| Anexo 4 (-ang) | outside the entrance | restaurant, cafeteria | lobby | bathroom, backyard | roof |
| In-laws' house (-eng) | outside the entrance, hallway | kitchen, dining area | living room, bedroom | bathroom, backyard | ❌ |
| Along (-ong) | outside the entrance, hallway | snack bar | middle of the studio | bathroom, backyard | roof |

⚠ The SW home bathroom and backyard cards list `-ù` as well as `-òu`. Since `iu` maps to `-ou`, `-ù` there is a typo. 就's Reason copied it.

---

## 2. Are the rules complete and unambiguous?

**The core rules are.** `syllable_coverage.py` ran all 412 standard Hanyu Pinyin syllables (a … zuo) through the rules:

- Every syllable maps to exactly one actor and one location. None fails.
- No two syllables share an actor + location pair, so a scene points back to exactly one pinyin syllable for its tone.
- All 55 actors are used. Most actors cover several syllables. bu-, pu-, mu- and fu- cover one each (bu, pu, mu, fu), and nü-/lü- cover two each (nü/nüe, lü/lüe).

**The problems are around the core:** gaps in the room cards (§1.4), cases the rules don't define yet (§3), wording that can point to two tones, actors that are easy to mix up (§4), and inconsistent data (§5).

---

## 3. Open decisions (rules to add)

- [ ] **D1. Syllables with no vowel (interjections).** The rules don't cover 嗯 ń / ňg / ǹg, 呣 m̀, 哼 hng, 噷 hm or 欸 ê. 嗯 is common. Options: treat a lone m/n/ng as the initial actor + Null (Michael Jackson / Nicholas Cage at Childhood home), or treat 嗯 as "en" (Jackie Chan at Lene's home). Pick one and write it on a Meta card. (欸's other readings ēi/éi/ěi/èi already fit: Null + -ei.)
- [ ] **D2. Which tone counts.** The notes use the **citation tone** (一 yī, 不 bù, 子 zǐ), not tone sandhi or the neutral tone you actually hear. Write that down. Also define when a neutral reading gets the roof: only for characters whose main reading is neutral (们 men, 么 me, 呢 ne, 吗 ma, 了 le, 的 de …). Five locations have no roof card (§1.4).
- [ ] **D3. Characters with several readings.** Right now this varies:
  - 兴: one numbered scene per reading (`1. … | 2. …`), but only one set in Reason.
  - 为: one Reason with two set links and two scenes.
  - 得: scene and Reason are built on **děi**, although **de** is far more common.
  - 的: built on **dì**; the common reading **de** is second.
  - 长: the scene covers cháng only; zhǎng (长大, 校长) has no scene.

  Suggested rule: the first reading in the Pinyin field drives the main scene. Each extra reading gets a numbered scene and a numbered actor/set line in Reason.
- [ ] **D4. Spelling over sound.** Everything goes by written pinyin (bo → b-, xiong → xi-, you → yi-), except one note: the lü- Meta-Actor's Extra field says "Can sound like lu… actor is Lotso". lü and lu are different syllables (旅 lǚ vs 路 lù), so this exception makes the mapping ambiguous. Suggested: delete the remark and always use Liu Kang for lü.
- [ ] **D5. One exact room per scene, named as on the card.**
  - *Tone 2 check, 2026-10-18:* 9 of the 10 tone-2 room types are eating places (kitchen, dining room/area, refectory, cafeteria, restaurant, snack bar, Lanchonete). No eating place is used for any other tone. The only exception is **Along - inside the entrance**, and no scene uses it (从 only says "near the entrance"). Suggested: retire that card, drop the "or inside the entrance…" clause from the tone-2 card, and move 从 to the Along snack bar. Then tone 2 always means "a place for eating". *(Done 2026-10-18: card retired, 从 moved, clause removed from the tone-2 card.)*
  - "Entrance" on its own is tone 1 (outside) everywhere except Along, where "inside the entrance" is tone 2. Your tone-2 card says "inside the entrance" applies only when no food place exists, yet Along also has a snack bar. Choose one of the two Along tone-2 rooms, or keep both and drop the condition from the tone card. 从's "near the entrance" can be read either way.
  - Similar spaces sit on different tones: patio (3) vs backyard (4), lobby (3) vs hallway (1), "in front of" / "door" / "outside" vs "outside the entrance" (1).
  - Some scenes name a second room as part of the story (况 snack bar, 唱 and 胖 restaurant, while the actor is in the bathroom; 头 and 雨 mention "outside"). The intended room is clear when you read the scene, but an automatic check reads it as a conflict. Suggested rule: the room where the actor **is** counts; mention other rooms only when the story needs them.
  - Childhood home has two bedrooms (main, kids). Scenes that just say "bedroom" are fine for the tone but don't say which one.
- [ ] **D6. People who are also locations.** "My dad" appears as a person in Childhood home scenes (日, 只), and Dad's house is the -ai location. Lene and Heitor carry the same risk. Suggested: name those people only when the scene is in their own location.
- [ ] **D7. One format for the Pinyin field.** Right now there are "1. gān (dry) | 2. gàn", "cháng | zhǎng", "dì, de" and capitalised "Zhí" / "Chuáng". Suggested: lowercase, readings in order of use, numbered `1. x | 2. y` when there is more than one.
- [x] **D8. Only single characters in the Characters deck.** (牙医's stray card moved, 2026-10-18.) 牙医 is a word; move it to Vocabulary.

---

## 4. Errors that keep recurring

Almost every actor error in the data is a **dropped or extra medial**: 回 h- instead of hu-, 铎 d- instead of du-, 思 si- instead of s-, 值 "-zhi", 旅 lu- instead of lü-, and the y-/w- shorthand. Some actor pairs make this mistake easier, especially where one actor was picked by spelling and the other by sound:

| Easy to mix up | Why |
|---|---|
| Tom Hanks (t-) / Tom (tu-) | same first name |
| Kung-fu Panda (ku-) / Kung Lao (qü-) | both "Kung" |
| Chaplin (ch-) / Chupacabra (chu-) / **Chewbacca (shu-)** | Chewbacca sounds like "chu" |
| **Charles Bronson (c-)** / Chaplin (ch-) | "Charles" sounds like ch- |
| Nemo (nu-) / Noob Saibot (nü-), Lotso (lu-) / Liu Kang (lü-) | u vs ü |

A quick test before saving a note: say the actor code, then the location final, and check that together they spell the pinyin without the tone mark.

---

## Suggested order (updated 2026-10-19, after the 07:33 sync)

Done so far: **every learned note is clean** (为 is flagged only because the checker can't read its two-set Reason). Meta decks: no repetitions, no broken links, `-ù` removed from SW home, Lô Borges spaced; the only Meta gaps left are 4 roof cards and the Gisele Bündchen space. Scenes written and notes unsuspended: 部 利 最 斯 海 比 由 重 合 特 信.

Everything below is on notes you haven't studied yet, so none of it hurts your reviews. It matters once new cards are back on.

1. **Unfinished notes that are NOT suspended** (they'd show up half-done as new cards): 性 华 市 陈 脑 理 无 全 因 使 将 具 值 且 努 窗. Suspend them, or write the scene first. 性 华 市 are near the front of the new-card queue.
2. **Wrong scenes:** 回 (Harrison Ford → Human Torch), 孩 (living room → a tone-2 room), 漂 (backyard → a tone-1 room), 雨 ("bedroom" but also "outside").
3. **Reasons:** 钟 (says 3rd tone, is 1), 仆 (says 4, is 2), 顾 (sh- → gu-), 旅 (lu- → lü-), 值 and 窗 (placeholders), 条 拿 站 ("Author:" → "Actor:"), 洗 (no tone).
4. **Room names that don't match a room note** (add the note, or add the word as a second name with `/`): Anexo 4 hallway (帮), CONOF backyard (错 座), Eupídio kitchen (血), In-laws' "dining room" vs "dining area" (层), Along "studio" vs "middle of the studio" (泳), "outside" vs "outside the entrance" (商 音), Childhood home "bedroom" → main or kids (主 女 李 旅 雨), "Sudoeste" → SW home (楼).
5. **Still suspended:**
   - Ready to unsuspend: 位.
   - Need more work: 被 (scene is only the set line; its prop is 礻 Ultraman, but 被's left side is 衤, the clothing radical, which has no prop note yet), 声 (Props list only 士).
   - Need a scene: 其 并 应 战 政 产 制 表 世 任 原 及 (12), plus the 14 Vocabulary notes (§5.5).
6. **Meta:** 4 roof cards (Childhood home, CONOF, Dad's house, In-laws' house) and "GiseleBündchen". Decide D2/D3 before writing scenes for neutral-tone or multi-reading characters.

Small things noticed: 部's Back says "unity" (probably "unit").

## 5. Fix list from the 2026-10-17 audit

To find a note in the browser, search `deck:MandarinMP::Characters Front:有` (replace the character).

### 5.1 Meta decks

- [x] *(added "In-laws' house - Outside the entrance", 2026-10-18)* Add room card **In-laws' house, tone 1** (e.g. outside the entrance). It's needed by 31 syllables (dēng, tīng, jīng, xīng, shēng, zhēng, bīng, qīng …). These scenes already use a tone-1 room there: 丁 听 生 经 声 兴(1) 灯 星 英 升.
- [x] *(added kitchen + dining room, 2026-10-18)* Add room card **SW home, tone 2** (e.g. kitchen). It's needed by 26 syllables (tóu, lóu, yóu, niú, qiú …). These scenes already use the kitchen: 头 牛 游 楼.
- [ ] Add **roof** cards (tone 5). Lene's home was added 2026-10-18 (们). Still missing: Childhood home, CONOF, Dad's house, In-laws' house.
- [ ] Add a card for each room that scenes use but that has no card yet (several rooms per tone is intended, see §1.2):
  - ~~In-laws' house hallway (tone 1): 兴 英~~ added 2026-10-18.
  - ~~Anexo 1 lobby (tone 3): 马 卡 法 打 把 哪 码~~ added 2026-10-18.
  - ~~Anexo 4 bathroom (tone 4): 上 让 样 向 况 唱 胖~~ added 2026-10-18.
  - Eupídio kitchen (tone 2): 血 (禾 和 决 fixed). Only the cafeteria exists.
  - ~~CONOF living room (tone 3): 我 果~~ (scenes moved to the Lobby, 2026-10-18). CONOF "lunch room" (昨 铎) is the Refectory (tone 2, `-ó`), which already has a card. Optionally change the wording to "refectory", or add "lunch room" as a second name on that note. CONOF backyard (tone 4): 错 座.
  - Along studio (tone 3): 泳 (总 fixed). The card says "middle of the studio".
  - ~~Anexo 4 cafeteria (tone 2): 羊~~ card added 2026-10-18. It is a different place from the restaurant (confirmed), so it keeps its own note.
  - ~~Anexo 1 restaurant (tone 2): 拿~~ fixed 2026-10-18 (Lanchonete renamed Cafeteria).
  - In-laws' "dining room" vs card "dining area": 层 (成 名 fixed 2026-10-18).
  - Anexo 4 hallway (tone 1): 帮.
  - Wording for tone 1 that differs from the cards ("entrance", "door", "outside" where the card says "outside the entrance"): 八 吃 商 音 (杯 fixed).
  - Childhood home "bedroom" without saying main or kids: 主 女 李 旅 雨.
- [x] Remove `-ù` from **SW home - bathroom** and **SW home - backyard**. *(Done by 2026-10-19.)*
- [x] Delete one of the two **Heitor's house - bedroom** cards. *(Deleted 2026-10-18; 北 relinked to the remaining card.)*
- [ ] Spelling of Meta-Actors names: **GiseleBündchen** → Gisele Bündchen (still open), ~~LôBorges~~ fixed.
- [ ] Use one spelling per location everywhere: Along / Along Pilates, São Paulo / Sao Paulo, CONOF / Conof, In-laws' / In-laws / In-law's.
- [ ] Decide D4 (the lü- "sounds like lu" remark).

### 5.2 The scene itself is wrong (rewrite the scene)

| | Char | Pinyin | Problem | Should be |
|---|---|---|---|---|
| [x] | 有 | yǒu | scene is at São Paulo home | SW home (-ou), living room (fixed 2026-10-18) |
| [ ] | 回 | huí | actor is Harrison Ford (h-) | Human Torch (hu-); Reason already says hu- |
| [x] | 从 | cóng | "at the Along studio, near the entrance": the studio is tone 3 and "entrance" is unclear | tone 2: Along - snack bar (see D5) |
| [ ] | 孩 | hái | Dad's house living room (tone 3) | a tone-2 room: kitchen or dining room |
| [x] | 火 | huǒ | CONOF lunch room = Refectory (tone 2) | tone 3: CONOF Lobby (fixed 2026-10-18) |
| [ ] | 漂 | piāo | São Paulo home backyard (tone 4) | tone 1: outside the entrance or hallway |
| [x] | 长 | cháng | Reason said zh- / 3rd tone, the scene is Chaplin in the restaurant (cháng) | ch- / -áng (Anexo 4 - restaurant) (fixed 2026-10-18). Optional, per D3: a second scene for zhǎng (George Clooney, Anexo 4 lobby). |

### 5.3 Reason field is wrong, scene is right (fix Reason only)

| | Char | Pinyin | Reason says | Should be |
|---|---|---|---|---|
| [x] | 禾 | hé | set link → Childhood home - kitchen (fixed 2026-10-18) | Eupídio - cafeteria (-e, tone 2). The scene uses Eupídio's kitchen, which needs a card (§5.1). |
| [x] | 和 | hé | -Null (Childhood home) | -é (Eupídio) (fixed 2026-10-18) |
| [x] | 炒 | chǎo | hu- / -o (CONOF) | ch- Chaplin / -ǎo (São Paulo home) (fixed 2026-10-18) |
| [x] | 铎 | duó | d- / -ou / 3rd tone | du- Darwin / -ó (CONOF) / 2nd tone |
| [ ] | 顾 | gù | sh- Sean Connery | gu- Gumball |
| [x] | 思 | sī | si- | s- Stallone (+ Null) (fixed 2026-10-18) |
| [ ] | 值 | zhí | "-zhi", no set | zh- George Clooney / Null, tone 2 |
| [ ] | 旅 | lǚ | lu- Lotso | lü- Liu Kang |
| [x] | 就 | jiù | set -ù (fixed to -òu 2026-10-18) | -iù (SW home - bathroom). Remove -ù from the SW home bathroom/backyard cards first (§5.1). |
| [x] | 为 | wèi | two linked sets, `Set: \| -èi … \| -éi …` | Content is correct (wu- / -èi bathroom, -éi kitchen). The audit flags it only because the parser doesn't read multi-line sets. Final format per D3. |
| [ ] | 条 | tiáo | "Author:" | "Actor:" |
| [ ] | 拿 | ná | "Author:" (new note, 2026-10-18) | "Actor:" (its room is fixed) |
| [ ] | 站 | zhàn | "Author:" (new note, 2026-10-18) | "Actor:" |
| [ ] | 窗 | chuāng | placeholder (`Actor: - / Set: x tone`) | chu- Chupacabra / -āng (Anexo 4), tone 1. Also needs a scene. |

**Wrong tone in Reason:**

| | Char | Pinyin | Reason tone | Correct |
|---|---|---|---|---|
| [x] | 或 | huò | 1 | 4 (fixed 2026-10-18) |
| [x] | 然 | rán | 1 | 2 |
| [x] | 决 | jué | 1 | 2. Scene uses Eupídio's kitchen, which needs a card (§5.1). |
| [x] | 如 | rú | 1 | 2 |
| [x] | 游 | yóu | 1 | 2 |
| [x] | 怎 | zěn | 4 | 3 |
| [x] | 得 | děi | 2 | 3 (the scene is in the living room, tone 3) |
| [x] | 喂 | wèi | 2 | 4 |
| [ ] | 仆 | pú | 4 | 2 |
| [x] | 最 | zuì | 2 | 4 |
| [ ] | 钟 | zhōng | "3rd tone" (the mark says 1) | 1 |
| [x] | 兴 | xīng | 4 | 1 for reading 1 (xìng is 4; follow D3). Scene 1 uses the hallway, which needs a card (§5.1). |

### 5.4 Notation only (optional cleanup)

- [x] Actor written `y-`, should be `yi-`: 羊 样 业 由 友 游 泳 药 (all fixed 2026-10-18).
- [x] Actor written `w-`, should be `wu-`: 文 王 外 位 未 喂 (all fixed 2026-10-18).
- [ ] Reason has no tone (old format without "Nth tone", or a set with no tone mark): 窗 值 洗 left (只 止 自 休 种 息 以 学 业 丁 听 为 fixed).
- [ ] Nicknames in scenes: Sudoeste (楼) → SW home; ~~Anexo I (八)~~ fixed. *(千 汽 昨 飞 肉 都 又 fixed 2026-10-18.)*
- [x] 另: scene says "In-law's house"; use In-laws' house. *(Fixed by 2026-10-19.)*
- [x] 飞: "in front of Heitor's house" → "outside the entrance of Heitor's house".

### 5.5 Notes with no scene, and suspended (unfinished) notes

Unfinished notes are **suspended** (that's how they were "disabled" while being added quickly). Unsuspend a note only when it's complete; it then takes its reorder position among the new cards.

As of the 17:31 sync on 2026-10-18 (者 由 弋 张 林 合 特 have scenes now):

- [ ] **Complete but still suspended, ready to unsuspend** (audit clean): ~~由 重 合 特 信~~ unsuspended; 位 ready; 被 and 声 need more work (see Suggested order).
- [ ] **Suspended, still need a scene:** 其 并 应 战 政 产 制 表 世 任 原 及 (12; 部 最 利 斯 海 比 done and unsuspended 2026-10-19). Most also have no meaning (Back) or examples yet. 最 also has the wrong tone in Reason.
- [ ] **Not suspended, no scene (will show up as new cards half-finished):** 性 华 市 陈 脑 理 无 全 因 使 将 具 值 且 努 窗 (16, in order of their new-card position; 性 is at position 42). Suspend them until the scene is written, or write it first.
- [ ] **Vocabulary, suspended and unfinished (14):** 活动 自动 运动 机会 一会儿 可是 哥哥 哥们儿 椅子 以为 以上 以下 内心 以内. Most have only the Front filled. 自动 哥哥 哥们儿 had been studied before.
- Not unfinished, leave as is: 一下 and 认为 each have one card suspended by Anki as a **leech** (too many lapses); .Resources, .Code and .Casting Actors are info notes, suspended on purpose.

### 5.6 Data hygiene

- [x] 見 has no pinyin (and no scene). *Not a problem: it's a linked note that shows 见's fields (see §6). The audit merges linked notes since 2026-10-19.*
- [x] **Notes with cards in both decks** (fixed 2026-10-18). A front ending in "." (长., 有., 的. …) is your single-character *Vocabulary* note, so 长. is not a duplicate of 长. Four notes have one card in each deck. In the browser, select the stray card → Change Deck:
  - 长. (Vocabulary note): move its Characters card to Vocabulary.
  - 個. [see: 个.] (Vocabulary note): move its Characters card to Vocabulary.
  - 牙医 (a word): move its Characters card to Vocabulary (D8).
  - 介 (Characters note): move its Vocabulary card to Characters.

  `audit_scenes.py` now prints these as "split deck".
- [ ] Capitalised pinyin: 值 "Zhí", 脑 "Nǎo", "Chuáng" (D7).

### 5.7 Repetitions in the Meta decks (check of 2026-10-18)

`meta_duplicates.py` checked 277 props, 55 actors, 13 locations, 5 tone cards and 101 room cards. Actors, locations, tone cards and room cards have no repetitions. All three prop problems involved a new note that was in the **Custom Study Session** filtered deck (50 Meta-Props notes; emptied back to Meta-Props on 2026-10-18).

The rule for props mirrors the rooms: **one note per component, one name per prop.** A name used for two components makes a scene ambiguous. Two notes for one component means two names to remember for the same thing.

| | Problem | Notes | Scenes involved | Suggested fix |
|---|---|---|---|---|
| [x] | Two components called **badge** | `badge.jpg` (learned; 给 豆 司 同 后 事 拿) and `tonal_lock.jpg` (new) | 贵 says "tonal lock" and shows `tonal_lock.jpg`, but doesn't link the note | The second note is a copy-paste slip: rename its c2 from "badge" to **tonal lock**, then link it from 贵's Props. *(Done 2026-10-18.)* |
| [x] | 矢 has two notes: **archer** (learned) and **hunter** (new) | `JD&iqB_Y[2` archer, `LkJr!\|V)U&` hunter | 候 says archer; 知's scene says archer but its Props say hunter; 医 links hunter | Keep **archer**: change 知's Props text and 医's scene and link to archer, then delete the hunter note. *(Done 2026-10-18: 知 医 link archer, hunter deleted.)* |
| [x] | 只 has two notes: **groucho glasses** (learned) and **Mr. Hat** (new) | `CsdAFj&Bnq` groucho glasses, `j^xIQ]qLyH` Mr. Hat | 识 links Mr. Hat and its scene names both ("Mr. Hat, who's wearing groucho glasses") | Pick one. Keeping the learned **groucho glasses** means rewording 识 and relinking it before deleting Mr. Hat. *(Done 2026-10-18: kept **Mr. Hat**, deleted groucho glasses. 识's scene still says "wearing groucho glasses" as story detail; optional to drop.)* |

Delete a prop note only after relinking every scene that points to it (§6 note).

**Lower priority, possible confusion:**
- Props that share a name with an actor: 目 **Muno** and 相 **Brobee and Muno** (actor mu- Muno); 去 **Kung Lao** (actor qü- Kung Lao); 𠂉 **woodpecker** (actor wu- Woody Woodpecker); 文 **queen** (actor qi- Queen Latifah). In a scene, Muno or Kung Lao can be read as the actor. Renaming the prop, for example to "Muno's eye", removes the doubt.
- ~~Near-identical names: jester hat / jester helmet~~. Intended: "hat" is the flat version, "helmet" the one with flaps.
- One name inside another (snake / dead snake / sleeping snake / stuffed snake, arrow / double arrow / triple arrow, sea horse / sea horse skewer …) is how the composite props are built, and is fine.

---

## 6. How the checker reads the notes

These details matter if the audit script is changed or the note format changes.

- **Reason has two formats.**
  - Old: `Actor: sh- | Location: -an | Set: 2nd tone | Props: …`
  - New: `Actor: [link:"GUID"|k-] (Keanu Reeves)<br>Set : [link:"GUID"|-ǒu] (SW home - living room)<br>Props: …`
- **`[link:"GUID"|label]`** (a myAnkidroid fork feature) points to the target note's `notes.guid`. GUIDs can contain `|`, `<` and `]`. Resolve the links **before** stripping HTML, and match the GUID with a lazy fixed-length pattern (`.{6,12}?`), not `[^|]`.
- Meta-Actors notes are Cloze: `Actor for {{c1::x-}} is {{c2::Name}}`. Meta-Sets uses `Location for {{c1::-x}} is {{c2::Name}}`, `Set for the {{c1::Nth}} tone is {{c2::…}}`, and Basic room cards (Front `Location - room`, Back listing the toned finals, e.g. `-āng, -iāng, -uāng`).
- Room cards: the part of the Front after ` - ` is the room; `/` separates other names for the same room. Room names are matched ignoring accents, apostrophes and repeated spaces. If the scene names the right-tone room, other rooms mentioned in the story are ignored.
- **Linked notes** (a myAnkidroid fork feature, `libanki/.../LinkedNotes.kt`). Configured by the `ankidroid-linked-note-config` script in both card templates: `linkedNoteField` "Linked Note", `searchField` "Front", picker limited to `MandarinMP::Characters`.
  - The field stores `{GUID} label` (older picks, e.g. 見) or `{guidhex:<GUID as UTF-8 hex>} label` (current picker, e.g. 的.). The label after the `}` is only for reading; the GUID decides.
  - In the reviewer, previewer, audio and type-answer the note is shown **merged**: Front always comes from the linked note, every other blank field is filled from it, and the note's own non-blank fields win. A banner on the card toggles "Show original" / "Show merged".
  - Only the fork does this. Anki desktop and the Browser show the stub's own (mostly empty) fields.
  - The scheduling stays separate: a stub has its own cards and its own HSK rank, so it's reviewed as a separate card that looks like the target.
  - 2026-10-19: 8 linked notes, all resolve: 的. 我. 你. 是. 了. 不. 在. (Vocabulary → the Characters note) and 見 (Characters → 见). Each has only Front and HSK Frequency of its own.
  - The audit checks linked notes as merged and reports a link that doesn't resolve as "broken linked note".
  - **Why the stubs exist (2026-10-19).** A trailing dot marks a one-character *word* ("好.") as opposed to the *character* ("好"). Several features rely on this:
    - The nav popup's "Word" button searches `MandarinMP::Vocabulary` for Front = selection + ".".
    - `ankidroid-frequency-config` gives "X." the global word rank and "X" the character rank.
    - The reorder script strips the dot to tie a word to its character.
  - The 7 Vocabulary stubs were created on 2025-07-12 as ordinary one-character word notes. On 2026-04-25 their content was emptied and replaced by a link. They are the deduplicated form of a pattern used across the collection: 101 one-character word notes, 94 of which still carry their own copy of the character note's Back, Scene and/or Examples (90 share at least one field word for word).
  - So the word card plus character card double review is a collection-wide design, not a linked-note problem. Navigation searches don't skip suspended cards, so suspending would not break the "Word" button. Whether to review both is a design choice for all 101 pairs.
  - 見 is a different case. The stub (created 2025-11-08) predates the "Char" button's `fallbackField: Traditional` (added 2026-04-20), which now finds 见 by itself. Its cards are new, would show the same 见 content, and have HSK rank 86, ahead of 见's 131.
    - [ ] Decide whether 見's stub cards should stay active (open).
  - **Implicit links (fork, 2026-10-19).** `implicitLinks` in `ankidroid-linked-note-config` links a note without a stored GUID. It applies only when Linked Note is empty, so a stored link always wins. For MandarinMP, add to the config block in **both** card templates:
    ```json
    "implicitLinks": [
      { "fromDeck": "MandarinMP::Vocabulary", "pattern": "^(.)\.$",
        "toDeck": "MandarinMP::Characters", "toField": "Front", "fallbackField": "Traditional" }
    ]
    ```
    - A Vocabulary note whose Front is one character plus "." links to the only Characters note with that Front (or that Traditional, if no Front matches). If several match, nothing is linked.
    - The banner says "Linked note view (by Front)". Filled fields still win, so the 94 copies look the same until fields are emptied.
    - A new word stub is just a note with Front `好.` in Vocabulary (plus HSK Frequency).
  - **Browser → select notes → ⋮ → "Empty fields that match linked note"** (one undoable step):
    - Empties each field that shows the same as in the linked note. Comparison ignores `<br>`, spacing, `{x.mp3}{*[sound:x.mp3]*}` wrappers and `(*1)` markers; links, formatting and wording count.
    - Never touches Front, Linked Note or the rule's source field.
    - Tags fields that still differ `linked-differs::<Field>` (replaced on every run).
    - Keeps frequency fields (HSK Frequency) and doesn't tag them: a word's rank is meant to differ from its character's.
    - Stores a link found by the implicit rule in Linked Note, so it shows in the editor, on desktop and to the audit.
    - On a second run, nothing is left to empty, so it reports "0 fields on 0 notes". That's expected.
  - **Preview on the 09:08 sync** (`tools/mandarinmp_audit/simulate_implicit_links.py`): all 94 word copies link.
    - 27 become pure stubs (only Front and HSK Frequency left): 他 这 说 她 人 来 个 点 谁 太 里 小 年 门 家 书 还 几 地 月 狗 本 块 四 五 十 字.
    - 67 keep at least one field.
    - Of the 61 Reasons that differ, 46 are the old plain form while the character note has the linked form, so they're safe to empty by hand. Check the other 15: 有 很 那 都 第 请 长 钱 坏 才 些 岁 号 少 見.
    - 見. links to the 見 stub in Characters (Front match), not to 见, so its display barely changes.
    - [x] Add `implicitLinks` to both templates (present in the 13:00 desktop copy).
    - [ ] Select `deck:MandarinMP::Vocabulary` in the Browser, run the action, then work through `tag:linked-differs::*` (no tags in the 13:00 copy yet).
  - **Creating the word from the character (fork, 2026-10-19).** Purpose: every character that is also a word gets a Vocabulary "X." note, so the Vocabulary deck counts single-character words too. The new note has only Front `X.`, HSK Frequency (word rank, as the editor's button would fill it) and Linked Note pointing back to the character. Everything else stays empty and is shown from the character.
    - **While studying:** select the character, tap "Word". If `X.` doesn't exist, the "No match" dialog now says "Linked to: X (Characters)". **Create** adds the linked note directly; **Open editor** keeps the old behaviour.
    - **Browser:** select Characters notes → ⋮ → **"Create linked word notes"**. It lists what it will create, each with its word frequency rank (most frequent first), and what already exists. Then it adds them all in one undoable step.
    - The word text comes from the rule's pattern (`^(.)\.$` gives `X.`). A rule whose pattern isn't plain text around one group needs `"createAs": "$1."`.
    - The link is stored (GUID), so it survives renaming either note and doesn't need the rule.
    - 13:00 copy: 503 Characters notes, 101 already have a word note, 401 don't. Only the ones that are words on their own should get one.
    - **Which characters (analysis 2026-10-19).** The existing pairs follow no consistent rule: 61 of 101 were made in July 2025, and 八 七 九 冷 茶 走 找 要 到 对 are missing. The ranking data does separate them. Ratio = word rank ("X.") ÷ char rank: median 0.8 with a word note, 6.3 without.
      - Suggested rule: word rank ≤ 2000, ratio ≤ 2, and Back not marked "bound form", "rarely used alone" or "usually in combinations". It keeps 96 of the 101 (misses 大 长 先 地 第) and proposes 107 more (57 already studied).
      - Ratio 2–5 is a grey zone (出 新 高 老 马 关 分 …): free words that are also common in compounds. Decide by hand.
      - Check segmentation artefacts before creating: 宜 世 使 及 元.
      - [ ] Pick from the candidate list and create them with "Create linked word notes" (open).
- Actor matching in scenes uses accent-folded text, splits CamelCase names, uses word boundaries ("Roo" ≠ "room"), and removes longer names first (Tom Hanks before Tom). Folding must keep **ü** apart from u for n/l (nü ≠ nu).

---

**Before deleting or renaming a Meta card,** search for notes that link to it (`"Reason:*<guid>*"`), or run the audit afterwards. It reports "set link points to a deleted note". A deleted target leaves the `[link:]` in the notes pointing nowhere.

## Re-running the audit

Read-only. Works while Anki desktop is open, because it uses a copy.

```bash
S=/c/Users/damar/AppData/Local/Temp/mandarinmp_audit   # any scratch folder
mkdir -p "$S"
cp "$APPDATA/Anki2/User 1/collection.anki2" "$APPDATA/Anki2/User 1/collection.anki2-wal" "$S/" 2>/dev/null
cd /h/git/personal/myAnkidroid/tools/mandarinmp_audit
C:/Python314/python -I audit_scenes.py "$S/collection.anki2" "$S/audit.json"
C:/Python314/python -I syllable_coverage.py audit_scenes.py "$S/audit.json"
C:/Python314/python -I meta_duplicates.py "$S/collection.anki2"   # repetitions in the Meta decks (§5.7)
```

Cards in a filtered deck (Custom Study Session) are counted in their home deck by both `audit_scenes.py` and `meta_duplicates.py`.

`audit.json` holds `actors`, `locations`, `roomcards`, `missing` (gaps), `meta_issues`, and `rows`. Each row has three issue lists: `R` (Reason wrong), `S` (scene wrong) and `N` (notation / missing room card).
Expect some false positives in `S` for scenes that name a second room or a nickname (see D5). Sync desktop first so the copy is current.

---

## Change log

| Date | Change |
|---|---|
| 2026-10-17 | First audit; rules derived from the Meta decks; completeness test (412/412 syllables). |
| 2026-10-18 | This document written; audit scripts saved to `tools/mandarinmp_audit/`. |
| 2026-10-18 | Re-audit after desktop sync: 506 notes (+旁 拿 站). 或 tone fixed. 百 新 头 Reason converted to the linked format. New "Author:" typo in 拿 and 站. Meta decks unchanged (7 gaps remain). |
| 2026-10-18 | 有 and 长 fixed and verified. Correction: 长. is the Vocabulary note for 长, not a duplicate; four notes (长. 個. 牙医 介) have cards split across Characters/Vocabulary. Audit script now reports split decks. |
| 2026-10-18 | Split-deck cards moved (长. 個. 牙医 → Vocabulary, 介 → Characters). Characters now has 503 notes; 27 Reason / 57 scene / 78 notation issues. No Meta deck changes yet. |
| 2026-10-18 | Added In-laws' house - Outside the entrance, SW home - kitchen and SW home - dining room (5 gaps left, all roofs). Fixed 和 然 决 丁 怎 听 牛 生 得 经 如 头 思 兴 喂. Still open from this batch: 禾 and 就. Totals: 18 Reason / 56 scene / 67 notation. |
| 2026-10-18 | Tone 2 check: rooms consistent except Along - inside the entrance; 火 fixed; 从 added to §5.2. CONOF: "lunch room" = Refectory. Kitchen is a separate room; both are tone 2 (`-ó`, eating places). 昨 铎 are fine; 火 still needs a tone-3 room. |
| 2026-10-18 | Clarified: several rooms per tone is intentional. A missing room card means add the card, not rewrite the scene. |
| 2026-10-18 | 09:49 sync: added In-laws' house - Hallway, retired Along - inside the entrance. Fixed 禾 就 铎 从 火 昨 (wording), 者 scene written. Totals: 15 Reason / 53 scene / 63 notation. Suggested order rewritten. |
| 2026-10-18 | 10:55 sync: added Anexo 1 - Lobby, Anexo 4 - bathroom, Lene's home - roof; deleted the Heitor bedroom duplicate (北 now has a broken link); tone-2 clause removed. Fixed 从 飞 况 唱 千 汽 胖 上 让 我 总 杯 果 决 样 肉 都 又 成 与 向 名 昨 and others. Totals: 16 Reason / 47 scene / 42 notation. |
| 2026-10-18 | Rule added: one note per room; other names for the same room go on that note after `/` (§1.2 rule 3). Audit reads `/` names, warns on a name repeated across notes, flags "bedroom" at Childhood home as ambiguous, ignores apostrophes/double spaces, and no longer flags story mentions when the right room is named. |
| 2026-10-18 | 12:07 sync: added Anexo 4 - cafeteria; Anexo 1 Lanchonete renamed Cafeteria. Fixed 北 (relinked) 禾 羊 炒 游 拿(room) 由(scene written), all y-/w- codes and the no-tone Reasons on learned notes. Learned notes with issues: only 八 吃 另 弋. Totals: 13 Reason / 44 scene / 19 notation. |
| 2026-10-18 | Anexo 4 restaurant and cafeteria confirmed as two places. Meta-deck repetition check added (`meta_duplicates.py`, §5.7): badge name clash (tonal lock), 矢 archer/hunter, 只 groucho glasses/Mr. Hat; props named like actors. Audit now counts filtered-deck cards in their home deck. |
| 2026-10-18 | Custom Study Session emptied: all 100 Meta-Props cards back home (5 still in learning, 95 new at their reorder positions). §5.7 prop fixes verified: tonal lock renamed and linked from 贵; 矢 merged into archer; 只 merged into Mr. Hat. 276 props, no repetitions, no broken links anywhere. |
| 2026-10-18 | Suspended notes reviewed (§5.5): 26 Characters notes suspended as unfinished, 8 of them now complete and ready to unsuspend; 16 notes without a scene are not suspended; 14 unfinished Vocabulary notes suspended. 弋 张 林 合 特 got scenes. Totals: 13 Reason / 38 scene / 17 notation. |
| 2026-10-19 | 07:33 sync: all learned notes clean (为 parser-only). Fixed 八 吃 另 弋, SW home `-ù`, Lô Borges. Scenes written and unsuspended: 部 利 最 斯 海 比 由 重 合 特 信. No broken links, no Meta repetitions. Totals: 12 Reason / 32 scene / 17 notation, all on unstudied notes. Suggested order rewritten. |
| 2026-10-19 | Linked notes: 見 is a stub linked to 见 (Linked Note field), not a note missing its pinyin. Documented how linked notes work (§6); audit_scenes.py now merges them like the reviewer. 8 linked notes, none broken. Reason total 12 → 11. |
| 2026-10-19 | Linked stubs: the double review isn't specific to them. All 101 one-character words have a word note ("X.") beside the character note, used by the nav "Word" button and the word frequency rank; the 7 stubs are the deduplicated version. Only the 見 stub is a pure duplicate now. §6 corrected. |
| 2026-10-19 | Added the optional "more linked stubs" item in §6: 21 word notes are exact copies, 73 have drifted from their character note. |
| 2026-10-19 | Fork: implicit links (`implicitLinks` rule in LinkedNotes.kt) and the Browser action "Empty fields that match linked note". Added simulate_implicit_links.py; preview: 94 linked, 27 become pure stubs. §6 updated. |
| 2026-10-19 | Fork: create the "X." word note from its character, empty and linked back. In the reviewer's "Word" no-match dialog (Create) and the Browser ("Create linked word notes"). `implicitLinks` confirmed in both templates. §6 updated. |
| 2026-10-19 | Analysed which characters have a word note. No rule in the existing pairs; frequency rule proposed (word rank ≤ 2000, ratio ≤ 2, not marked bound): 96/101 kept, 107 candidates. §6. |
| 2026-10-19 | Fork fix after trying 他.: "Empty fields that match linked note" now stores the implicit link in Linked Note and no longer tags HSK Frequency. §6. |
