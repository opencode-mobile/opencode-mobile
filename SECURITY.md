# Security policy

OpenCode Mobile stores opencode server credentials and can drive an opencode
server, which can run commands on the server's machine. Please report
vulnerabilities privately.

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/opencode-mobile/opencode-mobile/security/advisories/new)
(Security → Report a vulnerability). Do not open a public issue.

Include the affected platform and app version, the steps to reproduce, and the
impact you expect. We aim to reply within 7 days.

## Scope

In scope: the iOS and Android apps in this repository, including how they
store credentials and talk to an opencode server.

Out of scope: the opencode server itself (report to
[opencode](https://github.com/sst/opencode)), and servers exposed over plain
public HTTP. The apps expect a trusted transport such as LAN, VPN, HTTPS, or a
tunnel.

## Supported versions

Only the latest released version of each app gets security fixes.
