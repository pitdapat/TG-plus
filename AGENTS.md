# Agent instructions

## GitHub builds are manual

Pushing does **not** start a build. `.github/workflows/build-apk.yml` runs only on
`workflow_dispatch`, so commits and pushes while a feature is in progress cost nothing.
We iterate until the features are done and then run the full build once.

- Do not start a build unless the user asks for one.
- Do not add a `push`, `pull_request` or `schedule` trigger to the workflow.
- To build when asked:
  - Actions tab > Build Telegram Plus APK > Run workflow, or
  - `gh workflow run build-apk.yml --ref master -f ref=<branch, tag or SHA>`
- `ref` is the code that gets built. Blank means the branch the run was started from.
  The workflow file is read from the branch chosen in "Use workflow from" (use `master`),
  so the target branch does not need its own copy.
- A build takes about 6 to 7 minutes and publishes a rolling prerelease tagged
  `build-<branch>` (the default branch gets the "latest" release).

## Check locally before asking for a build

These are the same checks the workflow runs first and need only a JDK and Python:

```sh
out=$(mktemp -d)
javac -d "$out" -sourcepath TMessagesProj/src/main/java tests/telegram-plus/*Test.java
for src in tests/telegram-plus/*Test.java; do java -cp "$out" "$(basename "$src" .java)"; done
python3 tools/test_push_diagnostics.py
```

New tests go in `tests/telegram-plus/<Name>Test.java` (default package, with `main`).
The workflow picks up every `*Test.java` there, so no list needs editing.

## Release format (do not change without updating the in-app updater)

The updater in `org.telegram.messenger.tgplus.UpdatePolicy` parses the published release:

- tag: `build-<branch>` (one rolling release per branch)
- asset: `TelegramPlus-<version>-<branch>-build<run number>-<sha7>.apk`
