// CAP-HANA-02R2: budowa/generacja offline. Ten moduł nigdy nie uruchamia cf ani SQL.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { join, resolve, delimiter } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import Zip from 'adm-zip';
import YAML from 'yaml';
import { normalizePermissions, verifyPermissions } from './portable-zip.mjs';
import { PHASES, READ_ONLY_PHASES, READ_ONLY_CONSENT, WRITE_CONSENT } from './hana-integration.mjs';

export const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
export const out = join(root, 'deploy/build/hana-it');
export const APP = 'ainvoicer-hana-it';
export const TARGET = Object.freeze({ api: 'https://api.cf.eu20-001.hana.ondemand.com',
    org: 'National Center for Cryptography_dev-v2xzij9s', space: 'dev' });
export const FLAGS = Object.freeze({ SPRING_PROFILES_ACTIVE: 'cf', AINV_DEPLOYMENT_IDENTITY_SMOKE: 'false',
    AINV_DEPLOYMENT_HANA_READINESS: 'true', AINV_KSEF_ENABLED: 'false', AINV_KSEF_INBOUND_ENABLED: 'false',
    AINV_SAP_WRITE_ENABLED: 'false', AINV_SAP_WRITE_SWEEPER_ENABLED: 'false', AINV_KSEF_STORAGE_PROVIDER: 'off',
    SPRING_SQL_INIT_MODE: 'never', JBP_CONFIG_COMPONENTS: "jres: ['com.sap.xs.java.buildpack.jre.SAPMachineJRE']",
    JBP_CONFIG_SAP_MACHINE_JRE: '{ version: 25.+ }' });
const MAIN = 'ainvoicer.hanait.Main';
const hash = b => createHash('sha256').update(b).digest('hex');
const fileHash = p => hash(readFileSync(p));
const exec = (cmd, args, cwd = root) => {
    // mvn.cmd wymaga shella Windows; każdy argument jako literal PowerShell (również ścieżki ze spacjami).
    const windowsMaven = process.platform === 'win32' && cmd === 'mvn';
    const r = windowsMaven
        ? spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
            '& mvn ' + args.map(a => "'" + a.replaceAll("'", "''") + "'").join(' ') + '; exit $LASTEXITCODE'], { cwd, stdio: 'inherit' })
        : spawnSync(cmd, args, { cwd, stdio: 'inherit' });
    assert.equal(r.status, 0, 'R2 build command failed');
};

export function manifest() {
    return { applications: [{ name: APP, path: 'runner.jar', 'no-route': true, instances: 0,
        memory: '1G', 'disk_quota': '1G', stack: 'cflinuxfs4', buildpacks: ['sap_java_buildpack_jakarta'],
        'health-check-type': 'process', env: { ...FLAGS } }] };
}

function inputs() {
    const paths = ['cap-java/pom.xml', 'cap-java/srv/pom.xml', 'deploy/scripts/hana-it-runner.mjs',
        ...['Main.java', 'OfflineContractTest.java'].map(f => 'deploy/hana-it/src/ainvoicer/hanait/' + f),
        'cap-java/srv/src/test/java/customer/cap_java/hana/HanaPersistenceIT.java'];
    // Właściciel modelu i wszystkie źródła aplikacji są częścią dowodu, bez lokalnych/nieśledzonych plików.
    const git = spawnSync('git', ['ls-files', 'cap-java/db', 'cap-java/srv/src/main'], { cwd: root, encoding: 'utf8' });
    assert.equal(git.status, 0);
    return Object.fromEntries([...new Set([...paths, ...git.stdout.trim().split(/\r?\n/).filter(Boolean)])]
        .sort().map(p => [p, fileHash(join(root, p))]));
}

