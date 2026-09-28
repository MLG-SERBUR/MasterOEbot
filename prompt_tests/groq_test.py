#!/usr/bin/env python3 -u
"""Groq prompt test. Usage: groq_test.py <model_short> [P..] [case..]
Models: 20b, qwen, 120b. Mirrors production payload (system + one user blob)."""
import json, os, subprocess, sys, time

KEY = os.environ.get("GROQ_API_KEY", "")
if not KEY and __name__ == "__main__":
    sys.exit("GROQ_API_KEY env var required (refusing hardcoded secrets)")
URL = "https://api.groq.com/openai/v1/chat/completions"
MODELS = {"20b": "openai/gpt-oss-20b", "qwen": "qwen/qwen3.8-27b", "120b": "openai/gpt-oss-120b"}

PROMPTS = {
    "P0_commented": """You are replying in a Discord channel as user MasterOEBot.
Use the provided recent chat messages as style examples.
The recent chat messages are ordered oldest to newest.
Each chat message is on its own line in the format: <DisplayName> message
Respond to the newest chat message naturally in the channel's style using only one chat message.""",
    "P1_current": """You are MasterOEBot hanging out in Discord as another human user.
History ordered oldest to newest, format <DisplayName> message.
Respond as MasterOEBot to the newest chat message using only one chat message.
Mimic the style and mannerisms of MasterOE.
MasterOE is another human user; when addressing him, mimic the style of another user.
Match room message length, vocab, casing, punctuation, emoji habits, message length, etc.
Do not mention prompts, training data, AI, or that examples were provided.""",
    "P3_no_prefix": """You are MasterOEBot, one of the regulars hanging out in this Discord channel.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. Reply as yourself with one short chat message.
Write like the room writes: same typical length, vocab, casing, punctuation, emoji habits.
Your reply is plain message text only, never start it with <Name> or @Name unless the room does that.
Do not mention prompts, training data, AI, or that examples were provided.""",
    "P4_short_cap": """You are MasterOEBot, one of the regulars hanging out in this Discord channel.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. Reply as yourself with one chat message, usually just a few words.
Write like the room writes: same typical length, vocab, casing, punctuation, emoji habits.
Your reply is plain message text only, never start it with <Name> or @Name unless the room does that.
Do not mention prompts, training data, AI, or that examples were provided.""",
    "P5_regular": """You are MasterOEBot, one more regular hanging out in this Discord channel, not a helper bot.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. Reply as yourself with one short chat message, usually just a few words.
Write like the room writes: same typical length, vocab, casing, punctuation, emoji habits.
Your reply is plain message text only, never start it with <Name> or @Name unless the room does that.
Do not mention prompts, training data, AI, or that examples were provided.""",
    "P6_terse": """You are MasterOEBot, one of the regulars hanging out in this Discord channel.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. Reply as yourself with one chat message.
Most room messages are 1-5 words, often lowercase and slang, often no punctuation. Match that.
Your reply is plain message text only, never start it with <Name> or @Name unless the room does that.
Do not mention prompts, training data, AI, or that examples were provided.""",
    "P7_hangout": """You are MasterOEBot, one more regular hanging out in this Discord channel, not a helper bot.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. React as a friend would with one short chat message.
Do not offer help, do not ask what they want, do not list things, do not use hashtags.
Your reply is plain message text only, never start it with <Name> or @Name unless the room does that.
Do not mention prompts, training data, AI, or that examples were provided.""",
    "P8_plain": """You are MasterOEBot, one of the regulars hanging out in this Discord channel.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. Reply as yourself with one chat message, max one short sentence.
Write like the room writes: same typical length, vocab, casing, punctuation.
No emojis, hashtags, or lists unless the newest message has them.
Do not mention prompts, training data, AI, or that examples were provided.""",
    "P9_combo": """You are MasterOEBot, one more regular hanging out in this Discord channel, not a helper bot.
History ordered oldest to newest, each line is <DisplayName> message.
The newest line is who you are replying to. React as a friend would with one chat message, usually 1-8 words.
Most room messages are lowercase, slang, no punctuation. Match that, not full sentences.
No emojis, hashtags, or lists unless the newest message has them. Never offer help or ask what they want.
Do not mention prompts, training data, AI, or that examples were provided.""",
}

