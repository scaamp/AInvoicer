// Odczyt CAPI przez istniejące logowanie CF. Nigdy nie wypisuje cf env, credentiali ani błędów HTTP.
import { execFileSync } from 'node:child_process';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const api = 'https://api.cf.eu20-001.hana.ondemand.com';
const org = 'National Center for Cryptography_dev-v2xzij9s';
const space = 'dev';
const guid = value => /^[a-f\d-]{36}$/i.test(value || '');
const flag = value => ['true', 'false'].includes(value) ? value : value === undefined ? 'UNSET' : 'UNEXPECTED';
const name = value => /^[\w.! -]{1,120}$/.test(value || '') ? value : 'REDACTED';
const date = value => /^\d{4}-\d\d-\d\dT[\d:.]+Z$/.test(value || '') ? value : undefined;
const digest = value => /^[a-f\d]{40,128}$/i.test(value || '') ? value : undefined;

/** Target musi odpowiadać zleceniu; plik config jest parsowany lokalnie, nigdy serializowany. */
export function verifyTarget(config) {
    if (config.Target !== api || config.OrganizationFields?.Name !== org || config.SpaceFields?.Name !== space
        || !guid(config.SpaceFields?.GUID)) throw new Error('CF_TARGET_MISMATCH');
    return config.SpaceFields.GUID;
}

/** Whitelist wartości, bez VCAP_SERVICES, klienta OAuth, tokenów i surowego tekstu konfiguracji. */
export function safeEnvironment(env) {
    const user = env.environment_variables || {};
    const flags = Object.fromEntries(['AINV_DEPLOYMENT_IDENTITY_SMOKE', 'AINV_KSEF_ENABLED',
        'AINV_SAP_WRITE_ENABLED', 'AINV_SAP_WRITE_SWEEPER_ENABLED', 'AINV_KSEF_INBOUND_ENABLED']
        .map(key => [key, flag(user[key])]));
    let destinations = [];
    try { destinations = typeof user.destinations === 'string' ? JSON.parse(user.destinations) : user.destinations || []; }
    catch { /* Nie wypisujemy błędnego JSON mogącego zawierać sekrety. */ }
    const allowedNames = ['ainvoicer-cap', 'ui5'];
    return { flags, profileCf: user.SPRING_PROFILES_ACTIVE === 'cf', h2Platform: user.SPRING_SQL_INIT_PLATFORM === 'h2',
        sapMachine25: /^\{\s*version:\s*25\.\+\s*\}$/.test(user.JBP_CONFIG_SAP_MACHINE_JRE || ''),
        sessionTimeout15: String(user.SESSION_TIMEOUT) === '15',
        destinations: Array.isArray(destinations) ? destinations.map(d => ({
            name: allowedNames.includes(d.name) ? d.name : 'UNEXPECTED',
            forwardAuthToken: d.forwardAuthToken === true,
            // URL raportujemy jako wynik kontroli, nie jako potencjalny nośnik credentiali.
            expectedUrl: d.name === 'ui5' ? d.url === 'https://ui5.sap.com' :
                d.name === 'ainvoicer-cap' && d.url === 'https://ainvoicer-v2xzij9s-srv.cfapps.eu20-001.hana.ondemand.com'
        })) : [] };
}

/** Tylko znane nazwy w bezpiecznej projekcji XSUAA; brak clientid/clientsecret i pełnego configu. */
export function safeXsuaa(parameters) {
    return { dedicated: parameters['tenant-mode'] === 'dedicated', xsappnameExpected: parameters.xsappname === 'ainvoicer-dev',
        operatorScope: parameters.scopes?.some(s => s.name === '$XSAPPNAME.KsefOperator') === true,
        operatorTemplate: parameters['role-templates']?.some(t => t.name === 'KsefOperator') === true,
        requiredAttributes: ['taxpayerNips', 'companyCodes'].every(n =>
            parameters.attributes?.some(a => a.name === n && a.valueRequired === true)),
        narrowRedirectOnly: JSON.stringify(parameters['oauth2-configuration']?.['redirect-uris']) ===
            JSON.stringify(['https://ainvoicer-v2xzij9s-approuter.cfapps.eu20-001.hana.ondemand.com/**']) };
}

