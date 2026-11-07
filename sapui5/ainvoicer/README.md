## Application Details
|               |
| ------------- |
|**Generation Date and Time**<br>Tue May 20 2025 11:49:01 GMT+0000 (Coordinated Universal Time)|
|**App Generator**<br>SAP Fiori Application Generator|
|**App Generator Version**<br>1.17.5|
|**Generation Platform**<br>SAP Business Application Studio|
|**Template Used**<br>Basic V4|
|**Service Type**<br>SAP System (ABAP Environment on SAP Business Technology Platform)|
|**Service URL**<br>https://11b69711-9bcb-491b-b504-a9ba7e4b7e97.abap.us10.hana.ondemand.com/sap/opu/odata4/sap/zui_fi_acdoca_o4/srvd/sap/zui_fi_acdoca_o4/0001/|
|**Module Name**<br>ainvoicer|
|**Application Title**<br>AInvoicer|
|**Namespace**<br>|
|**UI5 Theme**<br>sap_horizon|
|**UI5 Version**<br>1.133.3|
|**Enable Code Assist Libraries**<br>False|
|**Enable TypeScript**<br>False|
|**Add Eslint configuration**<br>False|

## ainvoicer

AInvoicer is an SAP Fiori application for intelligent invoice processing powered by OData V4, ABAP RAP, and AI integration.

### Local RAP mode (verified 2026-11-07)

- The `Service URL` above (host `11b69711-…abap.us10…`) is **not** the current H01 system; it returns `503 System is unknown`. The Legacy `/sap` route in `ui5.yaml`/`ui5-local.yaml`/`ui5-mock.yaml` still points there.
- For real RAP reads copy [`ui5-rap.yaml.example`](ui5-rap.yaml.example) to the git-ignored `ui5-rap.local.yaml` and follow the [RAP UI live connection runbook](../../docs/guides/RAP_UI_LIVE_CONNECTION.md).
- `@sap/ux-ui5-tooling` is pinned to **1.19.6**: older versions open the reentrance login on the API host (Basic Auth only), and 1.20.0+ requires UI5 CLI v4.
- Run the server **in your own terminal**. The reentrance login opens once at startup and times out after 60 s.

### Starting the generated app

-   This app has been generated using the SAP Fiori tools - App Generator, as part of the SAP Fiori tools suite.  In order to launch the generated app, simply run the following from the generated app root folder:

```
    npm start
```

- It is also possible to run the application using mock data that reflects the OData Service URL supplied during application generation.  In order to run the application with Mock Data, run the following from the generated app root folder:

```
    npm run start-mock
```

#### Pre-requisites:

1. Active NodeJS LTS (Long Term Support) version and associated supported NPM version.  (See https://nodejs.org)


