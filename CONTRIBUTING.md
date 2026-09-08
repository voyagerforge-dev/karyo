# Contributing to Karyo

Thank you for wanting to improve Karyo. This page explains exactly what this repository can and
cannot accept, so that nobody spends effort on work that cannot be merged here.

## This repository is generated, so pull requests are not accepted

Karyo's public tree is generated from VoyagerForge's private working repository by a fail-closed
publication gate. The first release has one root commit; each later release adds one generated commit
and matching tag without rewriting earlier history. Each release regenerates the source tree,
so changes made only in this repository would be overwritten by the next release. Pull requests
are therefore not accepted.

This is a distribution boundary, not a licence restriction. The source is
[Apache-2.0](LICENSE): you may fork this repository, modify it, run it, and redistribute your
fork under the terms of that licence.

## Issues are welcome

Open an issue for a reproducible bug, an incorrect or missing document, or a feature request.
A useful bug report carries the Karyo version, how the stack was deployed, the exact request or
screen involved, what you expected, what happened instead, and the relevant application log
lines with any credentials removed.

A feature request is most useful when it describes the warehouse operation you need to perform
and why the current behaviour blocks it, rather than a proposed implementation. Karyo's domain
behaviour is specified in `docs/functional/`, and a request that contradicts a specified
behaviour should say so explicitly.

## Extending Karyo without changing it

Most customisation does not need a change to this source. Karyo exposes service provider
interfaces, CDI event observers, strategy properties, and webhooks; an extension compiles against
the Apache-2.0 `*-api` modules only and ships as its own JAR. Start from
[the executable extension cookbook](docs/guides/implementer-guide.md#extend-the-free-application)
and its example under `services/inventory-service/karyo-inventory-ext-example/`. You can build
your own augmented free image without vendor approval. Commercial combined images are separate.

## Security problems do not belong in an issue

Report a suspected vulnerability privately, following [SECURITY.md](SECURITY.md). Do not open a
public issue for it.

## Commercial engines

The optional commercial engines are not part of this repository and cannot be contributed to
here. [PAID-MODULES.md](PAID-MODULES.md) records the boundary and the contact route.
