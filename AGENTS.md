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
- A build takes about 3 to 7 minutes, depending on cache hits, and publishes a rolling
  prerelease tagged `build-<branch>` (the default branch gets the "latest" release).

### Always run the workflow from `master`

Always start the build with **"Use workflow from" = `master`** (`--ref master` in the
command). Never use a feature branch's copy of `build-apk.yml`: copies on old branches
are out of date and have different steps. `master` is the single source of truth, and the
branch being built does not need its own copy.

- "Use workflow from" picks the workflow **file**. The `ref` input picks the **code that is built**.
- Always fill in `ref` with the branch to build. If `ref` is blank, the build uses the
  branch the run was started from, so a blank `ref` on `master` builds `master`.
- Change the workflow only through `master`. Do not edit `build-apk.yml` on feature branches.
- The build runs the built branch's own `tools/*.py` and `tests/`. A branch without
  `tools/prepare_signing.py`, `tools/verify_firebase_apk.py` and
  `tools/test_push_diagnostics.py` fails with a clear error. Merge `master` into it first.

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

## Do not push while a build is running

Publishing creates a git tag with the repo's `GITHUB_TOKEN`. GitHub rejects that
(`HTTP 403: Resource not accessible by integration` on `gh release create`) when the built
commit's `.github/workflows/` differs from the latest commit of every branch. If a push
changes the workflow files while a build runs, the build job passes but the publish job
fails, and re-running it fails the same way.

- Build a branch tip (leave `ref` blank or give a branch name), not an older SHA.
- Do not push workflow changes to that branch until the run has finished.
- If it happened anyway, start a new run on the current tip.
