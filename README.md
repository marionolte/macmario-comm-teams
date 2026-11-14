# macmario-comm-teams

MH Service Communication — Microsoft Teams Plugin.

A small Java 21 library (with a thin CLI) for **sending and receiving Microsoft
Teams messages** — to and from channels, chats, and individual users. It is built
to be dropped into a web application as a jar dependency, and can also be run
standalone from the command line.

---

## Features

- **Send** messages to a **channel**, a **chat**, or a **user** via Microsoft Graph.
- **Send** channel **notifications** via an incoming **webhook** (no Azure AD needed).
- **Receive** messages by polling, or by handling Graph **change-notification**
  webhooks (validation handshake, `clientState` check, create/renew/delete
  subscriptions).
- **User login** built in: OAuth 2.0 **Authorization Code + PKCE** for web apps,
  **device-code** sign-in for the CLI, plus app-only (client-credentials) for reads.
- No HTTP-client dependency (uses the JDK `java.net.http.HttpClient`); JSON via
  Jackson. Immutable value types, thread-safe clients, secrets redacted in logs.

## Requirements

- **Java 21+** (consumers must run on 21 or later).
- **Maven 3.9+** to build.
- For Graph features: a **Microsoft Entra ID (Azure AD) app registration**
  (see [Azure setup](#azure-app-registration)).
- The internal `com.macmario.core:macmario-core-io` dependency must be available
  in your Maven repository.

## Add as a dependency

```xml
<dependency>
    <groupId>com.macmario.services</groupId>
    <artifactId>comm.teams</artifactId>
    <version>1.0</version>
</dependency>
```

## Build

```bash
mvn package          # -> target/comm-teams.jar (thin) + comm-teams-jar-with-dependencies.jar (fat)
mvn install          # install to local ~/.m2
mvn test             # run the test suite
```

The thin jar's runtime dependencies are copied to `target/lib/`, so
`java -jar target/comm-teams.jar <command>` works.

---

## Quick start

### 1. Channel notification via webhook (simplest, no login)

```java
TeamsConfig cfg = TeamsConfig.builder()
        .webhookUrl("https://<your-teams-workflow-or-connector-webhook>")
        .build();

TeamsClients.webhook(cfg).send(
        AdaptiveCard.builder()
                .title("Deployment")
                .text("v1.2.0 shipped to production")
                .addFact("Service", "comm.teams")
                .addFact("Status", "OK")
                .build());
```

### 2. Send/receive via Microsoft Graph as the signed-in user

If your web app already has the user's delegated access token:

```java
TeamsConfig cfg = TeamsConfig.fromEnv();
TeamsClient teams = TeamsClients.graphWithUserToken(cfg, () -> currentUserAccessToken());

teams.sendChannelMessage(teamId, channelId, OutgoingMessage.text("Hello channel"));
teams.sendChatMessage(chatId, OutgoingMessage.html("<b>Hello chat</b>"));
teams.sendDirectMessage(userId, OutgoingMessage.text("Hello user"));

List<TeamsMessage> latest = teams.fetchChannelMessages(teamId, channelId, 20);
CurrentUser me = teams.fetchCurrentUser();
```

### 3. Let the library log the user in (web app, Auth Code + PKCE)

Two routes and a session are all the host supplies:

```java
// Build once at startup and reuse (one shared HttpClient):
HttpTransport transport = HttpTransport.createDefault();
TeamsConfig cfg = TeamsConfig.fromEnv();              // clientId, tenantId, redirectUri
AuthorizationCodeFlow flow = TeamsClients.login(transport, cfg);

// GET /login
AuthorizationRequest req = flow.start();
session.setAttribute("authReq", req);                // records are Serializable
redirect(req.authorizationUrl());

// GET /callback?code=...&state=...&error=...
flow.failIfCallbackError(error, errorDescription);   // surfaces consent/decline errors
var pending = (AuthorizationRequest) session.getAttribute("authReq");
session.removeAttribute("authReq");                  // state is single-use
TokenSet tokens = flow.exchange(code, state, pending);
session.setAttribute("teamsTokens", tokens);         // persist per user

// Build a client; onRefresh re-persists rotated refresh tokens
TeamsClient teams = TeamsClients.graphAfterLogin(transport, cfg, tokens,
        rotated -> session.setAttribute("teamsTokens", rotated));
```

> The token provider is **per user** — keep it in that user's session, never share
> it across users.

### 4. Receive change notifications (push) in a web app

```java
// Create a subscription pointing at your HTTPS endpoint (default lifetime 55 min):
var subs = new SubscriptionManager(transport, appTokenProvider, cfg.graphBaseUrl());
Subscription sub = subs.create("/teams/{id}/channels/{id}/messages",
        "https://app.example/teams/notifications", clientStateSecret);
// ...renew before sub.expiration(), subs.renew(sub.id()); subs.delete(sub.id());

// In the endpoint:
var parser = new ChangeNotificationParser();
parser.validationToken(request.getQueryString())     // echo back as text/plain 200
      .ifPresentOrElse(this::echoPlainText, () -> {
          for (ChangeNotification n : parser.parse(requestBody)) {
              if (n.hasValidClientState(clientStateSecret)) {
                  TeamsMessage msg = teams.getChatMessage(chatId, n.resourceId());
                  // handle msg...
              }
          }
      });
```

---

## Command-line usage

Configure via environment variables, then run the jar.

**Sign in once, then run commands non-interactively:**

```bash
java -jar target/comm-teams.jar user-login       # device-code sign-in; token is saved
java -jar target/comm-teams.jar user-logout      # clear the stored token (local sign-out)
```

`user-login` prints a code to enter at <https://microsoft.com/devicelogin> and, on
success, saves the token (incl. refresh token) to an **owner-only** file —
`$TEAMS_TOKEN_STORE`, or `~/.comm-teams/token.json` by default.

**Send / receive** (these use the stored token if present, otherwise trigger a
one-off device-code sign-in for that command):

```bash
java -jar target/comm-teams.jar webhook        "Build finished"
java -jar target/comm-teams.jar send-channel   <teamId> <channelId> "Hello"
java -jar target/comm-teams.jar send-chat      <chatId> "Hello"
java -jar target/comm-teams.jar send-user      <userId> "Hello"
java -jar target/comm-teams.jar fetch-channel  <teamId> <channelId> [top]
java -jar target/comm-teams.jar fetch-chat     <chatId> [top]
```

> `user-logout` removes the local token only; it does not revoke the session in
> Entra ID.

---

## Configuration

Via `TeamsConfig.fromEnv()`, `TeamsConfig.fromProperties(path)`, or the builder.

| Env var | Properties key | Purpose |
|---|---|---|
| `TEAMS_TENANT_ID` | `teams.tenantId` | Entra tenant GUID, or `organizations` / `common` for multi-tenant |
| `TEAMS_CLIENT_ID` | `teams.clientId` | App registration client id |
| `TEAMS_CLIENT_SECRET` | `teams.clientSecret` | Secret for confidential clients / client-credentials (omit for public/PKCE) |
| `TEAMS_REDIRECT_URI` | `teams.redirectUri` | OAuth redirect URI for the auth-code login |
| `TEAMS_SCOPE` | `teams.scope` | Override scopes (defaults cover all operations) |
| `TEAMS_WEBHOOK_URL` | `teams.webhookUrl` | Incoming webhook URL |
| `TEAMS_TEAM_ID` / `TEAMS_CHANNEL_ID` | `teams.teamId` / `teams.channelId` | Default channel targets |
| `TEAMS_CLIENT_STATE` | `teams.clientState` | Secret echoed on change notifications |
| `TEAMS_TOKEN_STORE` | — | CLI token file path (default `~/.comm-teams/token.json`) |

Secrets are read from the environment or a properties file — never hardcode them.
`TeamsConfig`, `TokenSet`, and `AccessToken` redact secrets in `toString()`.

## Azure app registration

- **Sending** channel/chat/user messages requires a **delegated** token — Microsoft
  Graph only allows app-only tokens to send for *migration*. Grant the delegated
  scopes `ChannelMessage.Send` and `Chat.ReadWrite`; reading messages also needs
  `ChannelMessage.Read.All` (**admin consent**), and `/me` needs `User.Read`.
- **Web app (auth code)**: add a **"Web"** redirect URI matching `TEAMS_REDIRECT_URI`;
  add a **client secret** if the app is confidential (omit for public/PKCE). A
  mismatch yields `AADSTS7000218` (secret missing) or `AADSTS700025` (secret sent by
  a public client).
- **CLI (device code)**: enable **"Allow public client flows"** on the registration.
- **App-only reads/subscriptions**: use a client secret; message subscriptions need
  admin-consented, protected application permissions.
- **Incoming webhooks** are being retired in favor of Teams **Workflows** webhooks;
  prefer a Workflows "when a webhook request is received" URL. The sender accepts
  any 2xx (including the Workflows `202`).

## Architecture

See [CLAUDE.md](CLAUDE.md) for the package-level architecture, the auth model, and
contributor notes.

## License

Released under the **MIT License**. Copyright (c) 2026 macmario.
