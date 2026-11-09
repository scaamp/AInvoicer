/* global QUnit */
QUnit.config.autostart = false;
QUnit.config.reorder = false;
QUnit.config.testTimeout = 60000;

sap.ui.getCore().attachInit(function () {
    "use strict";
    sap.ui.require(["sap/ui/core/Component", "sap/ui/core/ComponentContainer"], function (Component, ComponentContainer) {
        var controller, component, container, baseline;

        async function get(path) {
            var response = await fetch(path, { credentials: "same-origin", headers: { "OData-MaxVersion": "4.0" } });
            if (!response.ok) { throw new Error("READ " + path.split("?")[0] + " HTTP " + response.status); }
            return response.json();
        }
        function rows() { return controller.getView().getModel("ksef").getProperty("/invoices"); }
        function ids(data) { return data.map(function (row) { return row.InvoiceUUID; }).sort(); }
        async function waitFor(check) {
            var start = Date.now();
            while (!check()) {
                if (Date.now() - start > 30000) { throw new Error("Application startup timeout"); }
                await new Promise(function (resolve) { setTimeout(resolve, 50); });
            }
        }

        // Uruchamiane jawnie na ignorowanej kopii dataMode=rap; nigdy w standardowej regresji mock.
        QUnit.module("RAP LIVE + local CAP runtime (mock principal, no IAS proof)", {
            before: async function () {
                var manifest = await get("../../manifest.json");
                if (manifest["sap.ui5"].config.ksefCockpit.dataMode !== "rap"
                        || manifest["sap.ui5"].config.ksefCockpit.capabilities.sendInvoice !== false) {
                    throw new Error("Requires local RAP manifest with Send OFF");
                }
                baseline = await get("/rap-ui/Invoices?$count=true&$top=20&$expand=_Items,_References&$orderby=InvoiceUUID");
                if (!baseline.value.length || Number(baseline["@odata.count"]) !== baseline.value.length) {
                    throw new Error("Requires a nonempty synthetic dataset of at most 20 invoices");
                }
                component = await Component.create({ name: "ainvoicer", id: "dual-read-app", manifest: true });
                container = new ComponentContainer({ component: component, height: "700px" });
                container.placeAt("live-app");
                await waitFor(function () {
                    var nav = component.getRootControl() && component.getRootControl().byId("app");
                    controller = nav && nav.getPages()[0] && nav.getPages()[0].getController();
                    return controller && controller._oKsefAdapter && controller._oKsefAdapter.getLastPage()
                        && !controller._ksefViewModel().getProperty("/busy");
                });
            },
            after: function () { if (container) { container.destroy(); } if (component) { component.destroy(); } }
        });

        QUnit.test("actual application joins RAP rows with authenticated CAP READ", async function (assert) {
            var status = await get("/odata/v4/KsefService/InvoiceIntegrationStatus?$count=true&$top=200");
            assert.ok(Array.isArray(status.value), "CAP returned an OData collection");
            assert.deepEqual(ids(rows()), ids(baseline.value), "rendered list matches real RAP");
            assert.ok(rows().every(function (row) { return row.IntegrationAvailable; }), "CAP history is available, including an empty history");
            assert.notOk(controller.byId("ksefSendButton").getEnabled(), "Send remains OFF");
            assert.strictEqual(controller._ksefViewModel().getProperty("/integrationError"), "");
        });

        QUnit.test("invoice details preserve real items, references, UUIDs and SAP status", async function (assert) {
            for (var header of baseline.value) {
                var detail = await controller._oKsefAdapter.loadInvoiceDetails(header.InvoiceUUID);
                assert.strictEqual(detail.Items.length, header._Items.length, "item count matches RAP");
                assert.strictEqual(detail.References.length, header._References.length, "reference count matches RAP");
                assert.strictEqual(detail.InvoiceStatus, header.InvoiceStatus);
                assert.ok(detail.Items.concat(detail.References).every(function (child) { return child.InvoiceUUID === header.InvoiceUUID; }));
                assert.strictEqual(detail.SapLastChangedAt, header.LocalLastChangedAt, "timestamp retains wire precision");
            }
        });

        QUnit.test("details dialog opens with a RAP invoice and CAP history", async function (assert) {
            await controller._ksefOpenDetails(rows()[0], "header");
            var dialog = controller.byId("ksefDetailsDialog");
            await waitFor(function () { return dialog.isOpen(); });
            var detail = controller.getView().getModel("ksefDetails");
            assert.ok(dialog.getDomRef(), "dialog rendered");
            assert.ok(dialog.getDomRef().textContent.includes(detail.getProperty("/invoice/InvoiceNumber")));
            assert.notOk(detail.getProperty("/historyError"), "CAP history READ succeeded");
            assert.ok(detail.getProperty("/invoice/Items").length > 0);
            controller.onKsefDetailsClose();
            await waitFor(function () { return !dialog.isOpen(); });
        });

        QUnit.test("number, NIP, date, company and amount filters execute against RAP", async function (assert) {
            var first = baseline.value[0];
            var cases = [
                { search: first.InvoiceNumber }, { search: first.SellerNip },
                { companyCodes: [first.CompanyCode] },
                { issueDateFrom: first.IssueDate, issueDateTo: first.IssueDate },
                { amountFrom: first.GrossAmount, amountTo: first.GrossAmount, currencyCode: first.CurrencyCode }
            ];
            for (var filter of cases) {
                await controller.onKsefClear();
                Object.keys(filter).forEach(function (key) { controller._ksefViewModel().setProperty("/filters/" + key, filter[key]); });
                await controller.onKsefSearch();
                assert.strictEqual(controller._ksefViewModel().getProperty("/error"), "", "RAP accepted filter");
                assert.ok(ids(rows()).includes(first.InvoiceUUID), "expected invoice retained");
            }
            await controller.onKsefClear();
        });

        QUnit.test("amount without currency is rejected before HTTP and releases busy state", async function (assert) {
            controller._ksefViewModel().setProperty("/filters/amountFrom", "1000");
            await controller.onKsefSearch();
            assert.ok(controller._ksefViewModel().getProperty("/error").includes("currency"));
            assert.notOk(controller._ksefViewModel().getProperty("/busy"));
            await controller.onKsefClear();
        });

        QUnit.test("server sort, count, top/skip and previous page keep stable unique UUIDs", async function (assert) {
            controller._oKsefConfig.rap.pageSize = 2;
            await controller.onKsefSort({ getParameter: function (name) {
                return name === "column" ? { getSortProperty: function () { return "GrossAmount"; } } : "Descending";
            }, preventDefault: function () {} });
            var collected = [], amounts = [], guard = 0;
            do {
                collected = collected.concat(ids(rows()));
                amounts = amounts.concat(rows().map(function (row) { return row.GrossAmount; }));
                assert.strictEqual(controller._oKsefAdapter.getLastPage().count, baseline.value.length);
                if (!controller._oKsefAdapter.getLastPage().hasMore) { break; }
                await controller.onKsefLoadMore();
            } while (++guard < 12);
            assert.deepEqual(collected.slice().sort(), ids(baseline.value));
            assert.strictEqual(new Set(collected).size, collected.length, "no duplicate UUIDs");
            assert.deepEqual(amounts, amounts.slice().sort(function (a, b) { return b - a; }));
            await controller.onKsefPreviousPage();
            assert.strictEqual(controller._oKsefAdapter.getLastPage().skip, Math.max(0, (Math.ceil(baseline.value.length / 2) - 2) * 2));
            controller._oKsefConfig.rap.pageSize = 50;
            await controller.onKsefClear();
        });

        [401, 403, "network", "timeout"].forEach(function (failure) {
            QUnit.test("real RAP stays visible with simulated CAP " + failure, async function (assert) {
                var adapter = controller._oKsefAdapter, fetchBefore = adapter._fnFetch;
                adapter._oConfig.cap.readTimeoutMs = 25;
                adapter._fnFetch = function () {
                    if (failure === "timeout") { return new Promise(function () {}); }
                    if (failure === "network") { return Promise.reject(new TypeError("Failed to fetch")); }
                    return Promise.resolve({ ok: false, status: failure, statusText: "Denied",
                        json: function () { return Promise.resolve({ error: { message: "Denied" } }); } });
                };
                try {
                    await controller._ksefLoad();
                    assert.deepEqual(ids(rows()), ids(baseline.value));
                    assert.ok(rows().every(function (row) { return row.IntegrationAvailable === false; }));
                    var detail = await adapter.loadInvoiceDetails(baseline.value[0].InvoiceUUID);
                    assert.strictEqual(detail.Items.length, baseline.value[0]._Items.length);
                    assert.notOk(controller.byId("ksefSendButton").getEnabled());
                } finally {
                    adapter._fnFetch = fetchBefore;
                    delete adapter._oConfig.cap.readTimeoutMs;
                    await controller._ksefLoad();
                }
            });
        });
        QUnit.start();
    });
});
