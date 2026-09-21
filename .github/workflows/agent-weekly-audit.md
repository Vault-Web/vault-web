---
description: Weekly repository audit that files at most two high-confidence issues, or none.
intent: Find the small number of genuinely actionable problems in the repository each week — security gaps, correctness bugs, untested critical paths — and file them as issues only when they are strong enough to be worth a maintainer's attention.

on:
  schedule: weekly on monday
  workflow_dispatch:
  skip-if-match:
    query: "is:issue is:open label:agent-audit"
    max: 4

permissions:
  contents: read
  issues: read
  pull-requests: read

timeout-minutes: 25
max-turns: 80
max-ai-credits: 200
max-daily-ai-credits: 400

concurrency:
  group: "agent-weekly-audit"

tools:
  github:
    mode: gh-proxy
    toolsets: [repos, issues, pull_requests]
    allowed-repos: ["vault-web/vault-web"]
    min-integrity: approved

safe-outputs:
  create-issue:
    max: 2
    title-prefix: "[audit] "
    labels: [agent-audit]

network:
  allowed: [defaults]
---

# Weekly Repository Audit

Audit `Vault-Web/vault-web` — a Java (Spring Boot) backend with an Angular
frontend — for work that is genuinely worth doing.

## Before proposing anything

Search the existing issues first. Look at:

- open issues, including those labelled `agent-audit` from your previous runs
- issues closed in the last few months, so you do not re-file something that was
  rejected or already fixed
- recent commits and merged pull requests, to see whether a problem you spotted
  is already being addressed

A finding that duplicates existing work is worse than no finding at all.

## What to look for

- **Security** — missing authorization checks, unvalidated input, secrets in the
  repository, unsafe defaults, weaknesses around JWT handling and the vault.
- **Correctness** — logic that is wrong in a reachable case.
- **Missing tests** — critical paths with no coverage, especially authentication,
  encryption, and file access.
- **Regressions** — behaviour that recent changes quietly broke.
- **Configuration and CI** — weaknesses in the build or workflow setup.

Repository hygiene and normalization problems count as valid findings when they
have a concrete consequence.

## The hard rule

Create **at most two** issues, and create **none** when nothing meets the bar.
An empty run is a successful run. Do not pad the output to reach two. Do not file
a finding you cannot back with a specific file and a specific consequence.

If you have nothing strong enough, emit `noop`.

## Issue format

Each issue states the problem, the file and line, why it matters, and what a fix
would involve. Keep it short enough that a maintainer can judge it in a minute.
