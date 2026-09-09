# Pull requests are not accepted in this repository

Thank you for wanting to improve Karyo. This repository cannot merge your change, and
that is a distribution boundary rather than a judgement about the change itself.

Karyo's public tree is **generated** from VoyagerForge's private working repository by a
fail-closed publication gate. Every release regenerates the source tree, so a commit made
only here would be discarded by the next release. Nothing is lost by closing this pull
request, and nothing would be preserved by merging it.

## What to do instead

- **Found a bug, a documentation defect, or a missing capability?**
  [Open an issue](https://github.com/voyagerforge-dev/karyo/issues). Issues are read, and
  fixes land in the private tree and reach you in the next release.
- **Need Karyo to behave differently for your warehouse?** Most customisation needs no
  change to this source. Karyo exposes service provider interfaces, CDI event observers,
  strategy properties and webhooks; an extension compiles against the Apache-2.0 `*-api`
  modules and ships as its own JAR. Start from the
  [extension cookbook](https://github.com/voyagerforge-dev/karyo/blob/main/docs/guides/implementer-guide.md#extend-the-free-application).
- **Want to carry the change yourself?** The source is
  [Apache-2.0](https://github.com/voyagerforge-dev/karyo/blob/main/LICENSE). Fork this
  repository, apply your change, run it, and redistribute your fork under that licence.
  You do not need our permission and you do not need this pull request.
- **Found a security problem?** Do not describe it here or in an issue. Follow
  [SECURITY.md](https://github.com/voyagerforge-dev/karyo/blob/main/SECURITY.md).

[CONTRIBUTING.md](https://github.com/voyagerforge-dev/karyo/blob/main/CONTRIBUTING.md)
explains this boundary in full.
