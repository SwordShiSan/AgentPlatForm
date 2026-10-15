let csrfToken: string | undefined;
export function sessionContext() { return { csrfToken }; }
export function setCsrf(value: string | undefined) { csrfToken = value; }
