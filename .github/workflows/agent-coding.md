---
description: Implements an issue as a pull request when a maintainer explicitly assigns it.
intent: Turn a well-specified issue into a reviewable pull request, only when a maintainer has explicitly asked for it, and stop early when the issue is too vague to implement safely.

on:
  issues:
    types: [labeled]
  labels: [agent-ready]
  roles: [admin, maintainer, write]
  reaction: rocket

permissions:
  contents: read
  issues: read
  pull-requests: read

timeout-minutes: 30
max-turns: 100
max-ai-credits: 400
max-daily-ai-credits: 800

concurrency:
  group: "agent-coding-${{ github.event.issue.number }}"

tools:
  github:
    mode: gh-proxy
    toolsets: [repos, issues, pull_requests]
    allowed-repos: ["vault-web/vault-web"]
    min-integrity: approved

safe-outputs:
  create-pull-request:
    max: 1
    title-prefix: "[agent] "
    labels: [agent-managed]
    draft: true
  add-comment:
    max: 1
    target: triggering

network:
  allowed: [defaults]
---

# Implement an Issue

A maintainer labelled an issue `agent-ready` in `Vault-Web/vault-web` — a Java
(Spring Boot) backend with an Angular frontend. Implement it.

## Before writing code

Read the issue carefully, then read the code it concerns. Understand how the
surrounding code is structured and follow those patterns rather than introducing
your own.

If the issue is too vague to implement without guessing at the intended behaviour,
do **not** guess. Comment with the specific question that needs answering and stop.
A pull request built on a wrong assumption costs more review time than no pull
request.

## While implementing

- Match the existing style; Spotless and Prettier run in CI and will reject
  deviations.
- Add tests for the logic you add, especially anything touching authentication,
  encryption, or file access.
- Run the relevant existing tests and the build before opening the pull request,
  where that is technically possible. If you could not run them, say so
  explicitly in the pull request description rather than staying silent.
- Keep the change scoped to the issue. Do not refactor adjacent code, rename
  things, or fix unrelated problems you notice — mention those in the pull request
  description instead.

## The pull request

Open it as a **draft**. Describe what you changed, why, and anything a reviewer
should check carefully. Link the issue. Be explicit about what you did not do and
about any assumption you had to make.

Never merge. Never modify workflow files under `.github/workflows/`.