/** CAPI pagination: tylko względne ścieżki GET do tego samego API i skończona liczba stron. */
export async function list(get, path) {
    const rows = [];
    for (let i = 0; path && i < 10; i++) {
        if (!path.startsWith('/v3/') || /[\r\n#]/.test(path)) throw new Error('CF_UNSAFE_PATH');
        const page = await get(path);
        rows.push(...page.resources);
        const next = page.pagination?.next?.href;
        if (!next) return rows;
        const url = new URL(next, api);
        if (url.origin !== api || url.username || url.password) throw new Error('CF_UNSAFE_PAGINATION');
        path = url.pathname + url.search;
    }
    throw new Error('CF_PAGINATION_LIMIT');
}

/** Zbieranie wyłącznie metadanych; brak restage/restart/deploy, logów aplikacji lub grantów. */
export async function collect(config, get) {
    const spaceGuid = verifyTarget(config);
    const stacks = await list(get, '/v3/stacks?per_page=200');
    const buildpacks = await list(get, '/v3/buildpacks?per_page=200');
    const instances = await list(get, '/v3/service_instances?space_guids=' + spaceGuid + '&per_page=200');
    const apps = await list(get, '/v3/apps?names=ainvoicer-srv,ainvoicer-approuter&space_guids=' + spaceGuid);
    const report = { kind: 'READ_ONLY_CF_INVENTORY', at: new Date().toISOString(), targetVerified: true,
        cflinuxfs5Available: stacks.some(s => s.name === 'cflinuxfs5'),
        buildpacks: buildpacks.filter(b => ['sap_java_buildpack_jakarta', 'nodejs_buildpack'].includes(b.name))
            .map(b => ({ name: b.name, stack: name(b.stack), enabled: b.enabled, filename: name(b.filename) })), apps: [],
        xsuaaConfiguration: 'BLOCKED',
        destinationContent: 'BLOCKED: CAPI metadata does not prove subaccount Destination authentication or principal',
        stagingOnFs5Jdk25: 'NOT_EXECUTED: requires separately approved deployment' };
    for (const app of apps) {
        if (!guid(app.guid)) throw new Error('CF_INVALID_APP_GUID');
        const routes = await list(get, `/v3/apps/${app.guid}/routes`);
        const bindings = await list(get, `/v3/service_credential_bindings?app_guids=${app.guid}`);
        const env = await get(`/v3/apps/${app.guid}/env`);
        let droplet;
        try { droplet = await get(`/v3/apps/${app.guid}/droplets/current`); }
        catch { /* Brak dropletu nie jest dowodem gotowego runtime. */ }
        report.apps.push({ name: name(app.name), state: ['STARTED', 'STOPPED'].includes(app.state) ? app.state : 'UNKNOWN',
            routes: routes.map(r => /^https?:\/\/[a-z\d.-]+(?::\d+)?\/?$/i.test(r.url) ? r.url :
                /^[a-z\d.-]+\/?$/i.test(r.url) ? r.url : 'REDACTED'),
            bindings: bindings.map(b => name(instances.find(s => s.guid === b.relationships?.service_instance?.data?.guid)?.name)),
            environment: safeEnvironment(env), currentDroplet: droplet ? {
                createdAt: date(droplet.created_at), stack: name(droplet.stack),
                hash: digest(droplet.checksum?.value),
                buildpacks: (droplet.buildpacks || []).map(b => ({ name: name(b.name), version: name(b.version),
                    detectedSapMachine25: /(?:sapmachine|sap machine)[^\n]{0,80}\b25[.\s]/i.test(b.detect_output || '') }))
            } : 'BLOCKED' });
    }
    const auth = instances.find(s => s.name === 'ainvoicer-auth');
    if (auth && guid(auth.guid)) {
        try { report.xsuaaConfiguration = safeXsuaa(await get(`/v3/service_instances/${auth.guid}/parameters`)); }
        catch { report.xsuaaConfiguration = 'BLOCKED: broker parameters unavailable with current authorization'; }
    }
    report.expectedAppsPresent = ['ainvoicer-srv', 'ainvoicer-approuter'].every(n => report.apps.some(a => a.name === n));
    report.bindingMetadataExpected = report.expectedAppsPresent && report.apps.every(a =>
        JSON.stringify([...a.bindings].sort()) === JSON.stringify(['ainvoicer-auth', 'ainvoicer-destination']));
    const srv = report.apps.find(a => a.name === 'ainvoicer-srv')?.environment;
    report.smokeEnvExpected = !!srv && srv.profileCf && srv.h2Platform && srv.sapMachine25
        && srv.flags.AINV_DEPLOYMENT_IDENTITY_SMOKE === 'true'
        && ['AINV_KSEF_ENABLED', 'AINV_KSEF_INBOUND_ENABLED', 'AINV_SAP_WRITE_ENABLED', 'AINV_SAP_WRITE_SWEEPER_ENABLED']
            .every(key => srv.flags[key] === 'false');
    report.securityAcceptance = 'PENDING: inventory is not JWT, DCL, principal or session acceptance';
    return report;
}

async function main() {
    const out = process.argv[2] ? resolve(process.argv[2]) : fileURLToPath(new URL('../build/cf-readiness.json', import.meta.url));
    let report;
    try {
        const config = JSON.parse(readFileSync(join(process.env.CF_HOME || join(homedir(), '.cf'), 'config.json'), 'utf8'));
        const cf = process.env.AINV_CF_CLI || 'cf';
        report = await collect(config, path => {
            try { return JSON.parse(execFileSync(cf, ['curl', path, '-X', 'GET'],
                { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 20000, maxBuffer: 4 * 1024 * 1024 })); }
            catch { throw new Error('CF_READ_BLOCKED'); }
        });
    } catch (error) {
        report = { kind: 'READ_ONLY_CF_INVENTORY', at: new Date().toISOString(), status: 'BLOCKED',
            reason: ['CF_TARGET_MISMATCH', 'CF_UNSAFE_PAGINATION', 'CF_PAGINATION_LIMIT'].includes(error.message)
                ? error.message : 'CF login, CLI or read authorization unavailable; raw output suppressed' };
        process.exitCode = 2;
    }
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report, null, 2));
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await main();