/** Korzysta z test-scope Maven; JUnit launcher dokładnie w wersji engine, bez console-standalone/duplikatów. */
export function assemble() {
    mkdirSync(out, { recursive: true });
    const deps = join(out, 'deps');
    rmSync(deps, { recursive: true, force: true });
    mkdirSync(deps);
    exec('mvn', ['-B', '-f', 'cap-java/srv/pom.xml', 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
        '-DincludeScope=test', '-DoutputDirectory=' + deps]);
    const engine = readdirSync(deps).find(n => /^junit-jupiter-engine-[\d.]+\.jar$/.test(n));
    assert.ok(engine, 'JUnit engine missing');
    const version = engine.match(/engine-([\d.]+)\.jar$/)[1];
    exec('mvn', ['-B', '-f', 'cap-java/srv/pom.xml', 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy',
        '-Dartifact=org.junit.platform:junit-platform-launcher:' + version, '-DoutputDirectory=' + deps]);
    const classes = join(out, 'classes');
    rmSync(classes, { recursive: true, force: true });
    mkdirSync(classes);
    const cp = [join(root, 'cap-java/srv/target/classes'), join(deps, '*')].join(delimiter);
    exec('javac', ['--release', '25', '-encoding', 'UTF-8', '-cp', cp, '-d', classes,
        ...['Main.java', 'OfflineContractTest.java'].map(n => join(root, 'deploy/hana-it/src/ainvoicer/hanait', n))]);
    const original = join(root, 'cap-java/srv/target/cap-java-exec.jar');
    const zip = new Zip(original);
    // Wyłącznie wybrany IT; nie przenosimy application-test.yaml, H2 seed ani innych testów LIVE.
    zip.addFile('BOOT-INF/classes/customer/cap_java/hana/HanaPersistenceIT.class',
        readFileSync(join(root, 'cap-java/srv/target/test-classes/customer/cap_java/hana/HanaPersistenceIT.class')));
    for (const n of readdirSync(join(classes, 'ainvoicer/hanait')))
        zip.addFile('BOOT-INF/classes/ainvoicer/hanait/' + n, readFileSync(join(classes, 'ainvoicer/hanait', n)));
    for (const n of readdirSync(deps)) {
        if (!zip.getEntry('BOOT-INF/lib/' + n)) zip.addFile('BOOT-INF/lib/' + n, readFileSync(join(deps, n)));
    }
    const mf = zip.readAsText('META-INF/MANIFEST.MF');
    assert.match(mf, /Start-Class: customer\.cap_java\.Application/);
    zip.updateFile('META-INF/MANIFEST.MF', Buffer.from(mf.replace(/Start-Class: customer\.cap_java\.Application/, 'Start-Class: ' + MAIN)));
    zip.deleteFile('BOOT-INF/classpath.idx');
    zip.deleteFile('BOOT-INF/layers.idx');
    // Usuwamy lokalne H2 DDL, aby SQL init nie mogło zostać użyte przypadkiem.
    zip.deleteFile('BOOT-INF/classes/schema-h2.sql');
    const libs = zip.getEntries().filter(e => e.entryName.startsWith('BOOT-INF/lib/') && !e.isDirectory);
    // Spring Boot wymaga STORED dla nested JAR; domyślne addFile w adm-zip używa DEFLATE.
    for (const entry of libs) entry.header.method = 0;
    zip.addFile('BOOT-INF/classpath.idx', Buffer.from(libs.map(e => '- "' + e.entryName + '"').join('\n') + '\n'));
    normalizePermissions(zip).writeZip(join(out, 'runner.jar'));
    writeFileSync(join(out, 'manifest.yml'), YAML.stringify(manifest()));
    const revision = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' });
    assert.equal(revision.status, 0);
    writeFileSync(join(out, 'evidence.json'), JSON.stringify({ kind: 'R2_OFFLINE_ONLY', commit: revision.stdout.trim(),
        inputs: inputs(), originalJar: fileHash(original), junit: version,
        files: { 'runner.jar': fileHash(join(out, 'runner.jar')), 'manifest.yml': fileHash(join(out, 'manifest.yml')) },
        entries: Object.fromEntries(zip.getEntries().filter(e => !e.isDirectory).map(e => [e.entryName, hash(e.getData())])) }, null, 2) + '\n');
    verify();
}

