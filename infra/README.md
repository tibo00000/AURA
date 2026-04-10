# Infrastructure

`infra/` contains non-application assets required to run, package, or deploy AURA.

## What belongs here
- Dockerfiles and local compose files
- deployment notes and support files for platforms such as Render
- VPS deployment notes and compose files
- environment templates and hosting notes
- scripts or helpers for local and remote environments

## What does not belong here
- Android app source code
- FastAPI application code
- product documentation that is already canonical in `docs/`

## External services policy
- External services must be configured through environment variables.
- Platform-specific configuration must stay in `infra/`.
- Application code must depend on abstract configuration, not on a hosting provider.
- A service such as Render is an execution target, not a domain dependency.
- The default Render blueprint lives at the repository root as `render.yaml` because Render expects that location by default.
- Search provider logic must remain on the backend side even if the hosting target changes.
