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
    "/api/v1/workspaces/{id}/environment": {
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
        get: operations["getWorkspaceEnvironment"];
        put?: never;
        post: operations["prepareWorkspaceEnvironment"];
        delete: operations["deleteWorkspaceEnvironment"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces/{id}/environment/retry": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["retryWorkspaceEnvironment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces/{id}/environment/sleep": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["sleepWorkspaceEnvironment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces/{id}/environment/wake": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["wakeWorkspaceEnvironment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces/{id}/environment/operations/{operationId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getEnvironmentOperation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces/{id}/environment/requests/{requestId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getEnvironmentRequest"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces/{id}/files": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listWorkspaceFiles"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getWorkspaceFile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/{id}/content": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["downloadFileVersion"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces/{id}/uploads": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Replacement requires fileId,baseVersionId and If-Match; no implicit overwrite. */
        post: operations["createFileUpload"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/uploads/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getFileUpload"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/uploads/{id}/content": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** @description Exact declared bytes and SHA256 frozen in upload; uploadId is the content identity. Uncertain transfer is queried, not automatically resent. */
        put: operations["transferFileUpload"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/uploads/{id}/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["completeFileUpload"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/file-operations/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getFileOperation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/workspaces/{id}/storage-usage": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getFileStorageUsage"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/plan": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTaskPlan"];
        put: operations["editTaskPlan"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/interactions/{interactionId}/answer": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["answerTaskInteraction"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/usage-quota": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getMyUsageQuota"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/permissions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTaskPermissions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["updateTaskPermissions"];
        trace?: never;
    };
    "/api/v1/tasks/{id}/permission-scopes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTaskPermissionScopes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/grants": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["createTaskGrant"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/grants/{grantId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["revokeTaskGrant"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tasks/{id}/operations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getTaskOperations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/operations/{id}/result": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getOperationResult"];
        put?: never;
        post?: never;
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
        TaskDirectoryGrant: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            scopeId: string;
            path: string;
            capabilities: ("LIST" | "READ" | "WRITE")[];
            /** Format: date-time */
            expiresAt: string;
            /** Format: date-time */
            revokedAt: string | null;
            active: boolean;
            policyVersion: string;
        };
        TaskPermissions: {
            /** Format: uuid */
            taskId: string;
            /** Format: uuid */
            workspaceId: string | null;
            /**
             * @default RISK_BASED
             * @enum {string}
             */
            mode: "RISK_BASED" | "FULL";
            version: number;
            policyVersion: string;
            mutable: boolean;
            effectiveRestrictions: string[];
            grants: components["schemas"]["TaskDirectoryGrant"][];
        };
        TaskPermissionScope: {
            /** Format: uuid */
            scopeId: string;
            path: string;
            capabilities: ("LIST" | "READ" | "WRITE")[];
        };
        TaskPermissionScopes: {
            items: components["schemas"]["TaskPermissionScope"][];
            /** Format: uuid */
            nextCursor: string | null;
        };
        TaskPermissionPatch: {
            /**
             * @default RISK_BASED
             * @enum {string}
             */
            mode: "RISK_BASED" | "FULL";
        };
        TaskGrantRequest: {
            /** Format: uuid */
            scopeId: string;
            capabilities: ("LIST" | "READ" | "WRITE")[];
        };
        CreateFileUpload: {
            path: string;
            bytes: number;
            sha256: string;
            /** Format: uuid */
            fileId?: string;
            /** Format: uuid */
            baseVersionId?: string;
        };
        FileUpload: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            workspaceId: string;
            /** Format: uuid */
            fileId: string;
            /** Format: uuid */
            versionId: string;
            path: string;
            bytes: number;
            sha256: string;
            /** @enum {string} */
            state: "CREATED" | "UPLOADING" | "VERIFYING" | "VERIFIED" | "QUEUED" | "COMMITTED" | "ABORTED" | "EXPIRED" | "UNKNOWN";
            /** Format: uuid */
            operationId: string | null;
            revision: number;
            /** Format: date-time */
            expiresAt: string;
            allowedActions: ("UPLOAD" | "COMPLETE" | "READ")[];
        };
        FileItem: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            workspaceId: string;
            path: string;
            parent: string;
            name: string;
            /** @enum {string} */
            kind: "FILE" | "DIRECTORY";
            /** Format: uuid */
            versionId: string | null;
            /** @enum {string} */
            collectionState: "PENDING" | "CONFIRMED" | "CHANGED" | "BLOCKED";
            revision: number;
            sha256: string | null;
            bytes: number | null;
        };
        FilePage: {
            items: components["schemas"]["FileItem"][];
            nextCursor: string | null;
        };
        FileOperation: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            workspaceId: string;
            /** Format: uuid */
            uploadId: string | null;
            /** @enum {string} */
            state: "PENDING" | "CLAIMED" | "RUNNING" | "COMMITTED" | "FAILED" | "UNKNOWN";
            revision: number;
            errorCode: string | null;
            allowedActions: "READ"[];
        };
        FileStorageUsage: {
            objectLimitBytes: number;
            reservedBytes: number;
            committedBytes: number;
            workspaceQuotaBytes: number;
            workspaceQuotaInodes: number;
            measuredBytes: number | null;
            measuredInodes: number | null;
            /** Format: date-time */
            measuredAt: string | null;
        };
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
            status: "QUEUED" | "RUNNING" | "WAITING_INPUT" | "BLOCKED" | "SUCCEEDED" | "FAILED" | "CANCELLED";
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
            status: "QUEUED" | "RUNNING" | "WAITING_INPUT" | "BLOCKED" | "SUCCEEDED" | "FAILED" | "CANCELLED";
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
                /** @default false */
                planMode: boolean;
                /** Format: uuid */
                workspaceId?: string | null;
                /**
                 * @default RISK_BASED
                 * @enum {string}
                 */
                permissionMode: "RISK_BASED" | "FULL";
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
            pending: components["schemas"]["PendingInteraction"][];
            artifacts: unknown[];
            /** @description Bounded committed tool projection in the snapshot transaction. More operations use the authorized per-task page. */
            operations?: components["schemas"]["TaskToolOperation"][];
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
        EmptyEnvironmentCommand: Record<string, never>;
        WorkspaceEnvironment: {
            /** Format: uuid */
            workspaceId: string;
            /** @enum {string} */
            state: "NOT_CREATED" | "PREPARING" | "READY" | "STOPPING" | "STOPPED" | "DELETING" | "DELETED" | "ERROR" | "BLOCKED";
            /** @enum {string} */
            phase: "NONE" | "PREPARING" | "READY" | "STOPPING" | "STOPPED" | "DELETING" | "DELETED" | "ERROR" | "BLOCKED";
            generation: number;
            version: number;
            /** Format: uuid */
            operationId: string | null;
            errorCode: string | null;
            allowedActions: ("PREPARE" | "RETRY" | "SLEEP" | "WAKE" | "DELETE")[];
        } & (unknown & unknown & unknown & unknown & unknown & unknown & unknown);
        EnvironmentOperation: {
            /** Format: uuid */
            operationId: string;
            /** Format: uuid */
            workspaceId: string;
            /** @enum {string} */
            intent: "PREPARE" | "WAKE" | "STOP" | "SLEEP" | "DELETE";
            /** @enum {string} */
            status: "DRAINING" | "PENDING" | "CLAIMED" | "RUNNING" | "COMMITTED" | "FAILED" | "UNKNOWN";
            generation: number;
            revision: number;
            errorCode: string | null;
        };
        EnvironmentSubmission: {
            /** Format: uuid */
            operationId: string;
            environment: components["schemas"]["WorkspaceEnvironment"];
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
            /** @enum {string} */
            environmentState: "NOT_CREATED" | "PREPARING" | "READY" | "STOPPING" | "STOPPED" | "DELETING" | "DELETED" | "ERROR" | "BLOCKED";
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
        PlanRevision: {
            /** Format: uuid */
            id: string;
            revision: number;
            title: string;
            markdown: string;
            /** @enum {unknown} */
            source: "MODEL" | "USER";
            hash: string;
            /** Format: date-time */
            createdAt: string;
        };
        TaskPlan: {
            /** Format: uuid */
            taskId: string;
            /** @enum {unknown} */
            status: "NOT_CREATED" | "DRAFT" | "REVIEW" | "APPROVED" | "DELIVERED" | "REJECTED";
            version: number;
            current: components["schemas"]["PlanRevision"] | null;
            revisions: components["schemas"]["PlanRevision"][];
            /**
             * @description Derived from the frozen capability catalog, current accepted plan revision and task state. Plan consent does not grant tool or resource permission.
             * @enum {unknown}
             */
            executionPhase?: "TEXT_ONLY" | "PLANNING" | "AWAITING_REVIEW" | "EXECUTING" | "COMPLETED" | "DELIVERED";
        };
        EditTaskPlan: {
            title: string;
            markdown: string;
        };
        PendingInteraction: {
            /** Format: uuid */
            interactionId: string;
            /** Format: uuid */
            taskId: string;
            version: number;
            /** Format: date-time */
            expiresAt: string;
            /** @constant */
            kind: "PLAN_APPROVAL";
            /** @constant */
            optional: false;
            plan: components["schemas"]["PlanRevision"];
            question: null;
            options: {
                id: string;
                label: string;
            }[];
            approval?: null;
        } | {
            /** Format: uuid */
            interactionId: string;
            /** Format: uuid */
            taskId: string;
            version: number;
            /** Format: date-time */
            expiresAt: string;
            /** @constant */
            kind: "QUESTION";
            optional: boolean;
            plan: null;
            question: string;
            options: {
                id: string;
                label: string;
            }[];
            approval?: null;
        } | {
            /** Format: uuid */
            interactionId: string;
            /** Format: uuid */
            taskId: string;
            version: number;
            /** Format: date-time */
            expiresAt: string;
            /** @constant */
            kind: "TOOL_APPROVAL";
            /** @constant */
            optional: false;
            plan: null;
            question: null;
            options: {
                id: string;
                label: string;
            }[];
            approval: components["schemas"]["ToolApprovalPayload"];
        };
        ToolApprovalPayload: {
            /** Format: uuid */
            operationId: string;
            toolName: string;
            summary: string;
            reasonCode: string;
            /** Format: uuid */
            workspaceId: string | null;
            target: string | null;
            targetVersion: string | null;
            callHash: string;
            stale: boolean;
            allowedActions: ("ALLOW_ONCE" | "DENY")[];
            permissionVersion: number;
            policyVersion: string;
            review?: components["schemas"]["WorkspaceToolReview"] | null;
        };
        AnswerInteraction: {
            expectedVersion: number;
            /** @enum {unknown} */
            decision: "EXECUTE_PLAN" | "REVISE_PLAN" | "REJECT_PLAN" | "DELIVER_PLAN" | "ANSWER" | "SKIP" | "ALLOW_ONCE" | "DENY";
            text?: string;
            optionIds?: string[];
        } & unknown;
        InteractionAccepted: {
            /** Format: uuid */
            taskId: string;
            /** Format: uuid */
            interactionId: string;
            /** @enum {unknown} */
            status: "QUEUED" | "WAITING_INPUT" | "RUNNING" | "SUCCEEDED";
            version: number;
        };
        QuotaView: {
            /** @enum {unknown} */
            metric: "TOKENS" | "COST_MICROS";
            unlimited: boolean;
            limit: number | null;
            used: number;
            reserved: number;
            remaining: number | null;
            /** Format: date-time */
            resetAt: string;
            policyVersion: number;
        };
        WorkspaceToolReview: {
            /** @enum {unknown} */
            kind: "LIST" | "READ" | "WRITE" | "SHELL";
            command: string | null;
            cwd: string | null;
            path: string | null;
            /** Format: uuid */
            fileId: string | null;
            /** Format: uuid */
            versionId: string | null;
            /** @enum {string|null} */
            writeMode: "CREATE" | "REPLACE" | null;
            textSha256: string | null;
            bytes: number | null;
            outputs: {
                path: string;
                maxBytes: number;
            }[];
            /** @enum {unknown} */
            impact: "WORKSPACE_INDEX" | "EXPLICIT_VERSION" | "NAMED_FILE" | "ENTIRE_BOUND_WORKSPACE";
            /** @constant */
            network: "NONE";
            stopsAfterEachCall: boolean;
        };
        TaskToolOperation: {
            /** Format: uuid */
            operationId: string;
            /** Format: uuid */
            taskId: string;
            /** @enum {unknown} */
            toolName: "workspace_list" | "workspace_read" | "workspace_write" | "workspace_shell";
            /** @enum {unknown} */
            status: "PREPARED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "UNKNOWN" | "DENIED";
            /** @enum {unknown} */
            phase: "PREPARED" | "WAITING_APPROVAL" | "ENVIRONMENT_PREPARING" | "RUNNING" | "STOPPING" | "SEALING" | "SUCCEEDED" | "FAILED" | "UNKNOWN" | "DENIED";
            summary: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            endedAt: string | null;
            errorCode: string | null;
            traceId: string | null;
            review: components["schemas"]["WorkspaceToolReview"] | null;
            resultAvailable: boolean;
        };
        TaskToolOperations: {
            items: components["schemas"]["TaskToolOperation"][];
            /** Format: uuid */
            nextCursor: string | null;
        };
        WorkspaceToolArtifact: {
            /** Format: uuid */
            fileId: string;
            /** Format: uuid */
            versionId: string;
            path: string;
            bytes: number;
            sha256: string;
        };
        /** @description Live-authorized bounded tool result. Raw stdout/stderr UTF-8 bytes combined are at most64KiB; a large result is fetched from private storage without exposing its key. Model summary is at most8KiB UTF-8. No host paths, credentials, SDK state or private parameter maps. */
        WorkspaceToolResult: {
            /** Format: uuid */
            operationId: string;
            /** Format: uuid */
            taskId: string;
            /** @enum {unknown} */
            status: "PREPARED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "UNKNOWN" | "DENIED";
            summary: string;
            exitCode: number | null;
            stdout: string | null;
            stderr: string | null;
            truncated: boolean;
            errorCode: string | null;
            files: {
                /** Format: uuid */
                fileId: string;
                /** Format: uuid */
                versionId: string;
                path: string;
                bytes: number;
                sha256: string;
            }[];
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
        EnvironmentMatch: string;
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
    getWorkspaceEnvironment: {
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
            /** @description Independent environment projection; absent environment is NOT_CREATED revision0 without allocation */
            200: {
                headers: {
                    /** @description Quoted environment revision */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WorkspaceEnvironment"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    prepareWorkspaceEnvironment: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "If-Match": components["parameters"]["EnvironmentMatch"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["EmptyEnvironmentCommand"];
            };
        };
        responses: {
            /** @description Durable prepare operation; repeat returns original operation identity separately from current projection */
            202: {
                headers: {
                    /** @description Quoted environment revision */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EnvironmentSubmission"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    deleteWorkspaceEnvironment: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "If-Match": components["parameters"]["EnvironmentMatch"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["EmptyEnvironmentCommand"];
            };
        };
        responses: {
            /** @description Remove only the execution environment; retain workspace, hard quota, files and immutable versions */
            202: {
                headers: {
                    /** @description Quoted environment revision */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EnvironmentSubmission"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    retryWorkspaceEnvironment: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "If-Match": components["parameters"]["EnvironmentMatch"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["EmptyEnvironmentCommand"];
            };
        };
        responses: {
            /** @description Retry only a proven failed operation with no unresolved effect or use */
            202: {
                headers: {
                    /** @description Quoted environment revision */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EnvironmentSubmission"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    sleepWorkspaceEnvironment: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "If-Match": components["parameters"]["EnvironmentMatch"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["EmptyEnvironmentCommand"];
            };
        };
        responses: {
            /** @description Stop and remove the execution environment; workspace/files/quota are retained */
            202: {
                headers: {
                    /** @description Quoted environment revision */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EnvironmentSubmission"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    wakeWorkspaceEnvironment: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "If-Match": components["parameters"]["EnvironmentMatch"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["EmptyEnvironmentCommand"];
            };
        };
        responses: {
            /** @description Prepare a new generation after confirmed stop; preserve workspace/files/quota */
            202: {
                headers: {
                    /** @description Quoted environment revision */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EnvironmentSubmission"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getEnvironmentOperation: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path: {
                id: string;
                operationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized original operation metadata without host/container identities */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EnvironmentOperation"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getEnvironmentRequest: {
        parameters: {
            query?: never;
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
            };
            path: {
                id: string;
                requestId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Resolve only this caller's original idempotency request after response loss */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EnvironmentOperation"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    listWorkspaceFiles: {
        parameters: {
            query?: {
                parent?: string;
                cursor?: string;
                limit?: number;
            };
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
            /** @description Authorized durable file projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FilePage"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getWorkspaceFile: {
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
            /** @description Authorized durable file projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FileItem"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    downloadFileVersion: {
        parameters: {
            query?: {
                versionId?: string;
            };
            header?: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                Range?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized immutable binary; safe attachment filename */
            200: {
                headers: {
                    ETag?: string;
                    "Accept-Ranges"?: "bytes";
                    [name: string]: unknown;
                };
                content: {
                    "application/octet-stream": string;
                };
            };
            /** @description Single byte range */
            206: {
                headers: {
                    "Content-Range"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/octet-stream": string;
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    createFileUpload: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                /** @description Reuse for an uncertain response. Same actor/space/path/key with another body returns409. */
                "Idempotency-Key": components["parameters"]["IdempotencyKey"];
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateFileUpload"];
            };
        };
        responses: {
            /** @description Authorized durable file projection */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FileUpload"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getFileUpload: {
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
            /** @description Authorized durable file projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FileUpload"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    transferFileUpload: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                "Content-Length": number;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/octet-stream": string;
            };
        };
        responses: {
            /** @description Authorized durable file projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FileUpload"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    completeFileUpload: {
        parameters: {
            query?: never;
            header: {
                /** @description Optional selector; current membership and credential policy are always checked. */
                "X-Space-Id"?: components["parameters"]["SpaceSelector"];
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Authorized durable file projection */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FileOperation"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getFileOperation: {
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
            /** @description Authorized durable file projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FileOperation"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getFileStorageUsage: {
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
            /** @description Authorized durable file projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FileStorageUsage"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getTaskPlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current authorized projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskPlan"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    editTaskPlan: {
        parameters: {
            query?: never;
            header: {
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                "If-Match": components["parameters"]["EnvironmentMatch"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EditTaskPlan"];
            };
        };
        responses: {
            /** @description Current authorized projection */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskPlan"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    answerTaskInteraction: {
        parameters: {
            query?: never;
            header: {
                /** @description Session-bound token; writes also require an exact allowed Origin */
                "X-CSRF-Token": components["parameters"]["CsrfToken"];
                "Idempotency-Key": string;
            };
            path: {
                id: string;
                interactionId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AnswerInteraction"];
            };
        };
        responses: {
            /** @description Answer durably accepted; execution resumes through MQ */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InteractionAccepted"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getMyUsageQuota: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Versioned daily quota; administrator unlimited is still metered */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["QuotaView"][];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getTaskPermissions: {
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
            /** @description Current task authorization projection */
            200: {
                headers: {
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskPermissions"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    updateTaskPermissions: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
                "If-Match": string;
                "X-Space-Id"?: string;
                "X-CSRF-Token": string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaskPermissionPatch"];
            };
        };
        responses: {
            /** @description Current task authorization projection */
            200: {
                headers: {
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskPermissions"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getTaskPermissionScopes: {
        parameters: {
            query?: {
                cursor?: string;
            };
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
            /** @description Current task authorization projection */
            200: {
                headers: {
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskPermissionScopes"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    createTaskGrant: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
                "If-Match": string;
                "X-Space-Id"?: string;
                "X-CSRF-Token": string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaskGrantRequest"];
            };
        };
        responses: {
            /** @description Current task authorization projection */
            200: {
                headers: {
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskPermissions"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    revokeTaskGrant: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
                "If-Match": string;
                "X-Space-Id"?: string;
                "X-CSRF-Token": string;
            };
            path: {
                id: string;
                grantId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current task authorization projection */
            200: {
                headers: {
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskPermissions"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getTaskOperations: {
        parameters: {
            query?: {
                cursor?: string;
                limit?: number;
            };
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
            /** @description Authorized committed tool projection; UNKNOWN never grants replay. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaskToolOperations"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
    getOperationResult: {
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
            /** @description Authorized committed tool projection; UNKNOWN never grants replay. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["WorkspaceToolResult"];
                };
            };
            default: components["responses"]["IdentityProblem"];
        };
    };
}
