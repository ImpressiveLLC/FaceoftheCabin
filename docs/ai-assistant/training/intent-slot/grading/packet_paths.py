"""Read-only S4 layout checks; not authorization, access isolation or finalization.

Caller supplies roots already approved outside this module. lstat checks are a
snapshot, not a race-safe capability: the finalizer must revalidate/bind handles.
No content is opened and no directory, packet or recipient access is created.
"""
from dataclasses import dataclass
from pathlib import Path
import stat


@dataclass(frozen=True)
class CheckedPaths:
    source_files: tuple[Path, ...]
    coordinator_root: Path
    staging: Path
    destination: Path
    recipient_roots: tuple[Path, ...]


def _absolute(value):
    path = Path(value)
    if not path.is_absolute() or ".." in path.parts:
        raise ValueError("absolute paths without parent traversal required")
    return path


def _inspect(path, *, absent=False):
    """Check every existing component without following links or Windows reparse points."""
    for part in (*reversed(path.parents), path):
        try:
            info = part.lstat()
        except FileNotFoundError:
            if part == path and absent:
                return
            raise ValueError("path or parent does not exist") from None
        if (stat.S_ISLNK(info.st_mode)
                or getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)):
            raise ValueError("symlink or reparse point refused")
        if part != path and not stat.S_ISDIR(info.st_mode):
            raise ValueError("path ancestor is not a directory")
    if absent:
        raise ValueError("output already exists")
    return info


def _overlap(a, b):
    return a.is_relative_to(b) or b.is_relative_to(a)


def _outside_git(path):
    for ancestor in (path, *path.parents):
        try:
            (ancestor / ".git").lstat()
        except FileNotFoundError:
            continue
        raise ValueError("coordinator and recipient storage must be outside Git")


def preflight_paths(*, source_files, source_root, coordinator_root, staging,
                    destination, recipient_roots):
    """Return checked paths only; never interpret this result as permission to read/write.

    Roots must exist and be pairwise disjoint. Staging and destination must be
    distinct absent direct children of the coordinator root (same-volume layout).
    Recipient roots are structurally separate; their ACLs/actual identity access
    are NOT checked. Filesystem changes after return invalidate this snapshot.
    """
    source_root, coordinator_root = map(_absolute, (source_root, coordinator_root))
    recipients = tuple(map(_absolute, recipient_roots))
    sources = tuple(map(_absolute, source_files))
    staging, destination = map(_absolute, (staging, destination))
    if not sources or not recipients:
        raise ValueError("source files and recipient roots required")
    roots = (source_root, coordinator_root, *recipients)
    for index, root in enumerate(roots):
        if not stat.S_ISDIR(_inspect(root).st_mode):
            raise ValueError("approved root is not a directory")
        if any(_overlap(root, other) for other in roots[:index]):
            raise ValueError("approved roots overlap")
    for root in (coordinator_root, *recipients):
        _outside_git(root)
    identities = set()
    for source in sources:
        if source == source_root or not source.is_relative_to(source_root):
            raise ValueError("source outside approved source root")
        info = _inspect(source)
        if not stat.S_ISREG(info.st_mode):
            raise ValueError("source is not a regular file")
        identity = (info.st_dev, info.st_ino)
        if identity in identities:
            raise ValueError("duplicate or hard-linked source")
        identities.add(identity)
    if staging == destination:
        raise ValueError("staging and destination must differ")
    for output in (staging, destination):
        if output.parent != coordinator_root:
            raise ValueError("outputs must be direct children of coordinator root")
        _inspect(output, absent=True)
    return CheckedPaths(sources, coordinator_root, staging, destination, recipients)
