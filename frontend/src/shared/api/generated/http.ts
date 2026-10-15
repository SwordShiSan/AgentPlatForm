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
}
