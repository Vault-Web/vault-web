---
description: Implements an issue labelled agent-ready as a draft pull request, unless a contributor is already working on it.
intent: Turn a well-specified issue into a reviewable pull request, only when a maintainer has explicitly asked for it, and stop early when the issue is too vague to implement safely.

on:
  issues:
    types: [labeled]
  labels: [agent-ready]
  roles: [admin, maintainer, write]
  # The triage agent applies agent-ready through the vault-web-agents app, which
  # has no repository role of its own.
  bots: ["vault-web-agents[bot]"]
  reaction: rocket
  # Throttle: never more than three open agent pull requests at a time, so
  # review stays manageable and agents do not crowd out contributors.
  skip-if-match:
    query: "is:pr is:open label:agent-managed"
    max: 3

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
    # Issues filed by the audit agent come from the app and must be readable.
    trusted-users: ["vault-web-agents[bot]"]
    # Content from outside contributors stays unreadable to this agent until a
    # maintainer vouches for it with agent-approved. The triage agent cannot set
    # that label, so untrusted text never reaches the agent that writes code
    # without a human decision.
    approval-labels: [agent-approved]

safe-outputs:
  github-app:
    app-id: ${{ vars.VAULTWEB_AGENT_APP_ID }}
    private-key: ${{ secrets.VAULTWEB_AGENT_APP_KEY }}
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

An issue in `Vault-Web/vault-web` — a Java (Spring Boot) backend with an Angular
frontend — was labelled `agent-ready`, either by a maintainer or by the triage
agent. Implement it.

## First: make sure nobody else is working on it

Contributors come first. Before doing anything else, check the issue and stop
with a short, friendly comment instead of writing code if any of these is true:

- the issue has an assignee;
- an open pull request already references the issue;
- someone has said in a comment that they are working on it or would like to;
- the issue is labelled `good first issue` — those are kept for new contributors.

In that case say that you are leaving the issue to them, and do not open a pull
request.

If you cannot read the issue body at all, it was written by someone outside the
project and has not yet been approved for agents. Comment that a maintainer needs
to add the `agent-approved` label before you can work on it, and stop.

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
