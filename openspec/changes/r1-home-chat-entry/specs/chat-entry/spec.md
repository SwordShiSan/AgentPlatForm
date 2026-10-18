## ADDED Requirements

### Requirement: Feature-aware application entry
The frontend SHALL route chat-enabled / and /app to /app/chat using the existing authenticated route guard. Login SHALL enter /app. Chat-disabled builds SHALL retain the health root and account landing.

#### Scenario: Anonymous root and successful login
- **WHEN** an anonymous visitor opens / in a chat-enabled build and logs in successfully
- **THEN** the visitor sees login followed directly by chat, without creating a conversation or model call

#### Scenario: Authenticated root, refresh and deep link
- **WHEN** an authenticated visitor opens / or /app, refreshes, or opens an existing conversation URL
- **THEN** chat remains reachable and the requested existing conversation retains its stable identity

#### Scenario: Disabled chat and dependency failure
- **WHEN** chat is disabled or authentication dependencies fail
- **THEN** the disabled build retains its original health/account behavior, and failures never expose protected data or cause a redirect loop

### Requirement: Discoverable navigation and health route
The frontend SHALL expose /status publicly and provide accessible header links to chat, account and connection status according to the chat feature flag. Enabled copy SHALL describe delivered features accurately.

#### Scenario: Accessible navigation
- **WHEN** the visitor uses header links at mobile/desktop widths, keyboard navigation, light/dark themes or 200 percent zoom
- **THEN** login/chat/account/status remain usable without horizontal overflow or exposure of another user's data

### Requirement: Gateway-only immutable delivery
The release SHALL replace only the gateway with the locally verified immutable frontend artifact, retain a rollback identity and protect existing backend/data. Acceptance SHALL not send model requests.

#### Scenario: Successful promotion and cloud smoke
- **WHEN** local gates and step-to-phase-to-main PRs pass and the same verified gateway archive is imported
- **THEN** public root/login/chat/status, existing SSE and private endpoint boundaries pass, the release records separate frontend/backend provenance, and a new immutable R1 production tag is created

#### Scenario: Failed cloud validation
- **WHEN** cloud capacity or gateway/browser verification fails
- **THEN** deployment stops or restores the previous gateway and deployment manifest without restarting backend services or changing user data
