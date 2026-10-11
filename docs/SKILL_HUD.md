# Skill HUD setup

This is a first-stage implementation. Fourteen sword IDs have been registered.
Five skills (sword6 and sword7) use user-supplied names and cooldowns, with
provisional actionbar triggers based on literal skill names. The debug command
collects the actual actionbar strings to validate those triggers.

## Commands

- /sh debug : toggle actionbar diagnostics (chat plus logs/latest.log)
- /sh test sword7 sl : manually start the registered skill's cooldown timer.
  This does NOT activate the server skill; enable HUD visibility separately if off.
- /sh sword1 all toggle : enable/disable l, sl, sr, ss for sword1 as a group
- /sh sword1 sl toggle : toggle just the Shift+left skill
- /sh sword1 l toggle : toggle just the normal left skill
- /sh sword1 sr toggle : toggle just the Shift+right skill
- /sh sword1 ss toggle : toggle just the double-Shift skill
- /sh sword1 r toggle : toggle global r (the shared leap skill)
- /sh global r toggle : same shared right-click leap
- /sh toggle : enable/disable all skill HUD rendering
- /sh size 1.5 : scale text from 0.5 to 5
- /sh list : list actual registered skills and visibility
- /sh reset : clear all selected HUD slots
- /hud : drag the skill HUD along with existing HUDs

All commands except debug/list are silent on success. They work even if a sword
has no skill catalog entry yet; selection preferences survive future updates.

## Development catalog

File: src/main/resources/assets/untitled/skill_catalog.json
This is bundled into the mod JAR. Currently defined:
- sword6 (타이달리스): sl 창람 8s, sr 파참 12s
- sword7 (츠바이핸더): sl 섬멸 9s, sr 철벽돌파 10s, ss 폭풍참격 11s
The triggers currently equal the literal skill names. If the server sends
resource-pack-specific glyphs instead, automatic detection will NOT work until
/sh debug reveals suitable raw text to use as triggers.
User visibility and HUD position are saved independently in config/152_hud.json.

Example only, NOT verified server data:

{
  "global": {
    "r": { "name": "도약", "trigger": "actual actionbar text", "cooldown": 15 }
  },
  "swords": [{
    "id": "sword1",
    "item_name": "actual displayed sword name",
    "skills": {
      "sl": { "name": "폭풍참격", "trigger": "actual trigger", "cooldown": 20 }
    }
  }]
}

Skill slots: l, sl, sr, ss. The r slot is always global.
The game item display name must exactly match item_name. The trigger is an
exact actionbar substring. Duplicate success messages do not restart an
already active timer. A message containing the trigger plus e.g. 5초 or 5s
corrects remaining time. Numeric-only messages cannot be assigned to a skill
without additional context; do not infer which cooldown they belong to.

## Next verification steps

1. Run /sh debug, use one skill successfully, copy actionbar raw/unicode.
2. Retry that skill during cooldown; copy the remaining-time actionbar.
3. Repeat with the other skills. Include exact weapon display name and
   duration per skill. Private-use Unicode glyphs are printed as \u{XXXX}.
4. Populate catalog, rebuild, and enable selected slots.

Timers do not run for unknown/unregistered skills, and no unknown durations
have been invented. The /sh test command starts a timer for a registered skill
only. Timer state resets on disconnect, and selection
preferences remain saved. Debug can be noisy; turn it off after sampling.
