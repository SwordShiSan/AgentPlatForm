/* Generated from contracts. Do not edit manually. */
export interface paths {
    "/api/v1/health": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Check application shell health */
        get: operations["getHealth"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/readiness": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Check baseline schema and required dependency connectivity */
        get: operations["getReadiness"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/csrf": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Create or rotate a session-bound CSRF token (local identity profile) */
        get: operations["getCsrf"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["login"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMe"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["logout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/models": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getModels"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getConversations"];
        put?: never;
        post: operations["createConversation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getConversation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/snapshot": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getConversationSnapshot"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/tasks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["submitChatTask"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTask"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/usage": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTaskUsage"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cancelTask"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getConversationEvents"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/preferences": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["chatPreferences"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateChatPreferences"];
        trace?: never;
    };
    "/api/v1/effective-config": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["effectiveChatConfig"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/model-credentials": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["modelCredentials"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/model-credentials/deepseek": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["putUserModelCredential"];
        post?: never;
        delete: operations["revokeUserModelCredential"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/configuration": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["taskConfiguration"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/diagnostics": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTaskDiagnostics"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/reconciliation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTaskReconciliation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/recovery": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTaskRecovery"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listWorkspaces"];
        put?: never;
        post: operations["createWorkspace"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getWorkspace"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces/{id}/retry": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["retryWorkspace"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        LoginRequest: {
            username: string;
            password: string;
        };
        CsrfResponse: {
            csrfToken: string;
        };
        MeResponse: {
            /** Format: uuid */
            id: string;
            username: string;
            platformAdmin: boolean;
            spaces: components["schemas"]["SpaceResponse"][];
        };
        SpaceResponse: {
            /** Format: uuid */
            id: string;
            /** @enum {string} */
            type: "PERSONAL" | "ORGANIZATION";
            /** @enum {string} */
            role: "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";
            objectLimitBytes: number;
        };
        ReadinessResponse: {
            /** @enum {string} */
            status: "READY" | "NOT_READY";
        };
        HealthResponse: {
            /** @enum {string} */
            status: "UP";
        };
        Problem: {
            /** Format: uri */
            type: string;
            title: string;
            status: number;
            code: string;
            detail?: string;
            traceId: string;
            retryable: boolean;
            resourceId?: string;
        };
        ChatModel: {
            /** Format: uuid */
            id: string;
            name: string;
            capabilities: "TEXT"[];
            available: boolean;
        };
        ChatModels: {
            items: components["schemas"]["ChatModel"][];
        };
        ChatConversation: {
            /** Format: uuid */
            id: string;
            title: string;
            version: number;
            /** Format: date-time */
            updatedAt: string;
            initialTask?: components["schemas"]["ChatAccepted"] | null;
        };
        ChatConversations: {
            items: components["schemas"]["ChatConversation"][];
            nextCursor: string | null;
        };
        CreateConversation: {
            title?: string;
            workspaceId?: null;
            initialTask?: components["schemas"]["SubmitChatTask"];
        };
        ChatTask: {
            /** Format: uuid */
            taskId: string;
            /** Format: uuid */
            conversationId: string;
            /** @enum {string} */
            status: "QUEUED" | "RUNNING" | "BLOCKED" | "SUCCEEDED" | "FAILED" | "CANCELLED";
            cancelRequested: boolean;
            reasonCode: string | null;
            version: number;
        };
        ChatAccepted: {
            /** Format: uuid */
            taskId: string;
            /** Format: uuid */
            conversationId: string;
            /** @enum {string} */
            status: "QUEUED" | "RUNNING" | "BLOCKED" | "SUCCEEDED" | "FAILED" | "CANCELLED";
            cursor: number;
        };
        SubmitChatTask: {
            input: {
                text: string;
                attachments?: unknown[];
            };
            options: {
                /** Format: uuid */
                modelId?: string;
                /** @constant */
                planMode?: false;
                /** @constant */
                permissionMode?: "RISK_BASED";
                /** @enum {string} */
                credentialSource?: "PLATFORM" | "USER";
            };
        };
        ChatTextBlock: {
            /** @constant */
            type: "TEXT";
            /** Format: uuid */
            blockId: string;
            text: string;
        };
        ChatMessage: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            taskId: string;
            /** @enum {string} */
            role: "USER" | "ASSISTANT";
            blocks: components["schemas"]["ChatTextBlock"][];
            /** @enum {string} */
            status: "COMPLETED" | "STREAMING" | "PARTIAL" | "FAILED";
            version: number;
        };
        ChatSnapshot: {
            /** @constant */
            schemaVersion: 1;
            cursor: number;
            messages: components["schemas"]["ChatMessage"][];
            tasks: components["schemas"]["ChatTask"][];
            pending: unknown[];
            artifacts: unknown[];
        };
        ChatUsage: {
            /** Format: uuid */
            callId: string;
            /** @enum {string} */
            status: "PREPARED" | "STARTED" | "COMPLETED" | "FAILED" | "UNKNOWN";
            inputTokens: number | null;
            outputTokens: number | null;
            costMicros: number | null;
            estimated: boolean;
        };
        ChatUsages: {
            items: components["schemas"]["ChatUsage"][];
        };
        ChatPreferences: {
            version: number;
            /** Format: uuid */
            defaultModelId: string | null;
            /** @enum {string} */
            defaultCredentialSource: "PLATFORM" | "USER";
        };
        UpdateChatPreferences: {
            /** Format: uuid */
            defaultModelId?: string | null;
            /** @enum {string} */
            defaultCredentialSource?: "PLATFORM" | "USER";
        };
        ModelCredentials: {
            version: number;
            userConfigured: boolean;
            platformAvailable: boolean;
        };
        PutModelCredential: {
            apiKey: string;
        };
        EffectiveChatConfig: {
            values: {
                /** Format: uuid */
                modelId: string;
                /** @enum {string} */
                credentialSource: "PLATFORM" | "USER";
            };
            sources: {
                modelId: string;
                credentialSource: string;
            };
            lockedFields: string[];
            configHash: string;
        };
        TaskConfiguration: {
            /** Format: uuid */
            configSnapshotId: string;
            configHash: string;
            /** Format: uuid */
            bundleId: string;
            values: {
                [key: string]: unknown;
            };
            restrictions: {
                credentialActive: boolean;
                modelActive: boolean;
            };
        };
        TaskDiagnostics: {
            /** Format: uuid */
            taskId: string;
            /** Format: uuid */
            traceId: string;
            code: string | null;
            /** @enum {string} */
            classification: "NONE" | "CANCELLED" | "USER_ACTION" | "POLICY_DENIED" | "TRANSIENT_SAFE_RETRY" | "UNKNOWN_SIDE_EFFECT" | "BUG";
            hasPartialOutput: boolean;
            /** @enum {string} */
            suggestedAction: "NONE" | "CHECK_ACCESS_AND_CREDENTIAL" | "CHECK_CONFIGURATION_AND_BUDGET" | "CHECK_SERVICE_STATUS" | "CONTACT_OPERATOR" | "CONTACT_OPERATOR_FOR_RECONCILIATION";
            /** @constant */
            automaticReplayAllowed: false;
        };
        CallReconciliation: {
            /** Format: uuid */
            callId: string;
            /** @enum {string} */
            status: "PREPARED" | "STARTED" | "COMPLETED" | "FAILED" | "UNKNOWN";
            /** @enum {string} */
            reservationState: "RESERVED" | "RECONCILING" | "SETTLED" | "RELEASED";
            /** Format: int64 */
            inputTokens: number | null;
            /** Format: int64 */
            outputTokens: number | null;
            /** Format: int64 */
            costMicros: number | null;
            /** Format: int64 */
            revision: number;
        };
        TaskReconciliation: {
            /** Format: uuid */
            taskId: string;
            /** @enum {string} */
            stopStage: "NOT_REQUESTED" | "STOPPING" | "UNCONFIRMED" | "STOPPED";
            stopConfirmed: boolean;
            unknownCalls: number;
            calls: components["schemas"]["CallReconciliation"][];
            /** @enum {string} */
            suggestedAction: "NONE" | "WAIT_FOR_STOP" | "CONTACT_OPERATOR_FOR_RECONCILIATION";
            excludedFromContext: boolean;
            /** Format: int64 */
            version: number;
        };
        TaskRecovery: {
            /** Format: uuid */
            taskId: string;
            /** @description Metadata-only candidate requiring trusted operator integrity validation; not permission to execute */
            eligible: boolean;
            /** @enum {string} */
            stage: "AVAILABLE" | "QUEUED" | "RESTORING" | "RUNNING" | "COMPLETED" | "BLOCKED";
            reasonCode: string | null;
            revision: number;
            taskVersion: number;
            /** Format: uuid */
            checkpointId: string | null;
            /** Format: uuid */
            traceId: string;
        };
        CreateWorkspace: {
            name: string;
        };
        Workspace: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            spaceId: string;
            name: string;
            /** @enum {string} */
            state: "PROVISIONING" | "READY" | "ERROR" | "BLOCKED";
            /** @constant */
            environmentState: "NOT_CREATED";
            /** @constant */
            quotaBytes: 2147483648;
            /** @constant */
            quotaInodes: 100000;
            version: number;
            /** Format: uuid */
            operationId: string;
            errorCode: string | null;
            /** Format: date-time */
            createdAt: string;
            /** @constant */
            fileAccess: false;
            /** @constant */
            shellAccess: false;
        };
        WorkspacePage: {
            items: components["schemas"]["Workspace"][];
            /** Format: uuid */
            nextCursor: string | null;
        };
    };
    responses: {
        /** @description Identity request rejected (400/401/403/404/409/429/503) */
        IdentityProblem: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
    };
    parameters: {
        /** @description Session-bound token; writes also require an exact allowed Origin */
        CsrfToken: string;
        /** @description Optional selector; current membership and credential policy are always checked. */
        SpaceSelector: string;
        /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
        IdempotencyKey: string;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getHealth: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The application shell is available */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HealthResponse"];
                };
            };
            /** @description Structured failure */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getReadiness: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Baseline dependencies reachable and schema compatible */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReadinessResponse"];
                };
            };
            /** @description Baseline unavailable or not configured */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReadinessResponse"];
                };
            };
        };
    };
    getCsrf: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description CSRF token and HttpOnly session cookie; never cached */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CsrfResponse"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    login: {
        parameters: {
            query?: never;
            header: {
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description Session and CSRF rotated; opaque cookie is not in JSON */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CsrfResponse"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getMe: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional space selector; membership is always verified */
                "X-Space-Id"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current actor and authorized space summaries only */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MeResponse"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    logout: {
        parameters: {
            query?: never;
            header: {
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Session revoked and cookie cleared */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getModels: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized committed platform state; no provider secrets or internal reasoning */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatModels"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getConversations: {
        parameters: {
            query?: {
                cursor?: string;
                limit?: number;
            };
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized committed platform state; no provider secrets or internal reasoning */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatConversations"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    createConversation: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateConversation"];
            };
        };
        responses: {
            /** @description Authorized committed platform state; no provider secrets or internal reasoning */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatConversation"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getConversation: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized committed platform state; no provider secrets or internal reasoning */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatConversation"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getConversationSnapshot: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized committed platform state; no provider secrets or internal reasoning */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatSnapshot"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    submitChatTask: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SubmitChatTask"];
            };
        };
        responses: {
            /** @description Authorized committed platform state; no provider secrets or internal reasoning */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatAccepted"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getTask: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized committed platform state; no provider secrets or internal reasoning */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatTask"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getTaskUsage: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized committed platform state; no provider secrets or internal reasoning */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatUsages"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    cancelTask: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": Record<string, never>;
            };
        };
        responses: {
            /** @description Cancel intent committed; execution is not stopped until confirmed. Repeated intent is idempotent; prior terminal success remains unchanged. Unknown remote outcome retains reservations and is never replayed. */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatTask"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getConversationEvents: {
        parameters: {
            query?: {
                /** @description Committed cursor, default0; wins over Last-Event-ID. Malformed/unsafe integer400; negative/ahead409; below persisted pruned-through watermark410. Equality with watermark is valid. Rebase from snapshot on409/410. */
                after?: number;
            };
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                "Last-Event-ID"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description SSE event platform_event; id is seq. Only committed events, Unicode codepoint offsets. Disconnect never cancels. Eight streams maximum; excess503 STREAM_CAPACITY_EXCEEDED. Lifetime25s, heartbeat2s; reconnect from applied cursor. Each page at most256 events/1MiB, oversized503 EVENT_STREAM_OVERSIZED requires snapshot. Preflight failures use Problem; after headers commit close the stream on failure, never emit a business failure or Problem as an event. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "text/event-stream": string;
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    chatPreferences: {
        parameters: {
            query?: never;
            header?: {
                "X-Space-Id"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatPreferences"];
                };
            };
            /** @description Problem */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    updateChatPreferences: {
        parameters: {
            query?: never;
            header: {
                "If-Match": string;
                "X-CSRF-Token": string;
                "X-Space-Id"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateChatPreferences"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChatPreferences"];
                };
            };
            /** @description Problem */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    effectiveChatConfig: {
        parameters: {
            query?: never;
            header?: {
                "X-Space-Id"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EffectiveChatConfig"];
                };
            };
            /** @description Problem */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    modelCredentials: {
        parameters: {
            query?: never;
            header?: {
                "X-Space-Id"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ModelCredentials"];
                };
            };
            /** @description Problem */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    putUserModelCredential: {
        parameters: {
            query?: never;
            header: {
                "If-Match": string;
                "X-CSRF-Token": string;
                "X-Space-Id"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PutModelCredential"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ModelCredentials"];
                };
            };
            /** @description Problem */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    revokeUserModelCredential: {
        parameters: {
            query?: never;
            header: {
                "If-Match": string;
                "X-CSRF-Token": string;
                "X-Space-Id"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ModelCredentials"];
                };
            };
            /** @description Problem */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    taskConfiguration: {
        parameters: {
            query?: never;
            header?: {
                "X-Space-Id"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskConfiguration"];
                };
            };
            /** @description Problem */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getTaskDiagnostics: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized committed platform state; no provider secrets or internal reasoning */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskDiagnostics"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getTaskReconciliation: {
        parameters: {
            query?: never;
            header?: {
                "X-Space-Id"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Live-authorized read-only cancellation and reconciliation projection; no evidence content, provider secrets or internal paths */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskReconciliation"];
                };
            };
            /** @description Problem */
            default: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getTaskRecovery: {
        parameters: {
            query?: never;
            header?: {
                "X-Space-Id"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Read-only metadata candidate; trusted operator and claim must verify full state integrity before restoration */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskRecovery"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    listWorkspaces: {
        parameters: {
            query?: {
                cursor?: string;
                limit?: number;
            };
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized workspace storage state; environment execution remains unavailable */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WorkspacePage"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    createWorkspace: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateWorkspace"];
            };
        };
        responses: {
            /** @description Authorized workspace storage state; environment execution remains unavailable */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Workspace"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getWorkspace: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized workspace storage state; environment execution remains unavailable */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Workspace"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    retryWorkspace: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "If-Match": string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized workspace storage state; environment execution remains unavailable */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Workspace"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
}
