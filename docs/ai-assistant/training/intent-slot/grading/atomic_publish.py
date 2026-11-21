"""Linux-only atomic no-replace directory publication, not the full S4 finalizer.

Requires a sole coordinator writer and an already verified private staging tree.
This does not validate contents, grant authority/access or guarantee crash durability.
Unsupported platforms/filesystems fail closed; never substitute copy/delete/rename.
"""
import ctypes
import os
import re
import stat
import sys
from .packet_paths import _absolute, _inspect, _outside_git


def _rename_noreplace(parent_fd, source_name, destination_name):
    try:
        rename = ctypes.CDLL(None, use_errno=True).renameat2
    except (AttributeError, OSError) as exc:
        raise NotImplementedError("renameat2 unavailable; publication refused") from exc
    rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    rename.restype = ctypes.c_int
    # Linux RENAME_NOREPLACE = 1. EEXIST, ENOSYS, EINVAL, etc. propagate; no fallback.
    if rename(parent_fd, os.fsencode(source_name), parent_fd, os.fsencode(destination_name), 1) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error), destination_name)


def _private_directory(info):
    if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid()
            or info.st_mode & 0o077):
        raise ValueError("directory must be owned by coordinator with no group/other access")


def publish_directory(staging, destination):
    """Atomically move verified staging to an absent sibling, never replacing a winner.

    Names must be distinct 32-character lowercase hex opaque IDs. Parent and staging
    must be private coordinator-owned directories. Parent is held open throughout;
    staging identity is rechecked relative to it. The sole-writer prerequisite is
    essential: this does not defeat another process with the coordinator identity.
    Success returns the destination path. No automatic cleanup or retry on failure.
    """
    if not sys.platform.startswith("linux"):
        raise NotImplementedError("atomic publication requires the reviewed Linux backend")
    staging, destination = map(_absolute, (staging, destination))
    if staging.parent != destination.parent or staging == destination:
        raise ValueError("publication needs distinct siblings")
    for path in (staging, destination):
        if not re.fullmatch(r"[0-9a-f]{32}", path.name):
            raise ValueError("publication names must be opaque lowercase hex IDs")
    parent = staging.parent
    parent_info = _inspect(parent)
    source_info = _inspect(staging)
    _private_directory(parent_info)
    _private_directory(source_info)
    _outside_git(parent)
    # Opening the checked parent binds both names to one directory even if its
    # pathname later moves. O_NOFOLLOW refuses a last-component link substitution.
    fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        held = os.fstat(fd)
        _private_directory(held)
        if (held.st_dev, held.st_ino) != (parent_info.st_dev, parent_info.st_ino):
            raise ValueError("coordinator directory changed during preflight")
        source = os.stat(staging.name, dir_fd=fd, follow_symlinks=False)
        _private_directory(source)
        if (source.st_dev, source.st_ino) != (source_info.st_dev, source_info.st_ino):
            raise ValueError("staging directory changed during preflight")
        _rename_noreplace(fd, staging.name, destination.name)
    finally:
        os.close(fd)
    return destination
