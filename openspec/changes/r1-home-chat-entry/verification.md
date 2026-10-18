# Verification

Initial status NOT_RUN. Do not mark VERIFIED before required local and cloud evidence.

| Gate | Assertions | Result |
|---|---|---|
| Baseline/spec | main cd30c22; remote phase1621965 has same tree; Actions disabled/runs0; strict change passed before code; cloud source28da397, original gateway34421f/API6853788; root free17517137920bytes | PASS |
| Engineering | frontend typecheck/unit/build, contract generation and invalid fixtures, links, OpenSpec strict | NOT_RUN |
| Both flags | anonymous/authenticated / and /app; login landing; public /status; flag-off root/account compatibility | NOT_RUN |
| Real local API | login/refresh/logout/session loss, separate users, existing conversation deep link/SSE, dependency failure without loops; no submissions | NOT_RUN |
| Accessibility | 375/768/1440,200% zoom, keyboard, light/dark navigation and accurate copy | NOT_RUN |
| Promotion | exact source, protected step→phase→main PRs, no hosted Actions | NOT_RUN |
| Cloud delivery | same archive/config/platform identities,>=5GiB root,32MiB gateway,no backend/container/data change, prior gateway/config saved | NOT_RUN |
| Cloud smoke | public root/login/chat/refresh/account/status/logout, IP/Host routing, internal endpoints denied, existing SSE read;0 model calls | NOT_RUN |
| Closeout | new production R1 tag only after gates, archived change, phase sync, ledgers, local/cloud matching hash packages >=30 days | NOT_RUN |

Failure paths include anonymous access, expired session, auth/network outage, disabled flag, navigation at small/zoom widths and rejected cloud candidate. Retain FAIL/NOT_RUN evidence and previous production identity until corrected.
