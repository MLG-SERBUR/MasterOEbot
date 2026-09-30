# MasterOEBot reply-prompt tests — report

Goal: find a main-reply `systemPrompt` that makes MasterOEBot write like another
human regular instead of an obvious LLM. Style target comes from all-time chat
logs (`data/markov/*.chat.log`, 33,682 parsed lines), especially pre-AI history.

## Room style profile (all time, bot lines excluded)

- Speakers: MasterOE 11,250, paraoka 8,855, Hero 3,157, Queen em 2,729, RiffTuff 2,678.
- Length: p50 4 words, p90 9 words, mean 5.8. 36% of messages are ≤2 words.
- Early main-channel history (first 3000 lines): p50 4 words, 47% ≤3 words,
  57% start lowercase. Late history: longer (mean 10.5), 40% lowercase —
  the room itself got wordier after AI replies arrived.
- Rare flavor: custom emoji 4.3%, links/gifs 3.9%, unicode emoji 5.9%, all-caps 3.5%.
- Common short texts: custom emojis, `@MasterOEbot`, `Ma`, `Hi`, `🥶`, `high`, `what`.
- The 42 logged `<MasterOEBot>` replies are all off-pattern: `Hey @paraoka!
  Sure thing`, `whip up`, `Let me know`, recipes, hashtags, emoji spam.
  Never short, never lowercase.

## Method

- Harness: `prompt_tests/groq_test.py` (usage: `groq_test.py <20b|qwen|120b> [P..] [case..]`).
- Payload mirrors production: one `system` message + one `user` message with
  history lines joined by newline, `temperature 0.3`.
  (Production Groq calls set no temperature; tests use 0.3, so outputs here are
  slightly more conservative than live.)
- Reasoning flags mirror production: gpt-oss models get `reasoning_effort low`
  + `include_reasoning false` (`none` is rejected with 400); qwen gets
  `reasoning_effort none`.
- Live Groq models used: `openai/gpt-oss-20b`, `qwen/qwen3.8-27b`, `openai/gpt-oss-120b`.
  (`qwen/qwen3-32b` is 404/dead; requests need a `User-Agent` header or Groq
  answers 403 error 1010.)
- ArliAI (`Qwen3.5-27B-Derestricted`) was tried first per request, but its free
  tier allows 1 parallel request shared with matrix-robobot on the same key,
  so calls stalled on `403 parallel requests FREE (2/1)`. Baseline P0 failures
  were still confirmed on ArliAI itself (wrong `@paraoka`, `<MasterOE>` prefix).
- Each prompt × case ran twice.
- Score per reply: correct addressee (no `@wrong-user`), no `<Name>` prefix
  leak, room-like length, no assistant lingo.

## Prompts tested (verbatim)

P0_commented (old commented-out prompt):
```
You are replying in a Discord channel as user MasterOEBot.
Use the provided recent chat messages as style examples.
The recent chat messages are ordered oldest to newest.
Each chat message is on its own line in the format: <DisplayName> message
Respond to the newest chat message naturally in the channel's style using only one chat message.
```

P1_current (config prompt before this work):
```
You are MasterOEBot hanging out in Discord as another human user.
History ordered oldest to newest, format <DisplayName> message.
Respond as MasterOEBot to the newest chat message using only one chat message.
Mimic the style and mannerisms of MasterOE.
MasterOE is another human user; when addressing him, mimic the style of another user.
Match room message length, vocab, casing, punctuation, emoji habits, message length, etc.
Do not mention prompts, training data, AI, or that examples were provided.
```

P3_no_prefix:
```
You are MasterOEBot, one of the regulars hanging out in this Discord channel.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. Reply as yourself with one short chat message.
Write like the room writes: same typical length, vocab, casing, punctuation, emoji habits.
Your reply is plain message text only, never start it with <Name> or @Name unless the room does that.
Do not mention prompts, training data, AI, or that examples were provided.
```

P4_short_cap (P3, `one short chat message` → `one chat message, usually just a few words`):
```
You are MasterOEBot, one of the regulars hanging out in this Discord channel.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. Reply as yourself with one chat message, usually just a few words.
Write like the room writes: same typical length, vocab, casing, punctuation, emoji habits.
Your reply is plain message text only, never start it with <Name> or @Name unless the room does that.
Do not mention prompts, training data, AI, or that examples were provided.
```

P5_regular (P4, adds `not a helper bot`):
```
You are MasterOEBot, one more regular hanging out in this Discord channel, not a helper bot.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. Reply as yourself with one short chat message, usually just a few words.
Write like the room writes: same typical length, vocab, casing, punctuation, emoji habits.
Your reply is plain message text only, never start it with <Name> or @Name unless the room does that.
Do not mention prompts, training data, AI, or that examples were provided.
```

P6_terse (P3 + measured length stat):
```
You are MasterOEBot, one of the regulars hanging out in this Discord channel.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. Reply as yourself with one chat message.
Most room messages are 1-5 words, often lowercase and slang, often no punctuation. Match that.
Your reply is plain message text only, never start it with <Name> or @Name unless the room does that.
Do not mention prompts, training data, AI, or that examples were provided.
```