/** Rehash wszystkich wpisów i JAR, kontrola bytecode/drivera/Spring/JUnit oraz manifestu bez tras. */
export function verify(directory = out, checkInputs = true) {
    const e = JSON.parse(readFileSync(join(directory, 'evidence.json')));
    assert.equal(e.kind, 'R2_OFFLINE_ONLY');
    if (checkInputs) assert.deepEqual(e.inputs, inputs(), 'R2 inputs changed; rebuild');
    for (const [name, expected] of Object.entries(e.files)) assert.equal(fileHash(join(directory, name)), expected, 'R2 artifact changed');
    assert.deepEqual(YAML.parse(readFileSync(join(directory, 'manifest.yml'), 'utf8')), manifest());
    const zip = new Zip(join(directory, 'runner.jar'));
    verifyPermissions(zip);
    assert.deepEqual(Object.fromEntries(zip.getEntries().filter(x => !x.isDirectory).map(x => [x.entryName, hash(x.getData())])), e.entries);
    assert.match(zip.readAsText('META-INF/MANIFEST.MF'), /Start-Class: ainvoicer\.hanait\.Main/);
    const required = ['BOOT-INF/classes/customer/cap_java/Application.class',
        'BOOT-INF/classes/customer/cap_java/hana/HanaPersistenceIT.class',
        'BOOT-INF/classes/ainvoicer/hanait/Main.class', 'BOOT-INF/classes/ainvoicer/hanait/OfflineContractTest.class',
        'BOOT-INF/classes/edmx/csn.json', 'org/springframework/boot/loader/launch/JarLauncher.class'];
    for (const name of required) assert.ok(zip.getEntry(name), 'R2 required class/resource missing: ' + name);
    for (const name of required.filter(n => n.startsWith('BOOT-INF') && n.endsWith('.class')))
        assert.equal(zip.readFile(name).readUInt16BE(6), 69, 'R2 requires JDK25 bytecode');
    const requiredClasses = ['org/junit/platform/launcher/core/LauncherFactory.class', 'org/junit/jupiter/engine/JupiterTestEngine.class',
        'org/springframework/test/context/junit/jupiter/SpringExtension.class', 'com/sap/db/jdbc/Driver.class'];
    const found = new Set();
    for (const entry of zip.getEntries().filter(n => n.entryName.startsWith('BOOT-INF/lib/') && !n.isDirectory)) {
        assert.equal(entry.header.method, 0, 'Spring Boot nested JAR must be STORED');
        const jar = new Zip(entry.getData());
        for (const c of requiredClasses) if (jar.getEntry(c)) found.add(c);
    }
    assert.equal(found.size, requiredClasses.length, 'R2 dependencies incomplete');
    assert.ok(!zip.getEntry('BOOT-INF/classes/schema-h2.sql'));
    return e;
}

export function validateTarget(target) { assert.deepEqual(target, TARGET, 'R2 target not approved'); }

/** Zgoda pochodzi WYŁĄCZNIE od wywołującego. Generator nie ustawia zgody i nie uruchamia procesu CF. */
export function taskRequest(phase, env, target = TARGET) {
    validateTarget(target);
    assert.ok(PHASES.includes(phase), 'R2 invalid phase');
    const consent = env.AINV_HANA_TEST_CONSENT;
    assert.ok(consent === WRITE_CONSENT || (READ_ONLY_PHASES.includes(phase) && consent === READ_ONLY_CONSENT), 'R2 explicit phase consent required');
    assert.match(env.AINV_HANA_TEST_RUN_ID || '', /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/);
    const nip = env.AINV_HANA_TEST_NIP || '';
    assert.match(nip, /^[1-9]((\d[1-9])|([1-9]\d))\d{7}$/);
    assert.equal([...nip.slice(0, 9)].reduce((s, c, i) => s + Number(c) * [6, 5, 7, 2, 3, 4, 5, 6, 7][i], 0) % 11, Number(nip[9]));
    assert.equal(env.AINV_HANA_TEST_HDI_NAME, 'ainvoicer-db');
    return { name: 'hana-it-' + phase + '-' + env.AINV_HANA_TEST_RUN_ID.slice(0, 8), memory_in_mb: 1024, disk_in_mb: 1024,
        command: `env AINV_HANA_TEST_CONSENT=${consent} AINV_HANA_TEST_PHASE=${phase} AINV_HANA_TEST_RUN_ID=${env.AINV_HANA_TEST_RUN_ID} AINV_HANA_TEST_NIP=${nip} AINV_HANA_TEST_HDI_NAME=ainvoicer-db .java-buildpack/sap_machine_jre/bin/java -Xmx512m -XX:MaxMetaspaceSize=256m -cp . org.springframework.boot.loader.launch.JarLauncher --run ${phase}` };
}

// Wygenerowany skrypt wymaga ponownej kontroli targetu przy KAŻDEJ bramce; nie zmienia targetu sam.
export function targetGuard() {
    return `$ErrorActionPreference = 'Stop'\n$cfConfigDir = if ($env:CF_HOME) { Join-Path $env:CF_HOME '.cf' } else { Join-Path $env:USERPROFILE '.cf' }\n$cfg = Get-Content -Raw (Join-Path $cfConfigDir 'config.json') | ConvertFrom-Json\nif ($cfg.Target -ne '${TARGET.api}' -or $cfg.OrganizationFields.Name -ne '${TARGET.org}' -or $cfg.SpaceFields.Name -ne '${TARGET.space}') { throw 'R2 target mismatch' }\nRemove-Variable cfg\nfunction Invoke-Cf { & $cf @args; if ($LASTEXITCODE -ne 0) { throw 'CF command failed; STOP' } }\n`;
}

