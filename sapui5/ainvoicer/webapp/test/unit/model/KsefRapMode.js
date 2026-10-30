/*global QUnit*/
sap.ui.define([
    "sap/ui/model/odata/v4/ODataModel",
    "sap/ui/model/Filter",
    "sap/ui/model/FilterOperator",
    "sap/ui/model/Sorter",
    "ainvoicer/model/ksef/KsefDataAdapter",
    "ainvoicer/model/ksef/KsefInvoiceMapper",
    "ainvoicer/model/ksef/KsefActionRules",
    "ainvoicer/model/ksef/KsefFilterBuilder",
    "ainvoicer/model/ksef/KsefAiFilterAdapter"
], function (ODataModel, Filter, FilterOperator, Sorter, KsefDataAdapter, KsefInvoiceMapper, KsefActionRules, KsefFilterBuilder, KsefAiFilterAdapter) {
    "use strict";

    // CAP-05 / ADR-0005: status biznesowy z RAP, stan integracji z CAP, lista z named modelu V4 "rap".
    var RAP_URL = "/rap-ui/";
    var UUID = "f7ac01d0-ac15-5b56-a77b-ec4e718ced7d";
    var ALL_CAPS = { readOperations: true, sendInvoice: true };

    function header(mOverrides) {
        return Object.assign({ InvoiceUUID: UUID, InvoiceNumber: "INV-1", Direction: "OUTBOUND", KsefNumber: "", KsefEnvironment: "" }, mOverrides);
    }

    function op(sStatus, mOverrides) {
        return Object.assign({ ID: sStatus + Math.random(), sapDocumentRef: UUID, operationType: "SEND_INVOICE", status: sStatus,
            environment: "TEST", ksefNumber: "", createdAt: "2026-10-28T10:00:00Z", modifiedAt: "2026-10-28T10:00:00Z" }, mOverrides);
    }

    /** Atrapa fetch dla CAP: rejestruje URL-e; fnRoute zwraca specyfikację odpowiedzi. */
    function fakeCap(fnRoute) {
        var aCalls = [];
        var fnFetch = function (sUrl, oOptions) {
            aCalls.push({ url: sUrl, options: oOptions });
            var oSpec = fnRoute(sUrl, oOptions);
            if (!oSpec || oSpec.networkError) {
                return Promise.reject(new TypeError("Failed to fetch"));
            }
            return Promise.resolve({
                ok: oSpec.status >= 200 && oSpec.status < 300, status: oSpec.status, statusText: "s",
                headers: { get: function () { return null; } },
                json: function () { return Promise.resolve(oSpec.body); }
            });
        };
        fnFetch.calls = aCalls;
        return fnFetch;
    }

    function rapAdapter(fnFetch) {
        var oModel = new ODataModel({ serviceUrl: RAP_URL, operationMode: "Server", autoExpandSelect: true });
        var oAdapter = new KsefDataAdapter({
            dataMode: "rap", rap: { entitySet: "Invoices", pageSize: 3 },
            capabilities: { sendInvoice: true }, cap: { actions: { send: "requestSend" } }
        }, fnFetch, oModel);
        oAdapter.model = oModel;
        return oAdapter;
    }

    QUnit.module("Status rules — RAP business result vs CAP integration status");

    QUnit.test("RAP KSeF number wins over any CAP state (ACCEPTED in RAP + CAP FAILED/REJECTED)", function (assert) {
        var oHeader = header({ KsefNumber: "5260002008-20261029-ABC-01", KsefEnvironment: "TEST" });
        [op("FAILED"), op("REJECTED"), op("RECONCILIATION_REQUIRED")].forEach(function (oOp) {
            var oRow = KsefInvoiceMapper.toInvoiceRow(oHeader, [oOp], true);
            assert.strictEqual(oRow.KsefStatus, "RECORDED_IN_SAP", oOp.status + " does not override RAP");
            assert.strictEqual(oRow.KsefStatusSource, "RAP");
        });
    });

    QUnit.test("CAP ACCEPTED while RAP has no number = accepted, SAP synchronization pending; Send blocked", function (assert) {
        var oRow = KsefInvoiceMapper.toInvoiceRow(header(), [op("ACCEPTED", { ksefNumber: "K-9", writebackState: "PENDING" })], true);
        assert.strictEqual(oRow.KsefStatus, "ACCEPTED_SAP_SYNC");
        assert.strictEqual(oRow.KsefNumber, "K-9");
        assert.strictEqual(oRow.WritebackState, "PENDING");
        assert.strictEqual(KsefActionRules.evaluateSend(oRow, ALL_CAPS, "TEST").reasonKey, "ksefActionReasonAlreadyInKsef");
    });

    QUnit.test("in-flight SEND followed by a later CHECK_STATUS keeps Send blocked", function (assert) {
        var oRow = KsefInvoiceMapper.toInvoiceRow(header(), [
            op("QUEUED", { modifiedAt: "2026-10-29T08:00:00Z" }),
            op("NEW", { operationType: "CHECK_STATUS", modifiedAt: "2026-10-29T09:00:00Z" })
        ], true);
        assert.strictEqual(oRow.LatestOperation.operationType, "CHECK_STATUS", "latest op is not a SEND");
        assert.strictEqual(oRow.KsefStatus, "QUEUED", "status comes from the SEND attempt");
        var oRule = KsefActionRules.evaluateSend(oRow, ALL_CAPS, "TEST");
        assert.strictEqual(oRule.enabled, false);
        assert.strictEqual(oRule.reasonKey, "ksefActionReasonInProgress");
    });

    QUnit.test("older ACCEPTED or RECONCILIATION_REQUIRED attempt blocks Send even if a newer attempt was REJECTED", function (assert) {
        var oAccepted = KsefInvoiceMapper.toInvoiceRow(header(), [
            op("ACCEPTED", { modifiedAt: "2026-10-28T08:00:00Z" }), op("REJECTED", { modifiedAt: "2026-10-29T08:00:00Z" })], true);
        assert.strictEqual(KsefActionRules.evaluateSend(oAccepted, ALL_CAPS, "TEST").reasonKey, "ksefActionReasonAlreadyInKsef");
        var oUnknown = KsefInvoiceMapper.toInvoiceRow(header(), [
            op("RECONCILIATION_REQUIRED", { modifiedAt: "2026-10-28T08:00:00Z" }), op("REJECTED", { modifiedAt: "2026-10-29T08:00:00Z" })], true);
        assert.strictEqual(oUnknown.KsefStatus, "RECONCILIATION_REQUIRED");
        assert.strictEqual(KsefActionRules.evaluateSend(oUnknown, ALL_CAPS, "TEST").reasonKey, "ksefActionReasonReconciliation");
    });

    QUnit.test("CAP unavailable: no confirmation shown, Send disabled; REJECTED alone allows a confirmed retry", function (assert) {
        var oRow = KsefInvoiceMapper.toInvoiceRow(header(), [], false);
        assert.strictEqual(oRow.KsefStatus, "NO_OPERATION", "\"no KSeF confirmation\", not \"never sent\"");
        assert.strictEqual(KsefActionRules.evaluateSend(oRow, ALL_CAPS, "TEST").reasonKey, "ksefActionReasonCapUnavailable");
        var oRejected = KsefInvoiceMapper.toInvoiceRow(header(), [op("REJECTED")], true);
        assert.strictEqual(KsefActionRules.evaluateSend(oRejected, ALL_CAPS, "TEST").enabled, true);
    });

    QUnit.test("KsefStatus (UI-derived) is never sent to RAP $filter", function (assert) {
        var aFilters = KsefFilterBuilder.build({ search: "", originProcesses: [], companyCodes: ["1000"], ksefStatuses: ["QUEUED"] }, "SALES");
        var aServer = KsefFilterBuilder.serverFilters(aFilters);
        var sPaths = JSON.stringify(aServer.map(function (f) { return f.sPath || f.aFilters.map(function (c) { return c.sPath; }); }));
        assert.ok(sPaths.indexOf("KsefStatus") === -1, sPaths);
        assert.ok(sPaths.indexOf("Direction") !== -1 && sPaths.indexOf("CompanyCode") !== -1, sPaths);
    });

    QUnit.test("mixed OR with CAP status stays local and CAP sort paths never reach RAP", function (assert) {
        var oMixed = new Filter({ filters: [new Filter("KsefStatus", FilterOperator.EQ, "QUEUED"),
            new Filter("CompanyCode", FilterOperator.EQ, "1000")], and: false });
        assert.deepEqual(KsefFilterBuilder.serverFilters([oMixed]), [], "server cannot narrow a mixed OR");
        var aSorters = KsefFilterBuilder.serverSorters([new Sorter("KsefStatus"), new Sorter("OperationReference"),
            new Sorter("LastUpdate"), new Sorter("InvoiceNumber")]);
        assert.deepEqual(aSorters.map(function (s) { return s.sPath; }), ["InvoiceNumber"]);
    });

    QUnit.module("KsefDataAdapter — rap mode (mock RAP UI service + fake CAP)");

    QUnit.test("server-side paging: one page with $count, CAP queried only for the page UUIDs", function (assert) {
        var fnFetch = fakeCap(function () {
            return { status: 200, body: { value: [], "@odata.count": 0 } };
        });
        var oAdapter = rapAdapter(fnFetch);
        return oAdapter.loadInvoices({ sorters: [new Sorter("InvoiceNumber", false)], skip: 0, top: 3 }).then(function (aRows) {
            assert.strictEqual(aRows.length, 3, "page size respected — not the whole list");
            var oPage = oAdapter.getLastPage();
            assert.strictEqual(oPage.count, 8, "$count from the server");
            assert.strictEqual(oPage.hasMore, true);
            var aNumbers = aRows.map(function (r) { return r.InvoiceNumber; });
            assert.deepEqual(aNumbers, aNumbers.slice().sort(), "sorted by the server");
            assert.strictEqual(fnFetch.calls.length, 1, "one CAP call for the page");
            var sUrl = decodeURIComponent(fnFetch.calls[0].url);
            assert.ok(sUrl.indexOf("/odata/v4/KsefService/InvoiceIntegrationStatus?$filter=") === 0, sUrl);
            aRows.forEach(function (r) {
                assert.ok(sUrl.indexOf(r.InvoiceUUID.toLowerCase()) !== -1, "page UUID in CAP filter");
            });
            assert.strictEqual((sUrl.match(/invoiceUUID eq/g) || []).length, 3, "only visible UUIDs");
            return oAdapter.loadInvoices({ sorters: [new Sorter("InvoiceNumber", false)], skip: 3, top: 3 }).then(function (aNext) {
                assert.strictEqual(aNext.length, 3);
                assert.ok(aNext[0].InvoiceNumber > aNumbers[2], "next page continues the server order");
            });
        });
    });

    QUnit.test("server-side filtering and descending sort", function (assert) {
        var oAdapter = rapAdapter(fakeCap(function () { return { status: 200, body: { value: [], "@odata.count": 0 } }; }));
        return oAdapter.loadInvoices({
            filters: [new Filter("Direction", FilterOperator.EQ, "OUTBOUND")],
            sorters: [new Sorter("GrossAmount", true)], skip: 0, top: 50
        }).then(function (aRows) {
            assert.strictEqual(oAdapter.getLastPage().count, 5, "server counted only OUTBOUND");
            assert.ok(aRows.every(function (r) { return r.Direction === "OUTBOUND"; }));
            for (var i = 1; i < aRows.length; i++) {
                assert.ok(aRows[i - 1].GrossAmount >= aRows[i].GrossAmount, "GrossAmount desc");
            }
            assert.strictEqual(oAdapter.getLastPage().hasMore, false);
        });
    });

    QUnit.test("CAP integration status is merged; RAP number still has priority", function (assert) {
        var oAdapter;
        var fnFetch = fakeCap(function (sUrl) {
            var aIds = decodeURIComponent(sUrl).match(/[0-9a-f]{8}-[0-9a-f-]{27}/g) || [];
            return { status: 200, body: { "@odata.count": aIds.length, value: aIds.map(function (sId) {
                return { operationId: "op-" + sId, invoiceUUID: sId, operationType: "SEND_INVOICE",
                    integrationStatus: "SUBMITTED", environment: "TEST", createdAt: "2026-10-29T08:00:00Z", modifiedAt: "2026-10-29T08:00:00Z" };
            }) } };
        });
        oAdapter = rapAdapter(fnFetch);
        return oAdapter.loadInvoices({ skip: 0, top: 50 }).then(function (aRows) {
            aRows.forEach(function (r) {
                if (r.SapKsefNumber) {
                    assert.strictEqual(r.KsefStatus, "RECORDED_IN_SAP", r.InvoiceNumber + ": RAP wins");
                } else {
                    assert.strictEqual(r.KsefStatus, "SUBMITTED", r.InvoiceNumber + ": CAP integration status");
                }
                assert.strictEqual(r.IntegrationAvailable, true);
            });
        });
    });

    QUnit.test("AI date and amount filters run on RAP with Edm.Date and Decimal types", function (assert) {
        var oAdapter = rapAdapter(fakeCap(function () { return { status: 200, body: { value: [], "@odata.count": 0 } }; }));
        var oAi = KsefAiFilterAdapter.translate({ filters: [
            { path: "Direction", operator: "EQ", value1: "OUTBOUND" },
            { path: "DocumentDate", operator: "BT", value1: "2026-10-01", value2: "2026-10-05" },
            { path: "AmountDocument", operator: "GT", value1: "2000,00" }
        ], sql: "ignored" });
        return oAdapter.loadInvoices({ filters: KsefFilterBuilder.serverFilters(oAi.filters), top: 50 }).then(function (aRows) {
            assert.strictEqual(oAdapter.getLastPage().count, 1, "RAP server counts the filtered result");
            assert.strictEqual(aRows.length, 1);
            assert.strictEqual(aRows[0].InvoiceNumber, "AINV-TEST-SEED-2026-FI-AR");
            assert.strictEqual(aRows[0].GrossAmount, 2706);
            assert.strictEqual(aRows[0].IssueDate, "2026-10-03");
        }).finally(function () { oAdapter.model.destroy(); });
    });

    QUnit.test("CAP unavailable: SAP rows still shown, integration unknown, Send disabled", function (assert) {
        var oAdapter = rapAdapter(fakeCap(function () { return { networkError: true }; }));
        return oAdapter.loadInvoices({ skip: 0, top: 3 }).then(function (aRows) {
            assert.strictEqual(aRows.length, 3);
            assert.ok(oAdapter.getLastPage().integrationError, "integration error reported");
            var oOutbound = aRows.filter(function (r) { return r.Direction === "OUTBOUND" && !r.SapKsefNumber; })[0];
            if (oOutbound) {
                assert.strictEqual(KsefActionRules.evaluateSend(oOutbound, ALL_CAPS, "TEST").reasonKey, "ksefActionReasonCapUnavailable");
            } else {
                assert.ok(aRows.every(function (r) { return r.IntegrationAvailable === false; }));
            }
        });
    });

    QUnit.test("truncated CAP history never enables Send even when the returned page contains no SEND", function (assert) {
        var oAdapter = rapAdapter(fakeCap(function () {
            return { status: 200, body: { value: [], "@odata.count": 201,
                "@odata.nextLink": "InvoiceIntegrationStatus?$skip=200" } };
        }));
        return oAdapter.loadInvoices({ top: 3 }).then(function (aRows) {
            assert.strictEqual(aRows.length, 3, "SAP rows remain visible");
            assert.ok(oAdapter.getLastPage().integrationError.indexOf("INCOMPLETE") >= 0);
            aRows.forEach(function (oRow) {
                assert.strictEqual(oRow.IntegrationAvailable, false);
                assert.strictEqual(KsefActionRules.evaluateSend(oRow, ALL_CAPS, "TEST").enabled, false);
            });
        }).finally(function () { oAdapter.model.destroy(); });
    });

    QUnit.test("HTTP 200 with malformed, uncounted or foreign CAP history keeps Send disabled", function (assert) {
        var aBodies = [null, {}, { value: [] }, { value: {}, "@odata.count": 0 },
            { value: [], "@odata.count": null }, { value: [], "@odata.count": -1 },
            { value: [], "@odata.count": "invalid" }, { value: [], "@odata.count": 0.5 },
            { value: [], "@odata.count": 1 },
            { value: [{ invoiceUUID: "00000000-0000-0000-0000-000000000000", operationId: "foreign-operation",
                operationType: "SEND_INVOICE", integrationStatus: "ACCEPTED", environment: "TEST" }], "@odata.count": 1 }];
        return aBodies.reduce(function (oPrevious, oBody) {
            return oPrevious.then(function () {
                var oAdapter = rapAdapter(fakeCap(function () { return { status: 200, body: oBody }; }));
                return oAdapter.loadInvoices({ top: 3 }).then(function (aRows) {
                    assert.strictEqual(aRows.length, 3, "SAP rows remain visible");
                    assert.ok(oAdapter.getLastPage().integrationError, "invalid history is reported");
                    aRows.forEach(function (oRow) {
                        assert.strictEqual(oRow.IntegrationAvailable, false);
                        assert.strictEqual(KsefActionRules.evaluateSend(oRow, ALL_CAPS, "TEST").enabled, false);
                    });
                }).finally(function () { oAdapter.model.destroy(); });
            });
        }, Promise.resolve());
    });

    QUnit.test("details come from RAP with _Items/_References", function (assert) {
        var oAdapter = rapAdapter(fakeCap(function () { return { status: 200, body: { value: [], "@odata.count": 0 } }; }));
        return oAdapter.loadInvoices({ skip: 0, top: 1,
            filters: [new Filter("InvoiceNumber", FilterOperator.EQ, "AINV-TEST-SEED-2026-SD-BILL")] }).then(function (aRows) {
            return oAdapter.loadInvoiceDetails(aRows[0].InvoiceUUID);
        }).then(function (oRow) {
            assert.strictEqual(oRow.InvoiceNumber, "AINV-TEST-SEED-2026-SD-BILL", "header");
            assert.strictEqual(oRow.Items.length, 2, "items expanded from RAP");
            assert.ok(Array.isArray(oRow.References), "references expanded from RAP");
        });
    });

    QUnit.test("CAP records without an operation state never count as a complete history", function (assert) {
        var oAdapter = rapAdapter(fakeCap(function (sUrl) {
            var aIds = decodeURIComponent(sUrl).match(/[0-9a-f]{8}-[0-9a-f-]{27}/g) || [];
            return { status: 200, body: { value: [{ invoiceUUID: aIds[0] }], "@odata.count": 1 } };
        }));
        return oAdapter.loadInvoices({ top: 3 }).then(function (aRows) {
            aRows.forEach(function (oRow) {
                assert.strictEqual(oRow.IntegrationAvailable, false);
                assert.strictEqual(KsefActionRules.evaluateSend(oRow, ALL_CAPS, "TEST").enabled, false);
            });
        }).finally(function () { oAdapter.model.destroy(); });
    });
});
