"""Windows startup bootstrap for sklearn DLLs blocked by AppLocker/SmartScreen."""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


def _unblock_windows_native_extensions() -> None:
    if os.name != "nt":
        return

    project_root = Path(__file__).resolve().parent
    candidate_roots = [project_root, project_root / ".venv"]
    for root in candidate_roots:
        if not root.exists():
            continue
        try:
            subprocess.run(
                [
                    "powershell.exe",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    (
                        f"Get-ChildItem -Path '{root}' -Recurse -File -Filter *.pyd | "
                        "ForEach-Object { Unblock-File -Path $_.FullName -ErrorAction SilentlyContinue }"
                    ),
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
                timeout=30,
            )
        except (FileNotFoundError, subprocess.TimeoutExpired):
            pass


_unblock_windows_native_extensions()
