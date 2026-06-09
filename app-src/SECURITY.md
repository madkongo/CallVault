# Security Policy

## Supported Versions

Security updates are provided **only** for the following versions:

| Version | Supported |
| ------- | --------- |
| Latest stable release | ✅* |
| Latest debug / prerelease | ✅ |
| Older versions | ❌ |

Reports affecting unsupported versions will be closed without action.

>[!TIP]
> Releases are build using Github Actions Runners. We also attests the file so that you can trace them back to the runner. You can see the [Github CLI docs](https://cli.github.com/manual/gh_attestation_verify) on how to do this.

---

## Reporting a Vulnerability

If you discover a security vulnerability in ShizuCallRecorder, please report it **privately** and practice responsible disclosure. [See how](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/report-a-vulnerability/privately-reporting-a-security-vulnerability).

When reporting a vulnerability, you may include any of the following:
- Detailed step-by-step reproduction instructions (including granular or 1-by-2 steps)
- Screenshots or screen recordings
- Logs or crash output
- Proof-of-concept (PoC) code
- Test applications or scripts demonstrating the issue

Providing detailed information helps us reproduce and fix the issue more efficiently.

---

## Testing Before Reporting

To reduce duplicate reports and false positives, please verify the issue under the following conditions:

1. Confirm the issue reproduces on the **latest stable release**
2. **Also test on the latest prerelease (debug) build**, if more recent than the latest release

If the issue does **not** reproduce on the prerelease build, it may already be fixed.

---

## Responsible Disclosure

Please follow these disclosure guidelines:

- Do **not** publicly disclose vulnerability details immediately
- Wait **at least 2 week after a release containing the fix** before sharing technical details that could reasonably lead to exploitation
  - This includes detailed exploit write-ups, abuse techniques, code snippets, videos, or tutorials that demonstrate real-world attacks

This delay allows users adequate time to update and helps reduce the risk of active exploitation.

---

## What Not to Report

- Issues affecting only unsupported versions
- Vulnerabilities that require modifying ShizuCallRecorder itself to be exploitable
- Reports without any reasonable security impact
- Reports not related to ShizuCallRecorder (For examples, issues related to Shizuku should be reported to Shizuku)
