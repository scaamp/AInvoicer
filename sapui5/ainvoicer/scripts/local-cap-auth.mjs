// Wyłącznie narzędzie testowe Node/CDP; aplikacja UI5 nie importuje tego modułu.
export function localCapAuth(env, pageUrl) {
    const user = env.AINV_CAP_LOCAL_TEST_USER;
    const password = env.AINV_CAP_LOCAL_TEST_PASSWORD;
    if (user === undefined && password === undefined) { return null; }
    const target = new URL(pageUrl);
    if (target.protocol !== "http:" || !["localhost", "127.0.0.1", "[::1]"].includes(target.hostname)
            || target.username || target.password || !user || !password || /[:\r\n]/.test(user) || /[\r\n]/.test(password)) {
        throw new Error("Local CAP test authentication requires loopback HTTP and both environment variables");
    }
    const authorization = "Basic " + Buffer.from(user + ":" + password).toString("base64");
    return {
        // CDP nie umieszcza credentiala w JS strony. Każdy redirect i każdy obcy kanał jest oczyszczany.
        headers(params) {
            const request = params.request;
            const uri = new URL(request.url);
            const headers = Object.entries(request.headers)
                .filter(([name]) => name.toLowerCase() !== "authorization")
                .map(([name, value]) => ({ name, value }));
            if (!params.redirectedRequestId && uri.origin === target.origin
                    && !uri.username && !uri.password && !/%|\\/.test(uri.pathname)
                    && uri.pathname.startsWith("/odata/v4/KsefService/")
                    && ["GET", "HEAD"].includes(request.method)) {
                headers.push({ name: "Authorization", value: authorization });
            }
            return headers;
        },
        redact(text) {
            return [authorization, authorization.substring(6), password, user]
                .reduce((result, secret) => result.split(secret).join("[REDACTED]"), String(text));
        }
    };
}
