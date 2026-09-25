---
description: Repairs failing CI on agent-managed pull requests automatically, at most twice per pull request.
intent: When CI fails on a pull request the agents opened, find the cause and push a fix to that pull request's branch, at most twice, and hand back to a human when two attempts were not enough. Never touch main.

on:
  workflow_run:
    workflows: ["Backend CI", "Frontend CI"]
    types: [completed]
    # The coding agent names its branches agent/issue-<n>-<slug>. Nothing else
    # is in scope.
    branches: ["agent/**"]
  # CI on agent branches is started by pushes from the vault-web-agents app.
  bots: ["vault-web-agents[bot]"]
  # Deterministic gates, evaluated before the agent runs:
  #   1. an open pull request from this branch must carry agent-managed
  #   2. it must not already have used its final attempt (agent-reentry-2)
  skip-if-no-match:
    query: >-
      is:pr is:open label:agent-managed head:${{ github.event.workflow_run.head_branch }}
    min: 1
  skip-if-match:
    query: >-
      is:pr is:open label:agent-reentry-2 head:${{ github.event.workflow_run.head_branch }}
    max: 1

# Only failed runs, and only for branches in this repository — a fork could
# name a branch agent/… too, but its CI must never start this agent.
if: >-
  github.event.workflow_run.conclusion == 'failure' &&
  github.event.workflow_run.head_repository.full_name == github.repository

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
  group: "agent-ci-repair-${{ github.event.workflow_run.head_branch }}"

# Check out the failing agent branch so a fix can be pushed to it.
checkout:
  ref: ${{ github.event.workflow_run.head_branch }}

tools:
  github:
    mode: gh-proxy
    toolsets: [repos, issues, pull_requests, actions]
    allowed-repos: ["vault-web/vault-web"]
    min-integrity: approved
    trusted-users: ["vault-web-agents[bot]"]

safe-outputs:
  # Pushes go through the app so CI runs again on the fixed branch.
  github-app:
    app-id: ${{ vars.VAULTWEB_AGENT_APP_ID }}
    private-key: ${{ secrets.VAULTWEB_AGENT_APP_KEY }}
  push-to-pull-request-branch:
    max: 1
    target: "*"
    # Defense in depth: the push is refused unless the PR is agent-owned.
    required-labels: [agent-managed]
  add-comment:
    max: 1
    target: "*"
  add-labels:
    max: 1
    target: "*"
    allowed: [agent-reentry-1, agent-reentry-2]
  remove-labels:
    max: 1
    target: "*"
    allowed: [agent-reentry-1]

network:
  allowed: [defaults]
---

# CI Repair

CI failed in `Vault-Web/vault-web` on a pull request opened by the coding agent.
Find why CI failed and fix the cause on that pull request's branch.

The failed run is ${{ github.event.workflow_run.html_url }}, on commit
`${{ github.event.workflow_run.head_sha }}`. First find the open pull request
whose head is that commit — you have it checked out — and use its number for every
output you emit.

## Attempt counter

Read the labels on that pull request.

- No `agent-reentry-*` label → this is attempt 1. Add `agent-reentry-1` before you
  finish.
- `agent-reentry-1` → this is attempt 2, your last. Remove `agent-reentry-1` and
  add `agent-reentry-2` before you finish.

You will never see a pull request that already carries `agent-reentry-2`: the
workflow refuses to start in that case, before you are invoked. After a second
failed attempt a maintainer takes over — say so in your comment.

## What to do

Read the failing job logs. Find the actual cause — a compilation error, a failing
test, a formatting violation that Spotless or Prettier rejects.

Fix the cause, not the symptom. Do not delete or skip a failing test to make CI
green: if a test fails because the code is wrong, fix the code. If you believe the
test itself is wrong, say so in a comment and stop rather than changing it.

Keep the change as small as the failure requires, and push it to the pull
request's own branch.

## Limits

Do not touch anything unrelated to the failure. Never push to `main`. Do not
merge. Do not change workflow files. If you cannot determine the cause, comment
with what you found and stop — a clear "I could not fix this, here is why" is more
useful than a guess.
