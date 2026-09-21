---
description: Repairs failing CI on agent-managed pull requests, with a hard re-entry limit.
intent: Fix the cause of a failing check on a pull request the agents own, at most twice per pull request, and hand back to a human when two attempts were not enough.

on:
  # Command semantics: the label is removed automatically after activation, so a
  # second repair requires a maintainer to deliberately re-apply it.
  label_command:
    name: agent-repair
    events: [pull_request]
  roles: [admin, maintainer, write]
  reaction: eyes
  # Deterministic gate, evaluated BEFORE the agent runs. Two conditions:
  #   1. the PR must be agent-owned (agent-managed)
  #   2. it must not already have used its final attempt (agent-reentry-2)
  # Neither depends on the model following instructions.
  skip-if-no-match:
    query: >-
      is:pr ${{ github.event.pull_request.number }} label:agent-managed
    min: 1
  skip-if-match:
    query: >-
      is:pr ${{ github.event.pull_request.number }} label:agent-reentry-2
    max: 1

permissions:
  contents: read
  pull-requests: read
  issues: read
  actions: read
  checks: read

timeout-minutes: 20
max-turns: 60
max-ai-credits: 150
max-daily-ai-credits: 450

concurrency:
  group: "agent-ci-repair-${{ github.event.pull_request.number }}"
  job-discriminator: ${{ github.run_id }}

tools:
  github:
    mode: gh-proxy
    toolsets: [repos, issues, pull_requests, actions]
    allowed-repos: ["vault-web/vault-web"]
    min-integrity: approved

safe-outputs:
  push-to-pull-request-branch:
    max: 1
    # Defense in depth: even if everything above were bypassed, the push is
    # refused unless the PR is agent-owned.
    required-labels: [agent-managed]
  add-comment:
    max: 1
    target: triggering
  add-labels:
    max: 1
    allowed: [agent-reentry-1, agent-reentry-2]
  remove-labels:
    max: 1
    allowed: [agent-reentry-1]

network:
  allowed: [defaults]
---

# CI Repair

A pull request in `Vault-Web/vault-web` was labelled `agent-repair`. Your job is to
find why its checks are failing and fix the cause.

## Attempt counter

Before anything else, read the labels on this pull request.

- No `agent-reentry-*` label → this is attempt 1. Add `agent-reentry-1` before you
  finish.
- `agent-reentry-1` → this is attempt 2, your last. Remove `agent-reentry-1` and
  add `agent-reentry-2` before you finish.

You will never see a pull request that already carries `agent-reentry-2`: the
workflow refuses to start in that case, before you are invoked. The counter you
maintain here is bookkeeping — the actual loop bound sits outside your control.

The `agent-repair` label is removed automatically when this run starts, so a
further attempt requires a maintainer to apply it again deliberately.

## What to do

Read the failing check runs and their logs. Find the actual cause — a compilation
error, a failing test, a formatting violation that Spotless or Prettier rejects.

Fix the cause, not the symptom. Do not delete or skip a failing test to make CI
green: if a test fails because the code is wrong, fix the code. If you believe the
test itself is wrong, say so in a comment and stop rather than changing it.

Keep the change as small as the failure requires.

## Limits

Do not touch anything unrelated to the failure. Do not merge. Do not change
workflow files. If you cannot determine the cause, comment with what you found and
stop — a clear "I could not fix this, here is why" is more useful than a guess.
