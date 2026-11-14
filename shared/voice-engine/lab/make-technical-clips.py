#!/usr/bin/env python3
"""Writes the bundled technical-English test clips (adapter/android/src/main/assets/lab/): WAVs spoken by macOS
text-to-speech voices, 16 kHz mono, and clips.json with the text a good transcript would have. macOS only.

  python3 make-technical-clips.py
"""
import json
import os
import subprocess
import wave

os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../adapter/android/src/main/assets/lab"))
# (id, voice, spoken text for TTS, reference text as a good transcript would write it)
clips = [
 ("hook", "Samantha", "Refactor the use effect hook in app dot T S X so the fetch call is debounced by three hundred milliseconds.",
  "Refactor the useEffect hook in App.tsx so the fetch call is debounced by 300 milliseconds."),
 ("tests", "Daniel", "Run the unit tests with P N P M test and show me any failures in the auth module.",
  "Run the unit tests with pnpm test and show me any failures in the auth module."),
 ("migration", "Karen", "Add a nullable timestamp column called archived at to the sessions table, and write the migration.",
  "Add a nullable timestamp column called archived at to the sessions table, and write the migration."),
 ("coroutine", "Moira", "Why does the Kotlin coroutine leak when the view model is cleared? Check the view model scope usage.",
  "Why does the Kotlin coroutine leak when the ViewModel is cleared? Check the viewModelScope usage."),
 ("graphql", "Reed (English (US))", "Rename the GraphQL resolver get user by I D to fetch user, and update every call site.",
  "Rename the GraphQL resolver getUserById to fetchUser, and update every call site."),
 ("pr", "Aman", "Open a pull request against main with a short description, and request a review from the backend team.",
  "Open a pull request against main with a short description, and request a review from the backend team."),
 ("docker", "Samantha", "The Docker build fails on the arm sixty four runner because the base image only ships A M D sixty four binaries.",
  "The Docker build fails on the arm64 runner because the base image only ships amd64 binaries."),
 ("redis", "Daniel", "Set the Redis cache T T L to fifteen minutes, and log a warning when the hit rate drops below eighty percent.",
  "Set the Redis cache TTL to 15 minutes, and log a warning when the hit rate drops below 80 percent."),
 ("search", "Karen", "Use a binary search instead of the linear scan in find index, and keep the function signature the same.",
  "Use a binary search instead of the linear scan in findIndex, and keep the function signature the same."),
 ("ios", "Moira", "Bump the I O S deployment target to eighteen, and fix the deprecation warnings in Swift U I.",
  "Bump the iOS deployment target to 18, and fix the deprecation warnings in SwiftUI."),
 ("webpack", "Reed (English (US))", "Stop. Undo the last change to the webpack config, and explain what the split chunks option does.",
  "Stop. Undo the last change to the webpack config, and explain what the splitChunks option does."),
 ("long", "Samantha",
  "Okay, here's the plan. First, read the README and the contributing guide. Then look at how the session store is wired into the API client, "
  "and find out why the websocket reconnects every thirty seconds. If it's the keep alive timeout, raise it to two minutes and add a test. "
  "Don't touch the database schema, and don't push anything until I've had a look.",
  "Okay, here's the plan. First, read the README and the contributing guide. Then look at how the session store is wired into the API client, "
  "and find out why the WebSocket reconnects every 30 seconds. If it's the keep-alive timeout, raise it to 2 minutes and add a test. "
  "Don't touch the database schema, and don't push anything until I've had a look."),
]
out = []
for cid, voice, spoken, ref in clips:
    subprocess.run(["say", "-v", voice, "-r", "185", "-o", f"{cid}.wav", "--data-format=LEI16@16000", spoken], check=True)
    with wave.open(f"{cid}.wav") as w:
        secs = w.getnframes() / w.getframerate()
    out.append({"id": cid, "file": f"{cid}.wav", "seconds": round(secs, 2), "voice": voice, "reference": ref})
    print(cid, round(secs, 1))
json.dump({"note": "Technical English test clips, spoken by macOS text-to-speech voices (clean audio, not real speech).", "clips": out},
          open("clips.json", "w"), indent=2)
