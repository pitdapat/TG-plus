# Agent instructions

## Building the app: one workflow, two modes

`.github/workflows/build-apk.yml` is the only build workflow. It runs only on
`workflow_dispatch` (pushing never starts a build) and builds the standalone ARM64 app
`org.patrick.tgplus.web` on GitHub, so no local Android toolchain is needed.

| | Test build (default) | Release build |
|---|---|---|
| Input | `publish=false` | `publish=true` |
| Output | workflow artifact: APK + `build-result.json` (14 days) | the same artifact, plus it replaces the rolling `build-<branch>` GitHub Release |
| Signing | permanent key if the secret works, else a throwaway key | permanent key required |
| API config, Firebase, certificate, package checks | warnings only | must all pass |
| Unit tests and compilation | must pass | must pass |
| Who starts it | an agent may start it whenever it needs to verify its work | only when the user asks |

Test and release builds run in separate concurrency groups, so a test build never
cancels or blocks a release build. A build takes about 3 to 7 minutes with a warm cache.

### How to run it

Always start it from `master` (`--ref master`) and put the code to build in `ref`:

```sh
# Test build (default). Never touches releases.
gh workflow run build-apk.yml --ref master -f ref=<branch, tag or SHA>

# Release build. Only when the user asks for one.
gh workflow run build-apk.yml --ref master -f ref=<branch> -f publish=true

# Follow it and fetch the result.
gh run list --workflow build-apk.yml --limit 1        # newest run id
gh run watch <run id> --exit-status                   # non-zero exit if the run failed
gh run download <run id>                              # APK + build-result.json
gh run view <run id> --log-failed                     # error output of failed steps
```

From the Actions tab: Build Telegram Plus APK > Run workflow, "Use workflow from" =
`master`, fill in `ref`, tick `publish` only for a release.

`build-result.json` holds `status`, `mode`, `ref`, `sha`, `version`, `signing`
(`permanent` or `throwaway`), `apk`, `apk_sha256`, `checks` (one result per check) and
`run_url`. It is uploaded even when the build fails, as long as the build got past
naming the APK. A throwaway-signed APK cannot update the installed app; uninstall it
first.

### Rules

- Do not add a `push`, `pull_request` or `schedule` trigger.
- "Use workflow from" picks the workflow **file**; `ref` picks the **code that is built**.
  Never run a feature branch's copy of `build-apk.yml`: copies on old branches are out of
  date. A blank `ref` builds the branch the run was started from.
- Change the workflow only through a PR into `master`. Do not edit it on a feature branch
  to get a build through (for example `if: false` on the publish job, or weakening a
  check). Use a test build instead.
- The build runs the built ref's own `tools/*.py` and `tests/`. A release build of a ref
  without `tools/prepare_signing.py`, `tools/verify_firebase_apk.py` and
  `tools/test_push_diagnostics.py` fails with a clear error. Merge `master` into it first.

### Always report errors back to the user

If anything goes wrong with a build, stop and tell the user. Do not silently retry, work
around it, or carry on as if it passed. This applies to a failed or cancelled run, to any
`fail:` entry in `build-result.json` `checks` (even though test builds only warn), to a
fallback to the throwaway key when the permanent key was expected, and to errors starting
or watching the run.

Report:

1. the run URL and whether it was a test or release build;
2. the ref and commit that was built;
3. the step that failed and the actual error lines (from `gh run view <id> --log-failed`
   or the `checks` entry), not a paraphrase;
4. your diagnosis of the cause, if you have one, and what you propose to do about it.

Then wait for the user before making workflow or signing changes or starting a release
build. Fixing an ordinary code error in your own change and running another test build
is fine, but still report the failure you hit.

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

## Keep master up to date

This is a solo project, so `master` is the one source of truth and must always hold the
latest finished work. A branch exists only for work in progress.

- Branch from the latest `master` (`git pull` first), never from another feature branch.
- When a feature is done, open a PR and merge it into `master` promptly. Do not leave
  finished work sitting on a branch.
- Right after merging, delete the branch on GitHub and locally, and delete its
  `build-<branch>` release and tag.
- Do not leave a branch that is fully merged ("0 ahead of master"). Delete it.
- While a branch is open, merge `master` into it regularly so it does not drift
  (a branch 70 commits behind is mostly conflicts).
- Do not commit directly to `master`. Use a branch and a PR so the build can be checked first.
- If unfinished work must be shelved, tag it (`git tag archive/<name> <branch>` and push the
  tag) and delete the branch, so nothing is lost and no stale branch remains.
- `master` must build. Run the local checks above before merging.

### Cleanup checklist after a merge

```sh
git push origin --delete <branch>
git branch -d <branch>
gh release delete build-<branch> --cleanup-tag --yes   # if a build of it was published
```

Merged branches also leave GitHub Actions caches behind (they count against the 10 GB
repository limit). List them with `gh api repos/pitdapat/TG-plus/actions/caches` and delete
the ones whose `ref` is a deleted branch.
