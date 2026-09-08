#!/usr/bin/env python3
"""Validate privileged deployment credentials."""

from __future__ import annotations

import argparse
import re
import sys

COMMON_WORDS = {
    "admin",
    "administrator",
    "changeme",
    "letmein",
    "manager",
    "operator",
    "password",
    "qwerty",
    "secret",
    "viewer",
    "welcome",
}
PREDICTABLE_SEQUENCES = (
    "0123456789",
    "9876543210",
    "abcdefghijklmnopqrstuvwxyz",
    "zyxwvutsrqponmlkjihgfedcba",
    "qwertyuiop",
    "poiuytrewq",
    "asdfghjkl",
    "lkjhgfdsa",
    "zxcvbnm",
    "mnbvcxz",
)


class CredentialPolicyError(ValueError):
    pass


def validate_env_literal(value: str) -> None:
    if "CHANGE_ME" in value:
        raise CredentialPolicyError("must not contain the CHANGE_ME placeholder")
    if any(character.isspace() for character in value) or any(
        character in value for character in ('"', "'", "#", "$", "\\")
    ):
        raise CredentialPolicyError(
            "must use one unquoted environment literal without whitespace, comments, "
            "interpolation, or escapes"
        )


def is_repeated(value: str) -> bool:
    return any(
        len(value) % width == 0 and value == value[:width] * (len(value) // width)
        for width in range(1, len(value) // 2 + 1)
    )


def is_predictable_sequence(value: str) -> bool:
    if not value:
        return False
    for sequence in PREDICTABLE_SEQUENCES:
        cycles = sequence * (len(value) // len(sequence) + 3)
        if any(cycles[offset:offset + len(value)] == value for offset in range(len(sequence))):
            return True
        if len(value) >= 8 and any(
            cycles[offset:offset + 8] in value for offset in range(len(sequence))
        ):
            return True
    return False


def common_word_coverage(value: str) -> int:
    coverage = [0] * (len(value) + 1)
    for start in range(len(value)):
        coverage[start + 1] = max(coverage[start + 1], coverage[start])
        for word in COMMON_WORDS:
            if value.startswith(word, start):
                end = start + len(word)
                coverage[end] = max(coverage[end], coverage[start] + len(word))
    return coverage[-1]


def is_predictable_dictionary(value: str) -> bool:
    reachable = {0}
    for start in range(len(value)):
        if start not in reachable:
            continue
        for word in COMMON_WORDS:
            if value.startswith(word, start):
                reachable.add(start + len(word))
    predictable_prefix = any(
        end > 0
        and (
            end == len(value)
            or value[end:].isdigit()
            or is_repeated(value[end:])
            or is_predictable_sequence(value[end:])
        )
        for end in reachable
    )
    return predictable_prefix or (
        bool(value) and common_word_coverage(value) * 2 >= len(value)
    )


def normalized_secret(value: str) -> str:
    return re.sub(r"[^a-z0-9]", "", value.casefold())


def is_trivial_remainder(value: str) -> bool:
    return not value or value.isdigit() or is_repeated(value) or is_predictable_sequence(value)


def is_username_derived(value: str, username: str) -> bool:
    user = username.strip()
    if not user:
        return True
    password_folded = value.casefold()
    user_folded = user.casefold()
    password_norm = normalized_secret(value)
    user_norm = normalized_secret(user)
    if not user_norm:
        return False
    if password_folded == user_folded or password_norm == user_norm:
        return True
    if password_folded == user_folded[::-1] or password_norm == user_norm[::-1]:
        return True
    if len(user_norm) >= 3 and len(password_norm) >= len(user_norm) * 2:
        copies = len(password_norm) // len(user_norm)
        if password_norm == user_norm * copies and len(password_norm) % len(user_norm) == 0:
            return True
    if len(user_norm) >= 4:
        if password_norm.startswith(user_norm) and is_trivial_remainder(password_norm[len(user_norm):]):
            return True
        if password_norm.endswith(user_norm) and is_trivial_remainder(password_norm[:-len(user_norm)]):
            return True
    return False


def validate_credential(
    value: str,
    min_length: int,
    username: str,
    require_env_literal: bool = False,
) -> None:
    if require_env_literal:
        validate_env_literal(value)
    if len(value) < min_length:
        raise CredentialPolicyError(f"must be at least {min_length} characters")
    if is_username_derived(value, username):
        raise CredentialPolicyError("must not match or be derived from its username")

    normalized = normalized_secret(value)
    weak = (
        is_repeated(value.casefold())
        or is_repeated(normalized)
        or is_predictable_sequence(normalized)
        or is_predictable_dictionary(normalized)
        or re.search(r"(.)\1{3,}", value.casefold()) is not None
        or len(set(value)) < 4
    )
    if weak:
        raise CredentialPolicyError("must not be a dictionary, predictable, or repeated value")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-length", type=int, required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--env-literal", action="store_true")
    args = parser.parse_args(argv)
    value = sys.stdin.read().rstrip("\n")
    try:
        validate_credential(
            value,
            args.min_length,
            args.username,
            require_env_literal=args.env_literal,
        )
    except CredentialPolicyError as exc:
        print(f"invalid credential: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