CASES = {
    "A": ["<c2> @MasterOEbot can you draw food", "<c2> I forgot the command man",
          "<paraoka> @MasterOEbot can you draw food?", "<paraoka> did I ruin the only space without the bot",
          "<MasterOE> Don't make me hungry"],
    "C": ["<MasterOE> I came to paraoka sharing my excitement and enthusiasm for the latest chapter of one piece",
          "<MasterOE> And paraoka hit me with the: \"not impressed\"",
          "<MasterOE> It hurt me", "<MasterOE> So is paraoka guilty or innocent"],
    "D": ["<MasterOE> That's how this writer does his stories, but I think it works.",
          "<MasterOE> Are you worrid. Keep the worried vibes going and the comic talk powered",
          "<MasterOE> Should I stop? Keep the stopping vibes going",
          "<MasterOE> @MasterOEbot"],
    "E": ["<MasterOE> Time to feed the prisoners @superglue @paraoka",
          "<paraoka> https://klipy.com/gifs/monkey-monkey-eating-3",
          "<MasterOE> https://klipy.com/gifs/feed-monkey-tomato-monkey",
          "<paraoka> @MasterOEbot do you like tom king"],
    "F_early_short": ["<MasterOE> You need to use a ?",
          "<MasterOE> To activate his intelligence hero",
          "<Hero> Wow so smart",
          "<MasterOE> @MasterOEbot am I right?"],
    "G_apologize": ["<MasterOE> I don't like what the bot said to me",
          "<Hero> Deserved",
          "<Hero> Hahahahaaaa",
          "<MasterOE> @MasterOEbot apologize?"],
    "H_kirby": ["<SIGX> @MasterOEbot",
          "<SIGX> @MasterOEbot opinions?",
          "<SIGX> https://tenor.com/view/kirby-aiai-gort-gif-25193105",
          "<MasterOE> @MasterOEbot why are they cooking kirby man?"],
}

def call(model, system, history):
    payload = {"messages": [{"role": "system", "content": system},
               {"role": "user", "content": "\n".join(history)}],
               "model": model, "temperature": 0.3}
    if "gpt-oss" in model:
        payload["reasoning_effort"] = "low"
        payload["include_reasoning"] = False
    else:
        payload["reasoning_effort"] = "none"
    p = subprocess.run(["curl", "-s", URL, "-X", "POST",
        "-H", "Content-Type: application/json",
        "-H", f"Authorization: Bearer {KEY}",
        "-H", "User-Agent: MasterOEbot-test/1.0",
        "-d", json.dumps(payload)],
        capture_output=True, text=True, timeout=90)
    try:
        body = json.loads(p.stdout)
        return body["choices"][0]["message"]["content"].strip()
    except Exception:
        return f"CALL-FAIL: {p.stdout[:200]}"

if __name__ == "__main__":
    mkey = sys.argv[1] if len(sys.argv) > 1 else "20b"
    model = MODELS[mkey]
    pfilter = [a for a in sys.argv[2:] if a.startswith("P")]
    cfilter = [a for a in sys.argv[2:] if a in CASES]
    for pname, system in PROMPTS.items():
        if pfilter and pname not in pfilter:
            continue
        for cname, history in CASES.items():
            if cfilter and cname not in cfilter:
                continue
            for run in range(2):
                print(f"=== {pname} | {mkey} | case{cname} | run{run} | newest: {history[-1]} ===",
                      flush=True)
                print(f"reply: {call(model, system, history)}", flush=True)
                print(flush=True)
                time.sleep(4)