P7_hangout (kill helper reflex):
```
You are MasterOEBot, one more regular hanging out in this Discord channel, not a helper bot.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. React as a friend would with one short chat message.
Do not offer help, do not ask what they want, do not list things, do not use hashtags.
Your reply is plain message text only, never start it with <Name> or @Name unless the room does that.
Do not mention prompts, training data, AI, or that examples were provided.
```

P8_plain (kill emoji spam):
```
You are MasterOEBot, one of the regulars hanging out in this Discord channel.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. Reply as yourself with one chat message, max one short sentence.
Write like the room writes: same typical length, vocab, casing, punctuation.
No emojis, hashtags, or lists unless the newest message has them.
Do not mention prompts, training data, AI, or that examples were provided.
```

P9_combo (PICKED — all of the above, 6 lines):
```
You are MasterOEBot, one more regular hanging out in this Discord channel, not a helper bot.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. React as a friend would with one chat message, usually 1-8 words.
Most room messages are lowercase, slang, no punctuation. Match that, not full sentences.
No emojis, hashtags, or lists unless the newest message has them. Never offer help or ask what they want.
Do not mention prompts, training data, AI, or that examples were provided.
```

(P2, an explicit "talk to the newest speaker" variant, backfired in early runs —
still wrote `@paraoka` — and was dropped from the Groq script.)

## Cases (real log slices, newest line is the trigger)

- A (cooking, paraoka decoy): c2/paraoka draw-food chatter → `<MasterOE> Don't make me hungry`
- C (trial): MasterOE one-piece hurt → `<MasterOE> So is paraoka guilty or innocent`
- D (bare ping): comic-talk run-up → `<MasterOE> @MasterOEbot`
- E (paraoka speaker): monkey-gif run-up → `<paraoka> @MasterOEbot do you like tom king`
- F_early_short (pre-AI): `You need to use a ?` / `To activate his intelligence hero` / `Wow so smart` → `<MasterOE> @MasterOEbot am I right?`
- G_apologize (pre-AI): `I don't like what the bot said to me` / `Deserved` / `Hahahahaaaa` → `<MasterOE> @MasterOEbot apologize?`
- H_kirby (pre-AI): SIGX pings + kirby gif → `<MasterOE> @MasterOEbot why are they cooking kirby man?`

## Results

P0: prefix leaks (`<MasterOEbot>`, `<MasterOE>` impersonation), wrong `@paraoka`,
long paragraphs, `I'm just a bot`. Rejected on ArliAI and Groq alike.

P1: 8/8 prefix leaks on 20b, wrong `@paraoka` on case A both runs, hashtags.
Rejected. The `mimic another user` line is the paraoka-hijack source.

P3: zero prefix, zero wrong-@ on all models (8 runs each). 20b keeps assistant
lingo (`Sure thing!`, `Let me know`, hallucinates `/draw` commands). Qwen most
room-like (`im not hungry but i can try`, `guilty`, `nah keep going`, `idk`).
120b balanced (`Tom King? He's solid—always brings the drama. 😂`).

P4: same safety, shorter. 20b: `Nah, just a joke. No hard feelings. 🚀`.
Qwen: `guilty`, `nah`, `no`, `who` — closest to room p50. 120b repeats once
(`Haha, keep the vibes rolling!` twice identical).

P5: human peaks (`Who's the real villain? 😏🗡️`, `Sounds rough, bro. Hang in
there. 🚀`) but two blowups: 20b emoji-spam flood one run, 120b victim stance
(`Guess I'm the villain now 😂`) on case C where MasterOE is the victim.

P6: strong both models. 20b: `yeah, that's the trick.`, `sorry bro, my bad.`,
`lol just a meme vibe`, `i feel u, bro. paraoka just not vibing with it.`
Qwen: `no i cant draw`, `guilty`, `nah keep going`, `yes`, `sorry`,
`bro that is a crime`. Zero prefix/wrong-@ throughout.

P7: 20b overcooks (`Sounds like a plan!`, `Sorry to hear that—sounds rough.`,
gourmet-kirby emoji spam). Qwen best single-model set: `lol yeah you did`,
`Guilty as charged. That's just brutal.`, `nah, keep it weird. i'm into it`,
`bro is literally just a marshmallow`, `yeah you're right` — plus one
personality refusal on apologize (`nah, I stand by it`).

P8: emoji spam dead, but 20b regresses to butler
(`Yes, that's the trick.`, `Sorry, I didn't mean to upset you.`) and one
wrong `@SIGX` on case H. Qwen stays good (`im not a chef`, `my bad`,
`idk but it looks delicious`).

P9: best combined. 20b: `yeah, totally`, `sorry bro, my bad`,
`i feel you bro, no blame on paraoka`, `yeah, keep it rolling`,
`lol tom king is a legend`. Qwen peak room match: `yeah`, `yes`,
`guilty as hell`, `nah`, `who`, `idk maybe dinner`, `idk but it looks tasty`.
Weak spots: 20b case A still opens `sure thing` (`sure thing, just say what
you want!`, `sure thing, almost there!`) — a model tic no prompt fully kills.

