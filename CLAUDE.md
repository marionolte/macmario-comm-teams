# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

`comm.teams` (groupId `com.macmario.services`) is a Java 21 library — with a thin
CLI driver — for **sending and receiving Microsoft Teams messages**: to/from
channels, chats, and individual users. It is designed to be consumed as a jar
dependency by a web application, and also runnable standalone via its `Main`
entry point (`com.macmario.services.comm.teams.Main`).

Two send paths are supported by design:

- **Incoming webhook** (`client.WebhookNotifier`) — simplest path for channel
  notifications; needs only a webhook URL, no Azure AD. Sends Adaptive Cards.
- **Microsoft Graph** (`client.TeamsClient` / `GraphTeamsClient`) — full
  send + receive for channels, chats, and direct-to-user messages.

Receiving is via Graph: polling (`fetch*` methods) and parsing Graph
change-notification webhooks (`notifications.ChangeNotificationParser`) so a host
web app can expose an endpoint and hand raw callbacks to this library.

## Architecture (the big picture)

Package layout under `src/main/java/com/macmario/services/comm/teams/`:

| Package | Responsibility |
|---|---|
| `config` | `TeamsConfig` — immutable config (record + builder), `fromEnv()` / `fromProperties()`. No secrets in code. |
| `auth` | `AccessTokenProvider` (the key abstraction) + implementations; `AuthorizationCodeFlow` (user login, PKCE), `RefreshingTokenProvider`, `TokenSet`, `Pkce`. |
| `http` | `HttpTransport` — thin wrapper over the JDK `java.net.http.HttpClient` + Jackson. **Internal**: no Jackson/HttpClient type appears in the public API. |
| `model` | `OutgoingMessage`, `TeamsMessage` (received), `AdaptiveCard`. Immutable. |
| `client` | `TeamsClient` interface + `GraphTeamsClient`, `WebhookNotifier`, and the `TeamsClients` factory (host entry point). |
| `notifications` | `ChangeNotificationParser` (parse/validate callbacks), `SubscriptionManager` (create/renew/delete Graph subscriptions), `ChangeNotification` / `Subscription` records. |
| (root) | `TeamsException` (unchecked, carries HTTP status), `Main` (thin CLI). |

### The auth model is the thing to understand first

Microsoft Graph **only allows app-only (client-credentials) tokens to *send*
Teams messages for migration** (`Teamwork.Migrate.All`). Normal sends require a
**delegated** token (`ChannelMessage.Send`, `Chat.ReadWrite`). This drives the
whole design: auth is pluggable via `AccessTokenProvider`:

- `StaticTokenProvider` — caller supplies a delegated token (a `Supplier<String>`
  so a web app can return a freshly-refreshed one).
- `AuthorizationCodeFlow` → `RefreshingTokenProvider` — the library performs the
  **user login** itself (auth-code + PKCE) and then auto-refreshes. **This is the
  send path when the app hasn't already got a token.**
- `ClientCredentialTokenProvider` — app-only token for **reads** and
  subscriptions (these need admin-consented, protected app permissions).
- `DeviceCodeTokenProvider` — interactive delegated sign-in for the CLI.

### User login (web app) — authorization code + PKCE

The library drives the login; the host app just owns two routes and the session.

```java
// Build these ONCE at startup and reuse (one shared HttpClient):
HttpTransport transport = HttpTransport.createDefault();
TeamsConfig cfg = TeamsConfig.fromEnv();            // needs clientId, tenantId, redirectUri
AuthorizationCodeFlow flow = TeamsClients.login(transport, cfg);

// GET /login  — send the user to Microsoft
AuthorizationRequest req = flow.start();            // or start(redirectUri)
session.setAttribute("authReq", req);               // records are Serializable
redirect(req.authorizationUrl());

// GET /callback?code=...&state=...&error=...  — finish login
flow.failIfCallbackError(error, errorDescription);  // surface consent/decline errors first
var pending = (AuthorizationRequest) session.getAttribute("authReq");
session.removeAttribute("authReq");                 // state is single-use
TokenSet tokens = flow.exchange(code, state, pending);   // verifies state (CSRF); throws if pending null
session.setAttribute("teamsTokens", tokens);             // persist per user

// Build a client per request from the session's TokenSet; onRefresh re-persists
// rotated tokens (Entra rotates them). Reuse the shared transport.
TeamsClient teams = TeamsClients.graphAfterLogin(transport, cfg, tokens,
        rotated -> session.setAttribute("teamsTokens", rotated));
CurrentUser me = teams.fetchCurrentUser();
teams.sendChannelMessage(teamId, channelId, OutgoingMessage.text("Hello from " + me.displayName()));
```

