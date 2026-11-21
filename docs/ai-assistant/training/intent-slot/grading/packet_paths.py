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
    if any(part.endswith((".", " ")) for part in path.parts):
        raise ValueError("trailing dot or space in path component refused")
    return path


def _inspect(path, *, absent=False, directory_ids=None):
    """Check every existing component without following links or Windows reparse points."""
    for part in (*reversed(path.parents), path):
        try:
            info = part.lstat()
        except FileNotFoundError:
            if part == path and absent:
                return
            raise ValueError("path or parent does not exist") from None
        except OSError as exc:
            raise ValueError("cannot inspect path metadata") from exc
        if (stat.S_ISLNK(info.st_mode)
                or getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)):
            raise ValueError("symlink or reparse point refused")
        if part != path and not stat.S_ISDIR(info.st_mode):
            raise ValueError("path ancestor is not a directory")
        if directory_ids is not None and stat.S_ISDIR(info.st_mode):
            directory_ids.add((info.st_dev, info.st_ino))
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
        except OSError as exc:
            raise ValueError("cannot inspect Git marker") from exc
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
    checked_roots = []
    for index, root in enumerate(roots):
        ancestors = set()
        info = _inspect(root, directory_ids=ancestors)
        if not stat.S_ISDIR(info.st_mode):
            raise ValueError("approved root is not a directory")
        identity = (info.st_dev, info.st_ino)
        if (any(_overlap(root, other) for other in roots[:index])
                or any(identity in previous_ancestors or previous_id in ancestors
                       for previous_id, previous_ancestors in checked_roots)):
            raise ValueError("approved roots overlap or alias")
        checked_roots.append((identity, ancestors))
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
    for output in (staging, destination):
        if output.parent != coordinator_root:
            raise ValueError("outputs must be direct children of coordinator root")
        if output.name.casefold() == ".git":
            raise ValueError("Git marker output name refused")
        _inspect(output, absent=True)
    # Both absent outputs have the same checked parent. Conservative case folding
    # also refuses names that would alias on a case-insensitive filesystem.
    if staging.name.casefold() == destination.name.casefold():
        raise ValueError("staging and destination must differ")
    return CheckedPaths(sources, coordinator_root, staging, destination, recipients)