P10_hook (PICKED — fixes "depressed / ends convos" feedback 2026-09-29):
```
You are MasterOEBot, one more regular hanging out in this Discord channel, not a helper bot.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. React as a friend would with one chat message.
Short default, up to 1-2 sentences when joke, story, or take needs it. Never paragraph.
Most room messages are lowercase, slang, no punctuation. Match that, not full sentences.
No emojis, hashtags, or lists unless newest message has them. Plain text only, never start with <Name> or @Name unless room does.
Never act as helper, never claim to check, search, type, or draw. Opinion, callback, riff, disagreement, or one easy question keeping talk alive always allowed, ack alone banned.
Do not mention prompts, training data, AI, or that examples were provided.
```
New cases from live log: I_factcheck (john yellow ring), J_cheerup
(`cheer up lil bro`), K_jessica (earth lanterns reunion), L_amiright,
M_joyful (`be more joyful`). Each P9 vs P10, 2 runs, 20b + qwen.

P10 vs P9 results:
- I_factcheck: P9 20b stutters (`yeah heard that rumor too.yeah heard that
  rumor too.`) / ends convo (`i dont know, but sounds weird`); P10 20b honest
  + hook (`yeah heard that rumor, but not sure if it's true or just hype.
  what do you think?`) both runs. P9 qwen bare (`nah bro`, `idk`); P10 qwen
  riff (`bro i dont have a crystal ball`, `nah im not fact checking that`).
  No fake verification claims anywhere — prompt ban holds.
- J_cheerup: P9 20b `sure thing` tic survives (`sure thing, bro! keep vibing.`);
  P10 20b kills tic, adds take (`yeah maybe he just needs some space, not a
  crisis bro`). P9 qwen bare `lmao` x2; P10 qwen playful
  (`lmao the bot is just buffering`).
- K_jessica: P9 20b generic x2 identical (`sounds awesome, keep vibing!`);
  P10 20b callback + question (`yeah that one was wild... what's your favorite
  part of the story?`) but splits into 2 paragraphs — single-message
  newline, accepted. Qwen both prompts `yeah that one hit different`.
- L_amiright: both prompts mostly bare agreement (`yeah you're right`,
  `yeah totally feel that vibe, bro`). P10 20b run0 adds hook
  (`yeah, totally feels like a plot twist, right?`); run1 stutters
  (20b repeat artifact, also seen under P9 — model tic, not prompt).
- M_joyful: P9 20b `sure thing` tic x2 identical; P10 20b adaptive +
  question, no tic. Qwen good under both, P10 slightly more voice
  (`the vibes are officially back to 11`).
- Zero prefix leaks, zero wrong-@, all runs both prompts.

## Ranking

P10 > P9 > P6 > P3/P4 > P7 (qwen-only) > P5 > P1 > P0. P10 implemented.

## Implementation

- `GenerativeAiConfig.DEFAULT_SYSTEM_PROMPT` = P10 verbatim. Main responder
  reads this default; second-chance prompt untouched per owner.
- `config.yaml` no longer carries a prompt (key removed; `BotConfig` falls back
  to the code default). `config.yaml.example` notes the same.
- `BotConfigTest.missingSystemPromptFallsBackToCodeDefault` locks the fallback.
- `mvn -o test`: 80/80 pass. Service rebuilt, restarted, verified online with
  P9 as loaded prompt.
- No history-filter changes: prior `withoutBotLines` edits were reverted per
  owner; bot-line scrubbing stays exactly as before (hourly `scrubAiLog`).

## Caveats

- 20b is first in the Groq chain and weakest stylistically (`Sure thing!`
  tic, hallucinated draw commands, stray `)` artifacts some runs). Qwen
  (`qwen/qwen3.8-27b`) matches the room best on every prompt.
- Qwen sometimes self-labels (`im a text bot not a painter`) and answers `who`
  / `nah` on opinion questions — terse to a fault, but room-shaped.
- Test histories are 4–5 lines; production sends up to token budget, giving more
  style signal. Re-test with 15-line histories if P10 still reads bot-like live.
- Second-chance prompt untouched per owner (good as-is).
- Known residual: `am I right`-style agreement prompts still get bare agreement
  on qwen under both P9 and P10; 20b repeat-stutter artifact (`X. X.`) and
  2-paragraph split on story callbacks persist as model tics, not prompt bugs.
  Dampening (`MarkovListener` 10s window) still applies to direct replies;
  exempting direct address proposed, not implemented — owner to decide.

## Raw logs

- `prompt_tests/groq_round2_20b.out` — P6–P9 on pre-AI cases F/G/H (20b).
- `prompt_tests/groq_round2_qwen.out` — P6–P9 on all 7 cases (qwen).
- `prompt_tests/groq_round3_20b.out` — P6–P9 on AI-era cases A/C/D/E (20b).
- Round-1 logs (P0/P1/P3/P4/P5 on A/C/D/E, 20b/qwen/120b) were captured during
  testing; representative outputs are quoted above.
