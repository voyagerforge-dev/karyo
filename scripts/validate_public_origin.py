#!/usr/bin/env python3
"""Validate Karyo's deployment-bound public browser origin."""

from __future__ import annotations

import shutil
import subprocess
import sys
from urllib.parse import urlsplit

WHATWG_ORIGIN_PROGRAM = r"""
const input = process.argv[1];
try {
  const parsed = new URL(input);
  if (
    (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') ||
    parsed.username !== '' ||
    parsed.password !== '' ||
    parsed.hostname === '' ||
    parsed.port === '0' ||
    input.includes('*')
  ) {
    process.exit(2);
  }
  process.stdout.write(parsed.origin);
} catch {
  process.exit(2);
}
"""


class PublicOriginError(ValueError):
    pass


class ToolchainError(PublicOriginError):
    """The validator could not run, as distinct from the origin being wrong.

    Kept a subclass so existing callers that catch PublicOriginError still catch it, but a
    distinct type so a caller can report a missing interpreter as a missing interpreter instead
    of filing it under the operator's configuration. Blaming an operator's env file for an
    absent Node install sends them to debug the wrong thing.
    """


def normalize_public_origin(origin: str) -> str:
    if not origin or origin != origin.strip() or any(char.isspace() for char in origin):
        raise PublicOriginError("origin must be non-empty and contain no whitespace")

    node = shutil.which("node")
    if node is None:
        raise ToolchainError("Node.js is required for browser-equivalent URL validation")
    try:
        result = subprocess.run(
            [node, "-e", WHATWG_ORIGIN_PROGRAM, origin],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ToolchainError("browser-equivalent URL validation failed") from exc
    if result.returncode != 0 or not result.stdout:
        raise PublicOriginError("origin must be one exact HTTP(S) browser origin")

    normalized = result.stdout
    if origin != normalized:
        raise PublicOriginError("origin must use its browser-canonical URL.origin form")
    return normalized


def validate_deployment_urls(
    origin: str,
    domain: str,
    keycloak_hostname: str,
    keycloak_url: str,
) -> str:
    normalized = normalize_public_origin(origin)
    parsed = urlsplit(normalized)
    expected_domain = parsed.hostname or ""
    if domain != expected_domain:
        raise PublicOriginError("KARYO_DOMAIN must equal the public origin hostname")
    expected_keycloak = f"{normalized}/auth"
    if keycloak_hostname != expected_keycloak:
        raise PublicOriginError("KC_HOSTNAME must equal KARYO_PUBLIC_ORIGIN plus /auth")
    if keycloak_url != "/auth":
        if normalize_public_origin(keycloak_url.removesuffix("/auth")) + "/auth" != keycloak_url:
            raise PublicOriginError("absolute KEYCLOAK_URL must equal KARYO_PUBLIC_ORIGIN plus /auth")
        if keycloak_url != expected_keycloak:
            raise PublicOriginError("absolute KEYCLOAK_URL must use the public origin")
    return normalized


def main(argv: list[str]) -> int:
    if len(argv) not in {2, 5}:
        print(
            "usage: validate_public_origin.py ORIGIN [KARYO_DOMAIN KC_HOSTNAME KEYCLOAK_URL]",
            file=sys.stderr,
        )
        return 2
    try:
        normalized = (
            normalize_public_origin(argv[1])
            if len(argv) == 2
            else validate_deployment_urls(argv[1], argv[2], argv[3], argv[4])
        )
        print(normalized)
    except ToolchainError as exc:
        # Exit 3, distinct from 2, so a caller reports a missing tool as a missing tool rather
        # than folding it into "your configuration is wrong".
        print(f"toolchain unavailable: {exc}", file=sys.stderr)
        return 3
    except PublicOriginError as exc:
        print(f"invalid public URL configuration: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
