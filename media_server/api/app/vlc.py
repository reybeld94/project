import os
import shutil
import subprocess


def _resolve_vlc_binary() -> str | None:
    override = (os.getenv("VLC_BIN") or "").strip()
    if override:
        if os.path.isabs(override) and os.path.exists(override):
            return override
        resolved = shutil.which(override)
        if resolved:
            return resolved

    return shutil.which("vlc") or shutil.which("cvlc")


def launch_vlc(url: str) -> bool:
    vlc_bin = _resolve_vlc_binary()
    if not vlc_bin:
        return False

    subprocess.Popen(
        [vlc_bin, "--play-and-exit", url],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    return True
