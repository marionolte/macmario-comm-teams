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
- **User login** built in: **username/password (ROPC)** for the CLI, OAuth 2.0
  **Authorization Code + PKCE** for web apps, device-code, plus app-only
  (client-credentials) for reads.
- **configdb-backed CLI config** (`-config` / `-showconfig`): settings stored in the
  `macmario-services-db` configdb (encryption, if any, is handled by that component).
- **Target by name**: resolve teams/channels by display name (or id) — no need to
  hardcode GUIDs; IDs are discovered at login.
- No HTTP-client dependency (uses the JDK `java.net.http.HttpClient`); JSON via
  Jackson. Immutable value types, thread-safe clients, secrets redacted in logs.

## Requirements

- **Java 21+** (consumers must run on 21 or later).
- **Maven 3.9+** to build.
- For Graph features: a **Microsoft Entra ID (Azure AD) app registration**
  (see [Azure setup](#azure-app-registration)).
- The internal dependencies must be available in your Maven repository:
  `com.macmario.core:macmario-core-io:1.0` and
  `com.macmario.services:macmario-services-db:1.0` (the configdb; brings H2).

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

## Install & configure the CLI

The CLI reads its settings from the **configdb** — it does **not** prompt for them at
login. You must run `-config` **once before** `user-login`, or you'll see
`Error: Missing required configuration value: tenantId`.

> **Run every command from the same directory.** The configdb is an H2 file at
> `./.config-app/configdb` relative to the current directory. If you `-config` in one
> folder and `user-login` in another, the second won't find your settings.

**1. Make the internal dependencies available** in your local Maven repo
(`~/.m2/repository`): `macmario-core-io:1.0` and `macmario-services-db:1.0`. Then build:

```bash
mvn package        # or: mvn install
```

**2. Configure once** (interactive — writes the configdb):

```bash
java -jar target/comm-teams.jar -config
#   Tenant ID (GUID or 'organizations'): <your-tenant-guid>
#   Client ID (app registration):        <your-client-id>
#   Client secret (blank for a public app): <blank or secret>
#   Teams username (UPN):                 user@contoso.com
#   Teams password:                       ********
#   Authority / Scopes / Webhook / Graph base URL: <blank = defaults>
# -> "Configuration saved." then "configdb: .../.config-app — run 'user-login' ..."
```

Required: **tenantId, clientId, username, password**. Press Enter to keep an existing
value or accept a default. Re-running `-config` updates values and clears the cached
token.

**3. Sign in** (username/password; caches the token and prints your teams + IDs):

```bash
java -jar target/comm-teams.jar user-login
```

**4. Use it** — see [Command-line usage](#command-line-usage) below.

**Prerequisites for login to succeed** (see [Azure setup](#azure-app-registration)):
the account must be a work/school account **without MFA**, the tenant must be a GUID or
`organizations` (not `common`), an admin must have consented to the delegated scopes,
and the app registration must match your client type (public → "Allow public client
flows" on, no secret; confidential → secret set, flows off).

> Prefer not to use the configdb? Set the `TEAMS_*` environment variables instead (see
> [Configuration](#configuration)); the CLI falls back to them when the `comm.teams`
> app has no configdb entry.

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

**1. Configure once** (interactive; stored in the configdb):

```bash
java -jar target/comm-teams.jar -config
#   Tenant ID (GUID or 'organizations'): ...
#   Client ID: ...
#   Teams username (UPN): user@contoso.com
#   Teams password: ******

java -jar target/comm-teams.jar -showconfig      # inspect/edit the configdb interactively (DbMain)
```

**2. Sign in** (username/password; caches the token and shows discovered IDs):

```bash
java -jar target/comm-teams.jar user-login       # ROPC sign-in; prints joined teams + IDs
java -jar target/comm-teams.jar user-logout      # clear the cached token (local sign-out)
```

**3. Send / receive** — team and channel accept a **name or an id**:

```bash
java -jar target/comm-teams.jar webhook        "Build finished"
java -jar target/comm-teams.jar send-channel   "My Team" "General" "Hello"
java -jar target/comm-teams.jar send-chat      "Group topic" "Hello"   # topic or 19: id
java -jar target/comm-teams.jar send-user      user@contoso.com "Hello"
java -jar target/comm-teams.jar fetch-channel  "My Team" "General" [top]
java -jar target/comm-teams.jar fetch-chat     "Group topic" [top]
```

**Discover IDs:**

```bash
java -jar target/comm-teams.jar list-teams
java -jar target/comm-teams.jar list-channels "My Team"
java -jar target/comm-teams.jar list-chats
```

### Webhook notifications from the CLI (no login)

The `webhook` command posts a notification straight to a channel through an **incoming
webhook URL**. It needs **no `user-login`, no tenant/client, no credentials** — only the
URL. It's the quickest way to push a one-off message (CI result, alert, deploy notice).

**1. Get a webhook URL** for the target channel (either works):

- **Teams Workflows** (recommended; connectors are being retired): in the channel,
  **⋯ → Workflows → "Post to a channel when a webhook request is received"**. Copy the
  generated URL.
- **Legacy connector**: **channel ⋯ → Connectors → Incoming Webhook → Create**, then
  copy the URL.

**2. Tell the CLI the URL** — any one of:

```bash
# a) store it in the configdb (the "Incoming webhook URL" prompt):
java -jar target/comm-teams.jar -config

# b) or set it for a single run via environment variable:
export TEAMS_WEBHOOK_URL="https://prod-00.westeurope.logic.azure.com:443/workflows/.../triggers/..."
```

**3. Send:**

```bash
java -jar target/comm-teams.jar webhook "Build #42 succeeded ✅"
# -> "Notification sent."
```

The text is sent as an **Adaptive Card**; any `2xx` response (including the Workflows
`202 Accepted`) counts as success. If the URL isn't configured you'll get
`Error: Missing required configuration value: webhookUrl`.

> Webhooks are **send-only and channel-only**. To send to a user/chat, read messages,
> or target by name, use the Graph commands above (which do require `user-login`).

The cached token (incl. refresh token) is saved to an **owner-only** file —
`$TEAMS_TOKEN_STORE`, or `~/.comm-teams/token.json` by default. The configdb (H2)
lives under `${working-dir}/.config-app/`.

> `user-logout` removes the local token only; it does not revoke the session in
> Entra ID. If the `comm.teams` app isn't configured in the configdb, the CLI falls
> back to environment variables.

---

## Configuration

Via `TeamsConfig.fromEnv()`, `TeamsConfig.fromProperties(path)`, or the builder.

| Env var | Properties key | Purpose |
|---|---|---|
| `TEAMS_TENANT_ID` | `teams.tenantId` | Entra tenant GUID, or `organizations` / `common` for multi-tenant |
| `TEAMS_CLIENT_ID` | `teams.clientId` | App registration client id |
| `TEAMS_CLIENT_SECRET` | `teams.clientSecret` | Secret for confidential clients / client-credentials (omit for public/PKCE) |
| `TEAMS_USERNAME` | `teams.username` | Username (UPN) for CLI username/password (ROPC) login |
| `TEAMS_PASSWORD` | `teams.password` | Password for ROPC login |
| `TEAMS_REDIRECT_URI` | `teams.redirectUri` | OAuth redirect URI for the auth-code login |
| `TEAMS_SCOPE` | `teams.scope` | Override scopes (defaults cover all operations) |
| `TEAMS_WEBHOOK_URL` | `teams.webhookUrl` | Incoming webhook URL |
| `TEAMS_TEAM_ID` / `TEAMS_CHANNEL_ID` | `teams.teamId` / `teams.channelId` | Default channel targets |
| `TEAMS_CLIENT_STATE` | `teams.clientState` | Secret echoed on change notifications |
| `TEAMS_TOKEN_STORE` | — | CLI token file path (default `~/.comm-teams/token.json`) |

Secrets are read from the environment, a properties file, or the configdb — never
hardcode them. `TeamsConfig`, `TokenSet`, and `AccessToken` redact secrets in
`toString()`.

**configdb:** values are stored via the configdb component (`macmario-services-db`);
this project does not encrypt or decrypt them — any encryption is handled by that
component/layer outside this project. Inspect or edit the stored settings interactively
with `-config` (guided) or `-showconfig` (the component's `DbMain` browser). Config
precedence for the CLI: the configdb (if the `comm.teams` app has entries) otherwise
`TEAMS_*` env vars. **Run all CLI commands from the same working directory** — the
configdb lives in `./.config-app/`.

## Azure app registration

- **Sending** channel/chat/user messages requires a **delegated** token — Microsoft
  Graph only allows app-only tokens to send for *migration*. Grant the delegated
  scopes `ChannelMessage.Send` and `Chat.ReadWrite`; reading messages also needs
  `ChannelMessage.Read.All` (**admin consent**), and `/me` needs `User.Read`.
- **Web app (auth code)**: add a **"Web"** redirect URI matching `TEAMS_REDIRECT_URI`;
  add a **client secret** if the app is confidential (omit for public/PKCE). A
  mismatch yields `AADSTS7000218` (secret missing) or `AADSTS700025` (secret sent by
  a public client).
- **CLI (username/password, ROPC)**: for a **public** client, enable **"Allow public
  client flows"** and configure **no** secret; for a **confidential** client, set a
  client secret and leave public-client flows **off** (otherwise Entra returns
  `AADSTS700025`/`AADSTS7000218`). ROPC works **only** with Entra work/school accounts
  **without MFA**,
  Conditional Access, SSO, or federation (pass-through auth is OK), and **not** with
  personal Microsoft accounts. The authority must be a specific tenant or
  `organizations` — never `common`/`consumers`. A tenant admin must pre-consent to the
  delegated scopes (ROPC shows no consent prompt). Scopes used: `ChannelMessage.Send`,
  `Chat.ReadWrite`, `ChannelMessage.Read.All`, `Team.ReadBasic.All`,
  `Channel.ReadBasic.All`, `User.Read`.
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
