#!/usr/bin/env python3
"""Write a release's notes into the app AND into the GitHub release body, from one input.

The release notes a user reads have to show up in three places, and they must agree:

  1. the Updates inbox row  (Today → bell)          — headline only
  2. Settings → About → "What's new"                — headline + every bullet
  3. "Check for updates" when a new version is found — fetched live from the release body

(1) and (2) are rendered from `AppChangelog.kt`, which is COMPILED INTO the APK — so the notes must be
written before the APK is built. (3) is read at runtime from the GitHub release body. This script is
the single step that produces both, so they can never drift apart.

It is called by .github/workflows/android-release.yml with a title and bullets derived from the PR
(or, failing that, the commits since the last tag), and it:

  * rewrites `AppChangelog.CURRENT_VERSION` to the version being released — that is what makes the app
    post a fresh "what's new" row to the Updates inbox once, on first launch after the update
    (`UpdateStore.seedWhatsNewIfNeeded`);
  * inserts a `Release(...)` entry at the top of `AppChangelog.releases`;
  * emits the same content as markdown for `gh release create --notes-file`.

Idempotent: re-running for a version already present in the changelog updates CURRENT_VERSION but does
not add a second entry, so a re-run of a build can't duplicate a release.

Usage:
    printf '%s\n' "bullet one" "bullet two" \
      | release-notes.py --version 8.3.0 --title "Direct updates" \
          --changelog app/src/main/java/com/noop/ui/AppChangelog.kt --out-md notes.md
"""

from __future__ import annotations

import argparse
import datetime as _dt
import re
import sys

# The anchor we insert after — the first line of the changelog's release list.
LIST_ANCHOR = "val releases: List<Release> = listOf("
VERSION_RE = re.compile(r'(const val CURRENT_VERSION = ")([^"]*)(")')


def kotlin_escape(s: str) -> str:
    """Escape a Python string so it is a valid Kotlin string-literal body.

    Order matters: backslashes first, or we would re-escape the escapes we just added. `$` must be
    escaped too — Kotlin would otherwise read `$foo` as a string template and fail to compile.
    """
    return (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
    )


def build_entry(version: str, title: str, date: str, items: list[str]) -> str:
    """Render the Kotlin `Release(...)` literal, indented to match the surrounding list."""
    lines = [
        "        Release(",
        f'            version = "{kotlin_escape(version)}",',
        f'            title = "{kotlin_escape(title)}",',
        f'            date = "{kotlin_escape(date)}",',
        "            items = listOf(",
    ]
    for item in items:
        lines.append(f'                "{kotlin_escape(item)}",')
    lines += ["            ),", "        ),"]
    return "\n".join(lines)


def build_markdown(version: str, title: str, items: list[str]) -> str:
    """The GitHub release body. The app's `UpdateCheck.cleanNotes` strips `**`/`#` and cuts at
    'Downloads', so this renders cleanly both on github.com and inside the update card."""
    out = [f"## {title}", ""]
    out += [f"- {item}" for item in items]
    out += [
        "",
        f"Choop {version}.",
    ]
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True, help="version being released, no leading v")
    ap.add_argument("--title", required=True, help="one-line headline (the inbox row's title)")
    ap.add_argument("--changelog", required=True, help="path to AppChangelog.kt")
    ap.add_argument("--date", default=None, help='display date, e.g. "July 2026"')
    ap.add_argument("--out-md", default=None, help="write the release body markdown here")
    args = ap.parse_args()

    date = args.date or _dt.date.today().strftime("%B %Y")
    items = [ln.strip() for ln in sys.stdin.read().splitlines() if ln.strip()]
    if not items:
        # Never leave the entry bullet-less: the What's New card would render an empty release.
        items = ["Maintenance and internal improvements."]

    src = open(args.changelog, encoding="utf-8").read()

    if not VERSION_RE.search(src):
        print(f"error: CURRENT_VERSION not found in {args.changelog}", file=sys.stderr)
        return 1
    if LIST_ANCHOR not in src:
        print(f"error: release list anchor not found in {args.changelog}", file=sys.stderr)
        return 1

    src = VERSION_RE.sub(lambda m: m.group(1) + args.version + m.group(3), src, count=1)

    # Only add the entry when this version isn't already in the list (re-run safety).
    already = re.search(r'version = "%s"' % re.escape(args.version), src)
    if not already:
        entry = build_entry(args.version, args.title, date, items)
        src = src.replace(LIST_ANCHOR, LIST_ANCHOR + "\n" + entry, 1)

    open(args.changelog, "w", encoding="utf-8").write(src)

    md = build_markdown(args.version, args.title, items)
    if args.out_md:
        open(args.out_md, "w", encoding="utf-8").write(md)
    else:
        sys.stdout.write(md)

    print(
        f"release-notes: version={args.version} entry={'existing' if already else 'added'} "
        f"items={len(items)}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