- **Confidential vs public client**: if `clientSecret` is set, the code exchange
  and refresh send it (a "Web" platform app registration). If it's absent, they
  don't (public/PKCE). A mismatch yields `AADSTS7000218` (secret missing) or
  `AADSTS700025` (secret sent by a public client).
- **Admin consent**: the default scopes include `ChannelMessage.Read.All`, which
  requires a tenant admin to consent once. Without it, a non-admin's callback comes
  back with `error=consent_required` (no `code`) — hence `failIfCallbackError`
  first. To avoid it entirely (send-only app), call `start(redirectUri, scope)` with
  a scope string omitting `ChannelMessage.Read.All`.
- **Multi-tenant**: set `tenantId=organizations` (any work/school tenant) or
  `common`; use a specific tenant GUID for single-tenant.
- `RefreshingTokenProvider` is **per user** — keep it in that user's session,
  never shared. Secrets are redacted in `toString()` on `TokenSet`, `AccessToken`,
  and `TeamsConfig`.

Build clients through `TeamsClients` (e.g. `graphWithUserToken`, `graphWithAppToken`,
`webhook`). Clients are stateless and thread-safe; share one instance.

**Azure AD app registration** must have **"Allow public client flows"** enabled for
the device-code CLI. Delegated scopes used by the CLI: `ChannelMessage.Send`,
`Chat.ReadWrite`, `ChannelMessage.Read.All` (admin consent), `User.Read`. A host
web app grants whichever scopes its own operations need and passes the resulting
token via `graphWithUserToken`.

### Using it from a web application

```java
TeamsConfig cfg = TeamsConfig.fromEnv();                 // or builder()
// Sending as the signed-in user (delegated token from the app's own OAuth):
TeamsClient teams = TeamsClients.graphWithUserToken(cfg, () -> currentUserAccessToken());
teams.sendChannelMessage(teamId, channelId, OutgoingMessage.text("Hello"));
teams.sendDirectMessage(userId, OutgoingMessage.html("<b>Hi</b>"));

// Simple channel notification (no Azure AD):
TeamsClients.webhook(cfg).send(AdaptiveCard.of("Deploy", "v1.2 shipped"));

// Receiving Graph change-notification callbacks in a controller:
var parser = new ChangeNotificationParser();
parser.validationToken(request.getQueryString())         // echo back as text/plain 200
      .ifPresentOrElse(this::echo, () -> {
          for (var n : parser.parse(body)) {
              if (n.hasValidClientState(expectedSecret)) { /* fetch by id, handle */ }
          }
      });
```

## Build & run

**Builds are run outside this directory** (e.g. in NetBeans on Windows); do not
treat running Maven here as part of the task. The commands below are for
reference / the host build environment. Maven 3.9+, Java 21.

```bash
mvn compile
mvn package          # target/comm-teams.jar (thin) + comm-teams-jar-with-dependencies.jar (fat)
mvn install
java -jar target/comm-teams.jar <command>          # thin jar; deps copied to target/lib/
mvn test -Dtest=GraphTeamsClientTest#sendChannelMessage_returnsId   # single test
```

Tests use a local JDK `com.sun.net.httpserver.HttpServer` stub (see
`src/test/java/.../support/StubServer.java`) for real, offline HTTP round-trips —
no Mockito/AssertJ, no network, no live credentials.

## Dependencies & gotchas

- **`macmario-core-io` is internal-only** and resolves from the local
  `~/.m2/repository/com/macmario/core/macmario-core-io/1.0/` (not on Maven
  Central). It is currently a declared dependency but **not used** by the Teams
  code (which uses the JDK HTTP client + Jackson); keep or drop it per project
  needs — ask before removing.
- **Jackson** (`jackson-databind`) is a direct dependency for JSON. HTTP uses the
  JDK's built-in client, so there is no HTTP-client dependency.
- **POM packaging** produces two runnables: the thin `target/comm-teams.jar`
  (manifest classpath → `target/lib/`, populated by `maven-dependency-plugin`) and
  the self-contained `jar-with-dependencies` fat jar (via `maven-assembly-plugin`
  with the built-in `jar-with-dependencies` descriptorRef).
- **Webhook deprecation**: Office 365 connector incoming webhooks are being
  retired in favour of Teams **Workflows** webhooks. `WebhookNotifier` sends the
  Adaptive Card message envelope (works on both) and treats any 2xx (incl. 202)
  as success.
- **Subscriptions** (change notifications) are short-lived and must be renewed.
  `SubscriptionManager` creates/renews/deletes them (default lifetime 55 min, kept
  under the 60-min cap so no `lifecycleNotificationUrl` is needed). Always verify
  `clientState` on every callback (`ChangeNotification.hasValidClientState`) and
  fetch the changed message by id (`getChannelMessage` / `getChatMessage`), since
  callbacks carry no content. Message subscriptions generally need an
  admin-consented application token.
