"""Small helpers shared by the patch modules. Every edit is idempotent and fails loudly."""
import pathlib
import sys


def die(msg):
    print(f"PATCH FAILED: {msg}", file=sys.stderr)
    sys.exit(1)


def edit(path, anchor, replacement, marker=None, count=1, gone_ok=False):
    """Replace `anchor` (must occur exactly `count` times) by `replacement`.
    marker: text that exists only after patching (idempotency). gone_ok: for deletions, a missing anchor means done."""
    if gone_ok and not path.exists():
        print(f"  nothing to do: {path.name} does not exist")
        return
    text = path.read_text(encoding="utf-8")
    if marker is not None and marker in text:
        print(f"  already patched: {path.name}")
        return
    found = text.count(anchor)
    if found == 0 and gone_ok:
        print(f"  nothing to do in {path.name} (already gone)")
        return
    if found != count:
        die(f"anchor found {found}x (expected {count}x) in {path}:\n---\n{anchor}\n---")
    path.write_text(text.replace(anchor, replacement), encoding="utf-8")
    print(f"  patched: {path.name}")


def cut_between(path, start, end):
    """Delete text from `start` up to (not including) `end`."""
    if not path.exists():
        print(f"  nothing to cut: {path.name} does not exist")
        return
    text = path.read_text(encoding="utf-8")
    i = text.find(start)
    if i < 0:
        print(f"  nothing to cut in {path.name}")
        return
    j = text.find(end, i)
    if j < 0:
        die(f"end marker not found after start marker in {path}: {end!r}")
    path.write_text(text[:i] + text[j:], encoding="utf-8")
    print(f"  cut section in {path.name}")


def delete(path):
    if path.is_dir():
        import shutil
        shutil.rmtree(path)
        print(f"  deleted dir {path.name}/")
    elif path.exists():
        path.unlink()
        print(f"  deleted {path.name}")
    else:
        print(f"  already gone: {path.name}")
