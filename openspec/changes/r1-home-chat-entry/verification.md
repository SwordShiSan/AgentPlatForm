# Verification

Local gates PASS on source d7707acc045c; promotion/cloud pending. Do not mark cloud VERIFIED before its evidence.

| Gate | Assertions | Result |
|---|---|---|
| Baseline/spec | main cd30c22; remote phase1621965 has same tree; Actions disabled/runs0; strict change passed before code; cloud source28da397, original gateway34421f/API6853788; root free17517137920bytes | PASS |
| Engineering | frontend typecheck/unit/build, contract generation and invalid fixtures, links, OpenSpec strict | PASS |
| Both flags | anonymous/authenticated / and /app; login landing; public /status; flag-off root/account compatibility | PASS |
| Real local API | login/refresh/logout/session loss, separate users, existing conversation deep link/SSE, dependency failure without loops; no submissions | PASS |
| Accessibility | 375/768/1440,200% zoom, keyboard, light/dark navigation and accurate copy | PASS |
| Promotion | exact source, protected step→phase→main PRs, no hosted Actions | NOT_RUN |
| Cloud delivery | same archive/config/platform identities,>=5GiB root,32MiB gateway,no backend/container/data change, prior gateway/config saved | NOT_RUN |
| Cloud smoke | public root/login/chat/refresh/account/status/logout, IP/Host routing, internal endpoints denied, existing SSE read;0 model calls | NOT_RUN |
| Closeout | new production R1 tag only after gates, archived change, phase sync, ledgers, local/cloud matching hash packages >=30 days | NOT_RUN |

Failure paths include anonymous access, expired session, auth/network outage, disabled flag, navigation at small/zoom widths and rejected cloud candidate. Retain FAIL/NOT_RUN evidence and previous production identity until corrected.

Local result: typecheck/build both flags PASS;22 unit tests,10 flag-off browser tests and5 flag-on browser tests PASS. Existing real local API/PG/MQ;314 conversations,338 tasks,372 model calls and1671168 reserved micros unchanged. No worker started or provider request sent. Windows sandbox EPERM retained; same22 tests passed under normal host permissions. MQ cold-start wait timeout retained; later healthy0 restarts/no OOM. Gateway config sha256:a926aced9585adfeef570e7da06a2f088cc0ba5c22a0574215835927e3de3468; export hash 14a6c1753646b6915b3a243e2da0803bb1a597b6d0195ae93475246dae7e7cc4. Evidence hashes: deploy/r1-home-entry-acceptance.json.