export function serviceGuard() {
    return `# Read metadata only; NEVER fetch credential endpoints. No service keys permitted.\nforeach ($pair in @(@('ainvoicer-db', 'hdi-shared'), @('ainvoicer-auth', 'application'))) {\n    $serviceGuid = Invoke-Cf service $pair[0] --guid\n    $service = (Invoke-Cf curl "/v3/service_instances/$serviceGuid") | ConvertFrom-Json\n    if ($service.type -ne 'managed' -or $service.name -ne $pair[0]) { throw 'Managed service mismatch' }\n    $planGuid = $service.relationships.service_plan.data.guid\n    $plan = (Invoke-Cf curl "/v3/service_plans/$planGuid") | ConvertFrom-Json\n    if ($plan.name -ne $pair[1]) { throw 'Service plan mismatch' }\n    $keys = (Invoke-Cf curl "/v3/service_credential_bindings?service_instance_guids=$serviceGuid&type=key") | ConvertFrom-Json\n    if ($keys.pagination.total_results -ne 0) { throw 'Service keys present; STOP' }\n}\n`;
}

export function commands(target = TARGET) {
    validateTarget(target);
    return targetGuard() + serviceGuard() + `# G2 ONLY after G1 MATCH and separate owner authorization. Run from repository root.\nnode deploy/scripts/hana-it-runner.mjs verify\nif ($LASTEXITCODE -ne 0) { throw 'R2 package failed verification' }\n# Refuse overwrite of any existing runner.\n$spaceGuid = Invoke-Cf space dev --guid\n$apps = (Invoke-Cf curl "/v3/apps?space_guids=$spaceGuid&names=${APP}") | ConvertFrom-Json\nif ($apps.pagination.total_results -ne 0) { throw 'Runner already exists; STOP and inspect' }\nInvoke-Cf push ${APP} -f deploy/build/hana-it/manifest.yml --no-route --no-start\nInvoke-Cf bind-service ${APP} ainvoicer-db\nInvoke-Cf bind-service ${APP} ainvoicer-auth\n# --no-start does NOT stage. --task stages without starting a web process or running a task.\nInvoke-Cf push ${APP} -f deploy/build/hana-it/manifest.yml --task --no-route\nInvoke-Cf app ${APP}\nInvoke-Cf tasks ${APP}\n# G3/G4 after separate approval: generate task.json with 'task PHASE' then POST per runbook.\n`;
}

export function cleanupCommands(target = TARGET) {
    validateTarget(target);
    return targetGuard() + `# Separate authorization. First confirm no RUNNING/CANCELING tasks, then data cleanup per runbook.\n$runnerGuid = Invoke-Cf app ${APP} --guid\n$taskList = (Invoke-Cf curl "/v3/apps/$runnerGuid/tasks?per_page=5000") | ConvertFrom-Json\nif ($taskList.pagination.total_pages -gt 1 -or @($taskList.resources | Where-Object { $_.state -in @('RUNNING', 'PENDING', 'CANCELING') }).Count -gt 0) { throw 'Tasks active or incomplete inventory; STOP' }\nInvoke-Cf tasks ${APP}\n# Cancel a running task ONLY by its reviewed sequence ID: cf terminate-task ${APP} <ID>.\n# If DML ran: recover -> approved cleanup -> verify-cleanup BEFORE deleting bindings.\n# App deletion never removes HANA test rows.\nif ((Read-Host 'Type REMOVE_RUNNER after approved data cleanup and no active tasks') -cne 'REMOVE_RUNNER') { throw 'Cleanup not confirmed' }\nInvoke-Cf unbind-service ${APP} ainvoicer-auth\nInvoke-Cf unbind-service ${APP} ainvoicer-db\nInvoke-Cf delete ${APP}\n# No -r, no delete-service, no delete-service-key; CAP and its bindings stay intact.\n`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try {
        const [action, phase, ...extra] = process.argv.slice(2);
        assert.equal(extra.length, 0);
        if (action === 'build' && !phase) {
            exec('mvn', ['-B', '-f', 'cap-java/pom.xml', 'clean', 'verify', '-Dcds.cds.skipVersionCheck=true']);
            assemble();
            console.log('R2_BUILD_PASS');
        } else if (action === 'verify' && !phase) { verify(); console.log('R2_PACKAGE_INTEGRITY=PASS'); }
        else if (action === 'test' && !phase) { verify(); exec('node', ['--test', 'deploy/hana-it/runner.test.mjs']); }
        else if (action === 'commands' && !phase) { verify(); process.stdout.write(commands()); }
        else if (action === 'target-guard' && !phase) process.stdout.write(targetGuard());
        else if (action === 'cleanup-commands' && !phase) process.stdout.write(cleanupCommands());
        else if (action === 'task') { verify(); process.stdout.write(JSON.stringify(taskRequest(phase, process.env)) + '\n'); }
        else throw new Error();
    } catch { console.error('R2_BLOCKED: invalid input, consent or package; no CF action performed'); process.exitCode = 2; }
}
