#!/usr/bin/env python3
"""Render least-privilege environment files for production Compose services."""

from __future__ import annotations

import os
import sys
from pathlib import Path

SERVICE_SUFFIXES = ("postgresql", "keycloak", "app", "nginx")


def read_assignments(source: Path) -> dict[str, str]:
    assignments: dict[str, str] = {}
    for raw_line in source.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        name, separator, value = raw_line.partition("=")
        if not separator or not name:
            raise ValueError(f"invalid environment assignment: {raw_line}")
        assignments[name] = value
    return assignments


def service_assignments(values: dict[str, str]) -> dict[str, dict[str, str]]:
    postgres = {name: value for name, value in values.items() if name.startswith("POSTGRES_")}
    keycloak_names = {
        "KC_BOOTSTRAP_ADMIN_USERNAME",
        "KC_BOOTSTRAP_ADMIN_PASSWORD",
        "KARYO_PUBLIC_ORIGIN",
        "KEYCLOAK_ADMIN_CLIENT_SECRET",
        "OIDC_SECRET",
    }
    keycloak = {
        name: value
        for name, value in values.items()
        if name.startswith("KC_") or name in keycloak_names
    }
    app = {
        name: value
        for name, value in values.items()
        if not name.startswith("KC_")
        and not name.startswith("POSTGRES_")
        and name not in {"KARYO_PUBLIC_ORIGIN", "KEYCLOAK_URL"}
    }
    nginx = {name: values[name] for name in ("KEYCLOAK_URL",) if name in values}
    return {"postgresql": postgres, "keycloak": keycloak, "app": app, "nginx": nginx}


def write_private(path: Path, values: dict[str, str]) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    payload = "".join(f"{name}={value}\n" for name, value in values.items())
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    try:
        fd = os.open(temporary, flags, 0o600)
    except FileExistsError:
        os.unlink(temporary)
        fd = os.open(temporary, flags, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(payload)
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def render(source: Path) -> list[Path]:
    values = read_assignments(source)
    rendered = service_assignments(values)
    outputs = []
    for suffix in SERVICE_SUFFIXES:
        output = source.with_name(f"{source.name}.{suffix}")
        write_private(output, rendered[suffix])
        outputs.append(output)
    return outputs


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("usage: render_compose_env.py ENV_FILE", file=sys.stderr)
        return 2
    source = Path(argv[0])
    try:
        outputs = render(source)
    except (OSError, ValueError) as exc:
        print(f"failed to render Compose environment: {exc}", file=sys.stderr)
        return 1
    for output in outputs:
        print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
