import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { localCapAuth } from "./local-cap-auth.mjs";

const env = { AINV_CAP_LOCAL_TEST_USER: "local-reader", AINV_CAP_LOCAL_TEST_PASSWORD: "synthetic-test-password" };
const base = "http://127.0.0.1:8086";
const cap = "/odata/v4/KsefService/";
const expected = "Basic " + Buffer.from(env.AINV_CAP_LOCAL_TEST_USER + ":" + env.AINV_CAP_LOCAL_TEST_PASSWORD).toString("base64");

test("authentication requires complete explicit opt-in on loopback", () => {
    assert.equal(localCapAuth({}, base), null);
    for (const input of [{ AINV_CAP_LOCAL_TEST_USER: "x" }, { AINV_CAP_LOCAL_TEST_PASSWORD: "x" },
        { ...env, AINV_CAP_LOCAL_TEST_PASSWORD: "" }, { ...env, AINV_CAP_LOCAL_TEST_USER: "a:b" }]) {
        assert.throws(() => localCapAuth(input, base));
    }
    for (const url of ["https://example.com", "http://127.0.0.1.evil.invalid", "http://user:pass@localhost", "https://localhost"]) {
        assert.throws(() => localCapAuth(env, url));
    }
});

test("only exact-origin CAP reads receive Authorization, redirects and other channels are stripped", () => {
    const auth = localCapAuth(env, base);
    for (const path of [cap + "$metadata", cap + "InvoiceIntegrationStatus?$count=true"]) {
        assert.equal(auth.headers({ request: { url: base + path, method: "GET", headers: {} } })[0].value, expected);
    }
    const cases = [
        [base + "/rap-ui/Invoices"], [base + "/sap/anything"], [base + "/resources/sap-ui-core.js"],
        ["https://ui5.sap.com/resources/sap-ui-core.js"], ["http://localhost:8086" + cap],
        ["http://127.0.0.1:8087" + cap], [base + "/odata/v4/KsefServiceEvil/"],
        [base + cap + "requestSend", "POST"], [base + cap + "%2f..%2frap-ui"],
        [base + cap + "InvoiceIntegrationStatus", "GET", "redirect-id"]
    ];
    for (const [url, method = "GET", redirectedRequestId] of cases) {
        assert.deepEqual(auth.headers({ redirectedRequestId, request: { url, method,
            headers: { aUtHoRiZaTiOn: expected, Accept: "application/json" } } }),
        [{ name: "Accept", value: "application/json" }]);
    }
    assert.equal(auth.redact(expected + " " + env.AINV_CAP_LOCAL_TEST_PASSWORD).includes("synthetic"), false);
});

// Rzeczywisty Chrome/CDP + dwa lokalne serwery HTTP. Bez SAP/CAP/KSeF i bez credentiali użytkownika.
test("browser strips Basic Authentication on same-origin and cross-origin redirects", async () => {
    const received = [];
    const foreign = createServer((req, res) => {
        received.push(["foreign", req.url, req.headers.authorization]);
        res.setHeader("Access-Control-Allow-Origin", "*");
        res.end("ok");
    });
    await new Promise(resolve => foreign.listen(0, "127.0.0.1", resolve));
    const otherOrigin = `http://127.0.0.1:${foreign.address().port}`;
    const server = createServer((req, res) => {
        received.push(["local", req.url, req.headers.authorization]);
        if (req.url === cap + "redirect") {
            res.writeHead(302, { Location: "/rap-ui/redirect-target" }).end();
        } else if (req.url === cap + "same-cap") {
            res.writeHead(302, { Location: cap + "redirect-target" }).end();
        } else if (req.url === cap + "foreign") {
            res.writeHead(302, { Location: otherOrigin + cap + "redirect-target" }).end();
        } else if (req.url === "/") {
            res.setHeader("Content-Type", "text/html");
            res.end(`<div id="qunit-testresult"></div><ol id="qunit-tests"></ol><script>
                (async () => {
                    await fetch('${cap}InvoiceIntegrationStatus');
                    await fetch('/rap-ui/Invoices'); await fetch('/sap/read'); await fetch('/resources/cdn-fixture.js');
                    await fetch('${cap}redirect'); await fetch('${cap}same-cap'); await fetch('${cap}foreign');
                    window.QUnit = {};
                    document.querySelector('#qunit-tests').innerHTML = '<li class="pass"><span class="module-name">fixture</span><span class="test-name">redirects complete</span></li>';
                    document.querySelector('#qunit-testresult').innerHTML = '<span class="total">1</span>';
                })();
                </script>`);
        } else { res.end("ok"); }
    });
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    try {
        const result = await new Promise((resolve, reject) => {
            const child = spawn(process.execPath, ["scripts/browser-test.mjs", "qunit", `http://127.0.0.1:${server.address().port}/`, "15000"],
                { env: { ...process.env, ...env }, stdio: ["ignore", "pipe", "pipe"] });
            let output = "";
            child.stdout.on("data", data => { output += data; });
            child.stderr.on("data", data => { output += data; });
            child.on("error", reject);
            child.on("exit", code => resolve({ code, output }));
        });
        assert.equal(result.code, 0, result.output);
        assert.equal(result.output.includes(expected), false);
        assert.equal(result.output.includes(env.AINV_CAP_LOCAL_TEST_PASSWORD), false);
        const credentialed = received.filter(([, , authorization]) => authorization);
        assert.deepEqual(credentialed.map(([origin, path]) => [origin, path]),
            [["local", cap + "InvoiceIntegrationStatus"], ["local", cap + "redirect"], ["local", cap + "same-cap"], ["local", cap + "foreign"]]);
        assert.ok(credentialed.every(([, , authorization]) => authorization === expected));
        assert.ok(received.some(([origin, path]) => origin === "foreign" && path.endsWith("redirect-target")));
        assert.ok(received.some(([, path]) => path === "/rap-ui/redirect-target"));
        assert.ok(received.some(([, path]) => path === cap + "redirect-target"));
    } finally {
        await Promise.all([new Promise(resolve => server.close(resolve)), new Promise(resolve => foreign.close(resolve))]);
    }
});
