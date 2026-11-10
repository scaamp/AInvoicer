/*global QUnit*/
sap.ui.define([
    "ainvoicer/model/ksef/inbound/InboundInboxAdapter"
], function (InboundInboxAdapter) {
    "use strict";

    var MOCK_CONFIG = {
        dataMode: "mock",
        pageSize: 20,
        environments: ["TEST", "DEMO", "QA"],
        mock: { invoicesPath: "ainvoicer/localService/ksef/inbound/InboundInvoices.json" },
        capabilities: { viewXml: true, checkDuplicate: true, linkSapDocument: true, approveAndPost: true }
    };
    var CAP_CONFIG = {
        dataMode: "cap",
        pageSize: 2,
        cap: { serviceUrl: "/odata/v4/KsefService/", entitySet: "InboundInvoiceOverview", readTimeoutMs: 200 }
    };
    var ID = "1b0e5c7a-4f2d-4c11-9a3e-000000000001";

    /** Atrapa fetch: rejestruje wywołania i odpowiada wg funkcji route(url, options). */
    function fakeFetch(fnRoute) {
        var aCalls = [];
        var fnFetch = function (sUrl, oOptions) {
            aCalls.push({ url: sUrl, options: oOptions });
            var oSpec = fnRoute(sUrl, oOptions) || { status: 404, body: { error: { message: "not found" } } };
            if (oSpec.networkError) {
                return Promise.reject(new TypeError("Failed to fetch"));
            }
            if (oSpec.hang) {
                return new Promise(function (resolve, reject) {
                    oOptions.signal.addEventListener("abort", function () {
                        reject(new DOMException("aborted", "AbortError"));
                    });
                });
            }
            return Promise.resolve({
                ok: oSpec.status >= 200 && oSpec.status < 300,
                status: oSpec.status,
                json: function () {
                    return oSpec.body === undefined ? Promise.reject(new SyntaxError("no json")) : Promise.resolve(oSpec.body);
                }
            });
        };
        fnFetch.calls = aCalls;
        return fnFetch;
    }

    function dto(iIndex, mOverrides) {
        return Object.assign({
            id: "1b0e5c7a-4f2d-4c11-9a3e-00000000000" + iIndex,
            ksefNumber: "2222222222-20261101-00000000001" + iIndex + "-AA",
            invoiceNumber: "FV/" + iIndex,
            grossAmount: "10.00",
            currency: "PLN"
        }, mOverrides || {});
    }

    function expectRejection(assert, oPromise, sCode, fnMore) {
        return oPromise.then(function () {
            assert.ok(false, "must reject with " + sCode);
        }, function (oError) {
            assert.strictEqual(oError.code, sCode, "error code " + sCode);
            if (fnMore) {
                fnMore(oError);
            }
        });
    }

    QUnit.module("InboundInboxAdapter — mode selection (fail-closed)");

    QUnit.test("missing, unknown or CF config means DISABLED — no data and no HTTP", function (assert) {
        var fnFetch = fakeFetch(function () { return { status: 200, body: { value: [dto(1)] } }; });
        var aConfigs = [undefined, {}, { dataMode: "rap" }, { dataMode: "MOCK" }, { dataMode: "disabled", mock: MOCK_CONFIG.mock }];
        return Promise.all(aConfigs.map(function (oConfig) {
            var oAdapter = new InboundInboxAdapter(oConfig, fnFetch);
            assert.strictEqual(oAdapter.getMode(), "disabled");
            assert.notOk(oAdapter.isAvailable());
            return Promise.all([
                expectRejection(assert, oAdapter.loadPage({}), "DISABLED"),
                expectRejection(assert, oAdapter.loadInvoice(ID), "DISABLED")
            ]);
        })).then(function () {
            assert.strictEqual(fnFetch.calls.length, 0, "no request in disabled mode");
        });
    });

    QUnit.test("future actions are always OFF, even if the manifest enables them", function (assert) {
        assert.deepEqual(new InboundInboxAdapter(MOCK_CONFIG).getCapabilities(),
            { viewXml: false, checkDuplicate: false, linkSapDocument: false, approveAndPost: false });
        assert.deepEqual(new InboundInboxAdapter(Object.assign({}, CAP_CONFIG, { capabilities: MOCK_CONFIG.capabilities })).getCapabilities(),
            { viewXml: false, checkDuplicate: false, linkSapDocument: false, approveAndPost: false });
        assert.notOk(InboundInboxAdapter.prototype.post || InboundInboxAdapter.prototype.requestPosting, "no write API");
    });

    QUnit.test("page size and environments are sanitized", function (assert) {
        assert.strictEqual(new InboundInboxAdapter({ dataMode: "mock", pageSize: 100000 }).getPageSize(), 100);
        assert.strictEqual(new InboundInboxAdapter({ dataMode: "mock", pageSize: "20" }).getPageSize(), 20);
        assert.deepEqual(new InboundInboxAdapter(MOCK_CONFIG).getEnvironments(), ["TEST", "DEMO"], "unknown QA dropped");
    });

    QUnit.module("InboundInboxAdapter — mock provider (synthetic data)");

    QUnit.test("loads the synthetic dataset, pages and counts like a server", function (assert) {
        var oAdapter = new InboundInboxAdapter(MOCK_CONFIG);
        return oAdapter.loadPage({ skip: 0, top: 20 }).then(function (oPage) {
            assert.strictEqual(oPage.count, 26, "26 synthetic records");
            assert.strictEqual(oPage.rows.length, 20);
            assert.ok(oPage.hasMore);
            assert.strictEqual(oPage.rejected, 0);
            assert.strictEqual(oPage.rows[0].acquisitionDate >= oPage.rows[1].acquisitionDate, true, "newest received first");
            return oAdapter.loadPage({ skip: 20, top: 20 });
        }).then(function (oPage) {
            assert.strictEqual(oPage.rows.length, 6);
            assert.notOk(oPage.hasMore);
        });
    });

    QUnit.test("mock covers edge cases: unsupported schema, conflict, null metadata, long numbers, precise amount", function (assert) {
        var oAdapter = new InboundInboxAdapter(MOCK_CONFIG);
        return oAdapter.loadPage({ skip: 0, top: 100 }).then(function (oPage) {
            function find(fnTest) { return oPage.rows.filter(fnTest)[0]; }
            assert.ok(find(function (o) { return o.parseStatus === "UNSUPPORTED_SCHEMA"; }), "unsupported schema");
            assert.ok(find(function (o) { return o.integrityStatus === "CONFLICT"; }), "integrity conflict");
            assert.ok(find(function (o) { return o.grossAmount === null && o.currency === null && o.issueDate === null; }), "incomplete metadata");
            assert.ok(find(function (o) { return o.invoiceNumber && o.invoiceNumber.length > 100; }), "long invoice number");
            assert.ok(find(function (o) { return o.issues.indexOf("NONSTANDARD_KSEF_NUMBER") !== -1; }), "long KSeF number");
            assert.ok(find(function (o) { return o.grossAmount === "98765432109876.54"; }), "precise amount kept as text");
            assert.ok(oPage.rows.every(function (o) { return o.sapPostingStatus !== "POSTED"; }), "no fictitious SAP posting in demo data");
            assert.ok(oPage.rows.every(function (o) { return o.environment !== "PROD"; }), "demo data never claims PROD");
        });
    });

    QUnit.test("filters are applied (and validated) before paging", function (assert) {
        var oAdapter = new InboundInboxAdapter(MOCK_CONFIG);
        return oAdapter.loadPage({ filters: { integrityStatuses: ["CONFLICT", "MISMATCH"] } }).then(function (oPage) {
            assert.strictEqual(oPage.count, 2);
            return expectRejection(assert, oAdapter.loadPage({ filters: { sellerNip: "abc" } }), "VALIDATION", function (oError) {
                assert.strictEqual(oError.errors[0].key, "inboxFilterErrorNip");
            });
        });
    });

    QUnit.test("mock without a configured path does not guess a fixture", function (assert) {
        var fnFetch = fakeFetch(function () { return { status: 200, body: { value: [] } }; });
        return Promise.all([
            expectRejection(assert, new InboundInboxAdapter({ dataMode: "mock" }, fnFetch).loadPage({}), "BACKEND_MISSING"),
            expectRejection(assert, new InboundInboxAdapter({ dataMode: "mock", mock: { invoicesPath: "../../evil.json" } }, fnFetch).loadPage({}), "BACKEND_MISSING")
        ]).then(function () {
            assert.strictEqual(fnFetch.calls.length, 0);
        });
    });

    QUnit.test("details read the same record; unknown id is NOT_FOUND; malformed id is rejected", function (assert) {
        var oAdapter = new InboundInboxAdapter(MOCK_CONFIG);
        return oAdapter.loadInvoice(ID.toUpperCase()).then(function (oRow) {
            assert.strictEqual(oRow.id, ID);
            assert.strictEqual(oRow.invoiceNumber, "FV/100/2026");
            return Promise.all([
                expectRejection(assert, oAdapter.loadInvoice("1b0e5c7a-4f2d-4c11-9a3e-0000000000ff"), "NOT_FOUND"),
                expectRejection(assert, oAdapter.loadInvoice("x') or (1 eq 1"), "PARSE")
            ]);
        });
    });

    QUnit.module("InboundInboxAdapter — future CAP OData provider");

    QUnit.test("cap mode without entitySet is BACKEND_MISSING without HTTP and never falls back to mock", function (assert) {
        var fnFetch = fakeFetch(function () { return { status: 200, body: { value: [] } }; });
        var oAdapter = new InboundInboxAdapter({ dataMode: "cap", cap: { serviceUrl: "/odata/v4/KsefService/", entitySet: null },
            mock: MOCK_CONFIG.mock }, fnFetch);
        return expectRejection(assert, oAdapter.loadPage({}), "BACKEND_MISSING", function (oError) {
            assert.strictEqual(oError.gap, "INBOX-OVERVIEW");
            assert.strictEqual(fnFetch.calls.length, 0);
        });
    });

    QUnit.test("reads one page with a same-origin GET, OData 4.0 headers and decimal-as-string", function (assert) {
        var fnFetch = fakeFetch(function () {
            return { status: 200, body: { "@odata.count": 3, value: [dto(1), dto(2, { id: "bad" })] } };
        });
        var oAdapter = new InboundInboxAdapter(CAP_CONFIG, fnFetch);
        return oAdapter.loadPage({ filters: { sellerNip: "2222222222" }, sort: { field: "issueDate" }, skip: 0 }).then(function (oPage) {
            var oCall = fnFetch.calls[0];
            assert.strictEqual(fnFetch.calls.length, 1);
            assert.ok(oCall.url.indexOf("/odata/v4/KsefService/InboundInvoiceOverview?$select=") === 0, oCall.url);
            assert.ok(oCall.url.indexOf("sellerNip%20eq%20'2222222222'") !== -1);
            assert.ok(oCall.url.indexOf("$orderby=issueDate%20asc%2Cid%20asc") !== -1);
            assert.ok(oCall.url.indexOf("$top=2") !== -1 && oCall.url.indexOf("$count=true") !== -1);
            assert.strictEqual(oCall.options.method, "GET");
            assert.strictEqual(oCall.options.credentials, "same-origin");
            assert.strictEqual(oCall.options.redirect, "error");
            assert.strictEqual(oCall.options.headers.Accept, "application/json;IEEE754Compatible=true");
            assert.strictEqual(oCall.options.headers["OData-MaxVersion"], "4.0");
            assert.notOk(oCall.options.headers.Authorization, "no credentials added by the UI");
            assert.strictEqual(oPage.rows.length, 1);
            assert.strictEqual(oPage.rejected, 1, "row without valid id is skipped and reported");
            assert.strictEqual(oPage.count, 3);
            assert.ok(oPage.hasMore);
        });
    });

    QUnit.test("incomplete or oversized responses are rejected instead of shown as an empty list", function (assert) {
        function adapterFor(oBody) {
            return new InboundInboxAdapter(CAP_CONFIG, fakeFetch(function () { return { status: 200, body: oBody }; }));
        }
        return Promise.all([
            expectRejection(assert, adapterFor({ value: [] }).loadPage({}), "PARSE"),
            expectRejection(assert, adapterFor({ "@odata.count": 1 }).loadPage({}), "PARSE"),
            expectRejection(assert, adapterFor({ "@odata.count": 9, value: [dto(1), dto(2), dto(3)] }).loadPage({}), "PARSE"),
            expectRejection(assert, adapterFor(undefined).loadPage({}), "PARSE")
        ]);
    });

    QUnit.test("HTTP errors, network failure and timeout are classified; message is truncated", function (assert) {
        var sLong = new Array(500).join("x");
        return Promise.all([
            expectRejection(assert, new InboundInboxAdapter(CAP_CONFIG, fakeFetch(function () {
                return { status: 403, body: { error: { message: sLong } } };
            })).loadPage({}), "HTTP", function (oError) {
                assert.strictEqual(oError.status, 403);
                assert.ok(oError.message.length <= 300);
            }),
            expectRejection(assert, new InboundInboxAdapter(CAP_CONFIG, fakeFetch(function () {
                return { status: 500 };
            })).loadPage({}), "HTTP", function (oError) {
                assert.strictEqual(oError.message, "HTTP 500");
            }),
            expectRejection(assert, new InboundInboxAdapter(CAP_CONFIG, fakeFetch(function () {
                return { networkError: true };
            })).loadPage({}), "NETWORK"),
            expectRejection(assert, new InboundInboxAdapter(CAP_CONFIG, fakeFetch(function () {
                return { hang: true };
            })).loadPage({}), "TIMEOUT"),
            expectRejection(assert, new InboundInboxAdapter(CAP_CONFIG, fakeFetch(function () {
                return { status: 404, body: {} };
            })).loadInvoice(ID), "NOT_FOUND")
        ]);
    });

    QUnit.test("no data leaves the application origin (service URL and mock path are checked)", function (assert) {
        var fnFetch = fakeFetch(function () { return { status: 200, body: { "@odata.count": 0, value: [] } }; });
        var aUrls = ["https://evil.example/odata/", "//evil.example/odata/", "/odata/../../x/", "javascript:alert(1)/", "/odata/v4/KsefService"];
        return Promise.all(aUrls.map(function (sUrl) {
            var oAdapter = new InboundInboxAdapter({ dataMode: "cap", cap: { serviceUrl: sUrl, entitySet: "InboundInvoiceOverview" } }, fnFetch);
            return expectRejection(assert, oAdapter.loadPage({}), "PARSE");
        }).concat([
            expectRejection(assert, new InboundInboxAdapter({ dataMode: "cap",
                cap: { serviceUrl: "/odata/v4/KsefService/", entitySet: "X?$expand=*" } }, fnFetch).loadPage({}), "PARSE")
        ])).then(function () {
            assert.strictEqual(fnFetch.calls.length, 0, "no request to a foreign or malformed URL");
            var fnOk = fakeFetch(function () { return { status: 200, body: { "@odata.count": 0, value: [] } }; });
            return new InboundInboxAdapter(CAP_CONFIG, fnOk).loadPage({}).then(function () {
                fnOk.calls.forEach(function (oCall) {
                    assert.strictEqual(new URL(oCall.url, window.location.href).origin, window.location.origin, "same origin");
                });
            });
        });
    });
});
