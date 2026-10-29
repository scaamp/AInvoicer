/*global QUnit*/
sap.ui.define([
    "ainvoicer/model/ksef/KsefDataAdapter"
], function (KsefDataAdapter) {
    "use strict";

    var UUID = "55ac244b-67e8-596f-a22f-7f20c57197cb";

    /** Atrapa fetch: rejestruje wywołania i zwraca odpowiedzi według URL. */
    function fakeFetch(fnRoute) {
        var aCalls = [];
        var fnFetch = function (sUrl, oOptions) {
            aCalls.push({ url: sUrl, options: oOptions });
            var oSpec = fnRoute(sUrl, oOptions) || { status: 404, body: { error: { message: "not found" } } };
            if (oSpec.networkError) {
                return Promise.reject(new TypeError("Failed to fetch"));
            }
            return Promise.resolve({
                ok: oSpec.status >= 200 && oSpec.status < 300,
                status: oSpec.status,
                statusText: "status " + oSpec.status,
                headers: { get: function (sName) { return (oSpec.headers || {})[sName] || null; } },
                json: function () {
                    return oSpec.body === undefined ? Promise.reject(new SyntaxError("no json")) : Promise.resolve(oSpec.body);
                }
            });
        };
        fnFetch.calls = aCalls;
        return fnFetch;
    }

    QUnit.module("KsefDataAdapter — mock mode");

    QUnit.test("loads the bundled RAP/CAP-shaped demo data and merges it", function (assert) {
        var oAdapter = new KsefDataAdapter({ dataMode: "mock" });
        assert.strictEqual(oAdapter.getMode(), "mock");
        return oAdapter.loadInvoices().then(function (aRows) {
            assert.strictEqual(aRows.length, 8, "4 H01 seed rows + 4 demo rows");
            var oSeed = aRows.filter(function (o) { return o.InvoiceNumber === "AINV-TEST-SEED-2026-SD-BILL"; })[0];
            assert.strictEqual(oSeed.GrossAmount, 2952, "seed gross amount equals ZCL_AINV_TEST_DATA");
            assert.strictEqual(oSeed.KsefStatus, "NEW", "demo CAP operation attached by sapDocumentRef");
            assert.strictEqual(oSeed.Items.length, 2);
        });
    });

    QUnit.test("mock capabilities: only CAP reads; no state-changing action is available", function (assert) {
        var oCaps = new KsefDataAdapter({
            dataMode: "mock",
            capabilities: { sendInvoice: true, syncInbound: true, viewXml: true, downloadUpo: true, checkDuplicate: true },
            cap: { actions: { send: "x", sync: "x", xml: "x", upo: "x", duplicate: "x" } }
        }).getCapabilities();
        assert.deepEqual(oCaps, { readOperations: true, sendInvoice: false, syncInbound: false, viewXml: false, downloadUpo: false, checkDuplicate: false });
    });

    QUnit.test("send in mock mode is rejected as BACKEND_MISSING and performs no request", function (assert) {
        var fnFetch = fakeFetch(function () { return null; });
        var oAdapter = new KsefDataAdapter({ dataMode: "mock" }, fnFetch);
        return oAdapter.requestSend(UUID).then(function () {
            assert.ok(false, "must not resolve");
        }, function (oError) {
            assert.strictEqual(oError.code, "BACKEND_MISSING");
            assert.strictEqual(oError.gap, "G-02");
            assert.strictEqual(fnFetch.calls.length, 0, "no HTTP call");
        });
    });

    QUnit.test("invoice details include items, references and history", function (assert) {
        var oAdapter = new KsefDataAdapter({ dataMode: "mock" });
        return Promise.all([oAdapter.loadInvoiceDetails(UUID), oAdapter.loadOperations(UUID)]).then(function (aResults) {
            assert.strictEqual(aResults[0].References.length, 2);
            assert.strictEqual(aResults[1].length, 1);
            assert.strictEqual(aResults[1][0].operationType, "SEND_INVOICE");
        });
    });

    QUnit.test("invalid InvoiceUUID is rejected before any request", function (assert) {
        var fnFetch = fakeFetch(function () { return null; });
        return new KsefDataAdapter({ dataMode: "cap" }, fnFetch).loadOperations("x' or 1 eq 1").then(function () {
            assert.ok(false);
        }, function (oError) {
            assert.strictEqual(oError.code, "PARSE");
            assert.strictEqual(fnFetch.calls.length, 0);
        });
    });

    QUnit.module("KsefDataAdapter — cap mode");

    QUnit.test("invoice list is BACKEND_MISSING (G-01) until CAP exposes it", function (assert) {
        return new KsefDataAdapter({ dataMode: "cap" }, fakeFetch(function () { return null; })).loadInvoices().then(function () {
            assert.ok(false);
        }, function (oError) {
            assert.strictEqual(oError.code, "BACKEND_MISSING");
            assert.strictEqual(oError.gap, "G-01");
        });
    });

    QUnit.test("operation history uses the existing CAP KsefOperations READ with a validated filter", function (assert) {
        var fnFetch = fakeFetch(function (sUrl) {
            return sUrl.indexOf("/odata/v4/KsefService/KsefOperations?") === 0
                ? { status: 200, body: { value: [{ status: "NEW", createdAt: "2026-10-28T10:00:00Z" }, { status: "REJECTED", createdAt: "2026-10-29T10:00:00Z" }] } }
                : null;
        });
        return new KsefDataAdapter({ dataMode: "cap" }, fnFetch).loadOperations(UUID.toUpperCase()).then(function (aOps) {
            assert.strictEqual(fnFetch.calls[0].url,
                "/odata/v4/KsefService/KsefOperations?$filter=sapDocumentRef eq '" + UUID + "'&$orderby=createdAt desc");
            assert.strictEqual(fnFetch.calls[0].options.credentials, "same-origin", "session auth only, no tokens in JS");
            assert.notOk(fnFetch.calls[0].options.headers.Authorization, "no Authorization header from the browser");
            assert.strictEqual(aOps[0].status, "REJECTED", "newest first");
        });
    });

    QUnit.test("list + operations are merged when the G-01 endpoint is configured", function (assert) {
        var fnFetch = fakeFetch(function (sUrl) {
            if (sUrl === "/odata/v4/KsefCockpitService/Invoices") {
                return { status: 200, body: { value: [{ InvoiceUUID: UUID, InvoiceNumber: "X", Direction: "OUTBOUND", GrossAmount: "10.50" }] } };
            }
            if (sUrl.indexOf("/odata/v4/KsefService/KsefOperations") === 0) {
                return { status: 200, body: { value: [{ sapDocumentRef: UUID, status: "QUEUED", operationType: "SEND_INVOICE" }] } };
            }
            return null;
        });
        return new KsefDataAdapter({ dataMode: "cap", cap: { invoiceListUrl: "/odata/v4/KsefCockpitService/Invoices" } }, fnFetch)
            .loadInvoices().then(function (aRows) {
                assert.strictEqual(aRows[0].KsefStatus, "QUEUED");
                assert.strictEqual(aRows[0].GrossAmount, 10.5);
            });
    });

    QUnit.test("HTTP errors expose only the (truncated) OData error message", function (assert) {
        var sLong = new Array(500).join("x");
        var fnFetch = fakeFetch(function () { return { status: 403, body: { error: { message: sLong, innererror: { token: "secret" } } } }; });
        return new KsefDataAdapter({ dataMode: "cap" }, fnFetch).loadOperations(UUID).then(function () {
            assert.ok(false);
        }, function (oError) {
            assert.strictEqual(oError.code, "HTTP");
            assert.strictEqual(oError.status, 403);
            assert.strictEqual(oError.message.length, 300);
            assert.strictEqual(oError.message.indexOf("secret"), -1);
        });
    });

    QUnit.test("network failure is reported as NETWORK", function (assert) {
        return new KsefDataAdapter({ dataMode: "cap" }, fakeFetch(function () { return { networkError: true }; }))
            .loadOperations(UUID).then(function () {
                assert.ok(false);
            }, function (oError) {
                assert.strictEqual(oError.code, "NETWORK");
            });
    });

    QUnit.test("configured send action posts once with CSRF token and returns the server status", function (assert) {
        var fnFetch = fakeFetch(function (sUrl, oOptions) {
            if (oOptions.method === "HEAD") {
                return { status: 200, headers: { "X-CSRF-Token": "csrf-123" }, body: {} };
            }
            if (oOptions.method === "POST") {
                return { status: 200, body: { status: "QUEUED" } };
            }
            return null;
        });
        var oAdapter = new KsefDataAdapter({
            dataMode: "cap",
            capabilities: { sendInvoice: true },
            cap: { actions: { send: "Invoices({InvoiceUUID})/KsefCockpitService.requestSend" } }
        }, fnFetch);
        assert.strictEqual(oAdapter.getCapabilities().sendInvoice, true);
        return oAdapter.requestSend(UUID).then(function (oResult) {
            var aPosts = fnFetch.calls.filter(function (c) { return c.options.method === "POST"; });
            assert.strictEqual(aPosts.length, 1, "exactly one POST — no client-side retry");
            assert.strictEqual(aPosts[0].url, "/odata/v4/KsefService/Invoices(" + UUID + ")/KsefCockpitService.requestSend");
            assert.strictEqual(aPosts[0].options.headers["X-CSRF-Token"], "csrf-123");
            assert.strictEqual(oResult.status, "QUEUED");
        });
    });

    QUnit.test("document URLs require a configured endpoint", function (assert) {
        var oAdapter = new KsefDataAdapter({ dataMode: "cap" });
        assert.throws(function () { oAdapter.getDocumentUrl("upo", UUID); }, function (oError) {
            return oError.code === "BACKEND_MISSING" && oError.gap === "G-06";
        });
        var oConfigured = new KsefDataAdapter({ dataMode: "cap", capabilities: { viewXml: true }, cap: { actions: { xml: "Documents({InvoiceUUID})/xml" } } });
        assert.strictEqual(oConfigured.getDocumentUrl("xml", UUID), "/odata/v4/KsefService/Documents(" + UUID + ")/xml");
    });
});
