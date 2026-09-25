---
description: Triages newly opened issues with labels, duplicate detection, and requests for missing information.
intent: Give every new issue a useful first response — correct labels, a link to any duplicate, and a request for whatever information is missing — so maintainers open an issue that is already actionable.

on:
  issues:
    types: [opened]
  skip-bots: [dependabot, renovate, copilot-swe-agent]
  reaction: eyes
  # Anyone may trigger this agent: most contributions here come from outside
  # collaborators, and gh-aw otherwise only runs for users with write access.
  roles: all

# Keeps a single account from draining the credit budget now that anyone can
# trigger this workflow.
user-rate-limit:
  max-runs-per-window: 3
  window: 60

permissions:
  contents: read
  issues: read

timeout-minutes: 8
max-turns: 20
max-ai-credits: 80
max-daily-ai-credits: 300

concurrency:
  group: "agent-issue-triage-${{ github.event.issue.number }}"

tools:
  # Required by strict mode once min-integrity is none: shell access to
  # untrusted input must be deliberate. Read-only commands only.
  bash: ["cat", "ls", "find", "grep", "head", "tail", "wc", "sort", "sed", "awk", "jq", "git", "gh"]
  github:
    mode: gh-proxy
    toolsets: [repos, issues]
    allowed-repos: ["vault-web/vault-web"]
    # The whole point of this agent is to handle outside contributions, and a
    # first-time contributor's items sit at integrity 'none'. With 'approved'
    # the agent could not read the very PRs/issues it exists to process.
    min-integrity: none

safe-outputs:
  # Labels are applied through the vault-web-agents app so that "agent-ready"
  # raises a labeled event the coding agent can react to. GITHUB_TOKEN would not.
  github-app:
    app-id: ${{ vars.VAULTWEB_AGENT_APP_ID }}
    private-key: ${{ secrets.VAULTWEB_AGENT_APP_KEY }}
  add-comment:
    max: 1
    target: triggering
  add-labels:
    max: 4
    allowed:
      - backend
      - frontend
      - bug
      - enhancement
      - documentation
      - security
      - Tests
      - question
      - duplicate
      - "good first issue"
      - "help wanted"
      # agent-approved is deliberately absent: only a human may vouch that an
      # outside contributor's issue is safe to hand to the coding agent.
      - agent-ready

network:
  allowed: [defaults]
---

# Issue Triage

A new issue was opened in `Vault-Web/vault-web`, a Java (Spring Boot) backend with
an Angular frontend. Many issues come from first-time contributors during
Hacktoberfest and similar events.

## What to do

1. **Search for duplicates.** Look through open and recently closed issues. If you
   find a genuine duplicate, link it explicitly by number.
2. **Classify.** Apply labels from the allowed list only. Use `backend` or
   `frontend` when the area is clear, and a type label (`bug`, `enhancement`,
   `documentation`, `security`, `Tests`, `question`).
3. **Decide who should work on it.** Pick at most one of these:
   - `good first issue` — small, clearly scoped, and a good way into the code
     base. Prefer this for well-described reports from contributors: people come
     to this project to contribute, and these issues are theirs.
   - `help wanted` — worth doing but larger, or needing design judgement.
   - `agent-ready` — a narrow, mechanical change with an unambiguous expected
     result (a failing test to fix, a small refactor, a dependency or config
     update) where no human has shown interest. Issues filed by the audit agent
     usually fit here.
   Leave all three off if the issue still needs information, discussion, or a
   maintainer decision. Never use `agent-ready` for anything security-relevant,
   for new features, or when the expected behaviour is open to interpretation.
4. **Identify missing information.** For a bug report, that usually means
   reproduction steps, the expected versus actual behaviour, and the browser or
   environment. Ask only for what is genuinely missing and genuinely needed.

## What not to do

Do not close the issue. Do not dismiss a security report as invalid — label it
`security` and let a human judge. Do not assign anyone, and never add `agent-ready` to an issue that already has an assignee or a linked pull request. Do not promise that
anything will be implemented. Do not answer a question you are not confident about.

## How to respond

Write one short comment. Be welcoming but brief — many of these are first-time
contributors. If the issue is already complete and clearly written, apply the labels but write
no comment — do not comment merely to say "thanks".

Begin the comment with `### Agent triage`.
