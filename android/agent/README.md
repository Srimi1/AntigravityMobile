# Antigravity Agent (standalone)

A coding agent that runs on the phone by itself. No PC needed.

- Talks to the **Gemini API** with your own key (free key from https://aistudio.google.com/apikey).
  A Google AI Pro plan does not include API access.
- Works in a private workspace folder inside the app. Tools: list, read, write, edit, grep, shell, web fetch.
- Shell commands need your approval unless you turn on auto-approve in Settings.
- The shell is Android's built-in toybox `sh`. Python, Node and git are not available yet (planned: bundled Linux userland).

Layout: `agent-core/` is plain Kotlin (Gemini client, agent loop, tools, tests). `agent/` is the Android UI.

Build: GitHub Actions workflow "Build Android APKs" (artifact `AntigravityAgent-apk`).
Run core tests locally: `./gradlew :agent-core:test`.
