import { readFile, writeFile, mkdir, open, unlink, rename } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const ROOT = dirname(fileURLToPath(import.meta.url));
const TEMPLATE = 'demo-web-app/src/main/resources/xml/invoices/sample/invoice-template_v3.xml';
const TEST_API = 'https://api-test.ksef.mf.gov.pl';

/** Lokalny błąd z bezpiecznym komunikatem. Nigdy nie dołączamy odpowiedzi auth,
 * nagłówków ani surowego błędu fetch: mogą zawierać JWT lub podpisany URL UPO. */
export class RunnerError extends Error {
  constructor(message, code = 'CONTRACT', httpStatus) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
  }
}

/** Czyta prosty plik .env bez zależności npm. Nie nadpisuje zmiennych procesu.
 * Nie komunikuje się z API; błędna linia kończy pracę przed operacjami KSeF. */
export async function loadEnv(file = join(ROOT, '.env'), env = process.env) {
  let source;
  try { source = await readFile(file, 'utf8'); }
  catch (error) { if (error.code === 'ENOENT') return; throw error; }
  for (const line of source.split(/\r?\n/)) {
    if (!line.trim() || line.trim().startsWith('#')) continue;
    const match = line.match(/^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!match) throw new RunnerError('Niepoprawny format .env.', 'CONFIG');
    let value = match[2];
    if (/^(["']).*\1$/.test(value)) value = value.slice(1, -1);
    if (env[match[1]] === undefined) env[match[1]] = value;
  }
}

/** Waliduje konfigurację przed HTTP. Tylko TEST i syntetyczny NIP z zadania.
 * Sam KSEF_ENV nie zmienia środowiska Javy: jej konfigurację sprawdzamy osobno. */
export function readConfig(env = process.env) {
  const integer = (name, fallback) => {
    const value = Number(env[name] ?? fallback);
    if (!Number.isSafeInteger(value) || value < 1 || value > 3600000)
      throw new RunnerError(`Niepoprawna wartość ${name}.`, 'CONFIG');
    return value;
  };
  let url;
  try { url = new URL(env.JAVA_CLIENT_URL || 'http://localhost:8082'); }
  catch { throw new RunnerError('Niepoprawny JAVA_CLIENT_URL.', 'CONFIG'); }
  if (url.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
      || url.username || url.password || url.search || url.hash || url.pathname !== '/')
    throw new RunnerError('JAVA_CLIENT_URL musi wskazywać lokalny serwer HTTP bez ścieżki.', 'CONFIG');
  if ((env.KSEF_ENV || 'TEST') !== 'TEST' || (env.KSEF_NIP || '1234563218') !== '1234563218')
    throw new RunnerError('Dozwolone wyłącznie KSeF TEST i NIP 1234563218.', 'CONFIG');
  return {
    baseUrl: url.origin, nip: '1234563218', environment: 'TEST',
    sourceDir: env.JAVA_CLIENT_SOURCE_DIR ? resolve(ROOT, env.JAVA_CLIENT_SOURCE_DIR) : null,
    intervalMs: integer('POLL_INTERVAL_MS', 2000), attempts: integer('MAX_POLL_ATTEMPTS', 20),
    pollTimeoutMs: integer('POLL_TIMEOUT_MS', 120000), httpTimeoutMs: integer('HTTP_TIMEOUT_MS', 30000),
    authTimeoutMs: integer('AUTH_HTTP_TIMEOUT_MS', 120000),
    outputDir: join(ROOT, 'output'),
  };
}

/** Jeden transport do lokalnego REST Javy. Timeout obejmuje też odczyt body.
 * JSON i pusta odpowiedź close są obsługiwane osobno od binarnego UPO.
 * Nie ma retry, także dla auth/redeem i send. Przekierowania są zabronione.
 * Java sama dodaje "Bearer " do JWT, dlatego tutaj wysyłamy SUROWY token. */
export async function callJavaClient(config, method, path, body, options = {}) {
  const { token, binary = false, timeoutMs = config.httpTimeoutMs, log = console.log,
    fetchImpl = globalThis.fetch } = options;
  const url = new URL(path, config.baseUrl);
  if (!path.startsWith('/') || url.origin !== config.baseUrl)
    throw new RunnerError('Zabroniony adres poza lokalnym Java Client.', 'CONFIG');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchImpl(url, {
      method, redirect: 'error', signal: controller.signal,
      headers: { Accept: binary ? 'application/octet-stream' : 'application/json',
        ...(token ? { Authorization: token } : {}),
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
    log(`      Java API: ${method} ${url.pathname} | HTTP Status: ${response.status}`);
    if (!response.ok) {
      // Nie wypisujemy body błędu Spring Boot, również gdy zawiera stack trace.
      await response.body?.cancel();
      throw new RunnerError(`Java API: HTTP ${response.status}.`, 'HTTP', response.status);
    }
    const bytes = Buffer.from(await response.arrayBuffer());
    if (binary) return { status: response.status, data: bytes };
    if (!bytes.length) return { status: response.status, data: null };
    try { return { status: response.status, data: JSON.parse(bytes.toString('utf8')) }; }
    catch { throw new RunnerError('Java API zwróciło niepoprawny JSON.', 'CONTRACT'); }
  } catch (error) {
    if (error instanceof RunnerError) throw error;
    throw new RunnerError(controller.signal.aborted
      ? 'Timeout Java API; wynik operacji może być nieznany.'
      : 'Błąd połączenia z Java API; wynik operacji może być nieznany.',
    controller.signal.aborted ? 'TIMEOUT' : 'NETWORK');
  } finally { clearTimeout(timer); }
}

/** Polling wyłącznie odczytów. Limituje próby i całkowity czas, również HTTP.
 * null oznacza "jeszcze czekamy". Retry dotyczy tylko awarii odczytu i 429/5xx;
 * odrzucenie biznesowe, 401 i naruszenie kontraktu kończą etap od razu. */
export async function poll(config, read, { log = console.log } = {}) {
  const deadline = Date.now() + config.pollTimeoutMs;
  for (let attempt = 1; attempt <= config.attempts; attempt++) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) break;
    try {
      const result = await read(Math.min(config.httpTimeoutMs, remaining));
      if (result !== null) return result;
    } catch (error) {
      if (!['NETWORK', 'TIMEOUT'].includes(error.code)
          && !(error.code === 'HTTP' && (error.httpStatus === 429 || error.httpStatus >= 500))) throw error;
      log(`      Odczyt ${attempt}/${config.attempts}: ${error.code}; kolejna próba w limicie pollingu.`);
    }
    if (attempt < config.attempts) await delay(Math.max(0, Math.min(config.intervalMs, deadline - Date.now())));
  }
  throw new RunnerError('Polling nie zakończył się w limicie prób/czasu.', 'PENDING');
}

/** Rozróżnia status biznesowy faktury od HTTP. KSeF: GET /v2/sessions/{s}/invoices/{i}.
 * 100/150 = kolejka/przetwarzanie, 200 = przyjęcie, 440 = duplikat.
 * Kod 200 bez numeru KSeF nie jest dla tego eksperymentu sukcesem. */
export function invoiceResult(data) {
  const code = data?.status?.code;
  if (!Number.isInteger(code)) throw new RunnerError('Brak liczbowego status.code faktury.');
  if ([100, 150].includes(code)) return null;
  if (code === 440) throw new RunnerError('Faktura jest duplikatem (status.code=440).', 'DUPLICATE');
  if (code !== 200) throw new RunnerError(`Faktura odrzucona / nieznany status.code=${code}.`, 'REJECTED');
  if (typeof data.ksefNumber !== 'string' || !/^\d{10}-\d{8}-[A-F0-9]{12}-[A-F0-9]{2}$/i.test(data.ksefNumber))
    throw new RunnerError('status.code=200 bez poprawnego numeru KSeF.');
  return { businessStatus: code, ksefNumber: data.ksefNumber };
}

/** Sprawdza format i ważność JWT, NIE weryfikuje jego podpisu kryptograficznie.
 * Źródłem tokenu jest Java po redeem; przydatność sprawdza później KSeF.
 * Nie zapisujemy JWT ani zdekodowanych danych użytkownika. Brak endpointu KSeF. */
export function requireJwt(data) {
  const token = data?.accessToken?.token;
  if (typeof token !== 'string' || !/^[\w-]+\.[\w-]+\.[\w-]+$/.test(token))
    throw new RunnerError('Nie otrzymano accessToken JWT; HTTP 202 nie kończy uwierzytelniania.', 'AUTH');
  let payload;
  try { payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString()); }
  catch { throw new RunnerError('Niepoprawna struktura JWT.', 'AUTH'); }
  if (!Number.isFinite(payload.exp) || payload.exp * 1000 <= Date.now())
    throw new RunnerError('JWT nie ma ważnego terminu exp.', 'AUTH');
  return token;
}

/** Sprawdza zgodność źródłowego szablonu Java z podglądem fixture.
 * To weryfikacja XML, nie dowód środowiska JVM: ten daje /e2e/config. */
export async function verifyJavaSource(config) {
  if (!config.sourceDir) throw new RunnerError('Ustaw JAVA_CLIENT_SOURCE_DIR zgodnie z README.', 'CONFIG');
  const read = path => readFile(join(config.sourceDir, path), 'utf8');
  const [yaml, template, fixture] = await Promise.all([
    read('demo-web-app/src/main/resources/application.yaml'), read(TEMPLATE),
    readFile(join(ROOT, 'fixtures/test-invoice.xml'), 'utf8'),
  ]);
  if (!yaml.includes(`base-uri: "${TEST_API}"`) || !yaml.includes('suffix-uri: "v2"'))
    throw new RunnerError('Źródłowa konfiguracja Java nie wskazuje KSeF TEST.', 'CONFIG');
  const preview = template.replaceAll('#nip#', config.nip).replaceAll('#invoicing_date#', '2025-06-15')
    .replaceAll('#invoice_number#', 'E2E-PREVIEW');
  if (preview.replaceAll('\r\n', '\n').trim() !== fixture.replaceAll('\r\n', '\n').trim())
    throw new RunnerError('Szablon Java różni się od zweryfikowanego przykładu w fixtures.');
}

/** GET Java /e2e/config odczytuje efektywny adres SDK z działającej JVM.
 * Nie wywołuje KSeF. Brak endpointu, DEMO, PROD lub inny adres blokuje auth/send,
 * nawet gdy lokalny .env i źródłowy YAML deklarują TEST. */
export async function verifyRuntime(config, fetchImpl = globalThis.fetch, log = console.log) {
  const { data } = await callJavaClient(config, 'GET', '/e2e/config', undefined, { fetchImpl, log });
  if (data?.environment !== 'TEST' || data.baseUri !== TEST_API || data.suffixUri !== 'v2')
    throw new RunnerError('Działająca Java nie potwierdziła środowiska KSeF TEST. Wysyłka zablokowana.', 'CONFIG');
  log('      Java runtime: KSeF TEST potwierdzony.');
}

/** Bezpieczny preflight: OPTIONS obsługuje Spring MVC bez wywoływania SDK KSeF.
 * Nie używamy GET/POST "na próbę", bo mogłyby otworzyć sesję albo wysłać XML.
 * 404/405 lub niewłaściwe Allow oznacza inny kontrakt działającego serwera. */
export async function preflight(config, fetchImpl = globalThis.fetch, log = console.log) {
  for (const [path, method] of [
    [`/authenticationProcess/${config.nip}`, 'POST'], ['/open-session', 'POST'],
    [`/send-invoice/probe/${config.nip}`, 'POST'], ['/invoice-status', 'GET'],
    ['/close-session/probe', 'POST'], ['/invoice-upo-by-invoice-number', 'GET'], ['/session-status', 'GET'],
  ]) {
    let response;
    try { response = await fetchImpl(`${config.baseUrl}${path}`, {
      method: 'OPTIONS', redirect: 'error', signal: AbortSignal.timeout(config.httpTimeoutMs),
    }); } catch { throw new RunnerError('Preflight: nie można połączyć się z Java API.', 'NETWORK'); }
    await response.body?.cancel();
    if (!response.ok || !response.headers.get('allow')?.split(/,\s*/).includes(method))
      throw new RunnerError(`Preflight: niepotwierdzony kontrakt ${method} ${path} (HTTP ${response.status}).`);
    log(`      Potwierdzono ${method} ${path} (OPTIONS ${response.status}).`);
  }
}

/** Cała autoryzacja jest jednym POST Java /authenticationProcess/{nip}:
 * POST /v2/auth/challenge, POST /v2/auth/xades-signature, GET /v2/auth/{ref},
 * POST /v2/auth/token/redeem. Java wymaga statusu biznesowego 200 przed redeem.
 * Poprawione demo odpytuje status i zwraca końcowy status.code. Przy 500 przerywamy;
 * ponowienie POST mogłoby powtórzyć redeem o nieznanym wyniku. */
export async function authenticate(call, nip, timeoutMs = 120000) {
  const response = await call('POST', `/authenticationProcess/${nip}`, undefined, { timeoutMs });
  if (response.status !== 200) throw new RunnerError('Uwierzytelnianie nie zakończyło się HTTP 200.', 'AUTH');
  if (response.data?.status?.code !== 200)
    throw new RunnerError('Biznesowy status uwierzytelniania nie wynosi 200.', 'AUTH');
  return requireJwt(response.data);
}

/** Etap lokalny; brak API do przesłania własnego XML. Java używa swojego
 * oficjalnego szablonu i losuje UUID przy send. Fixture to podgląd tego wzoru,
 * nie dokładne bajty wysłanego dokumentu. Walidację XSD opisuje README. */
export async function prepareInvoice() {
  const xml = await readFile(join(ROOT, 'fixtures/test-invoice.xml'), 'utf8');
  if (!xml.includes('kodSystemowy="FA (3)"') || !xml.includes('<NIP>1234563218</NIP>') || /#[a-z_]+#/.test(xml))
    throw new RunnerError('Niepoprawny lokalny przykład FA(3).');
  return { format: 'FA(3)', source: 'official-java-sample', actualXmlControlledByJava: true };
}

/** Java POST /open-session -> KSeF POST /v2/sessions/online.
 * AES-256/IV/RSA-OAEP i formCode pozostają w Javie. Demo ma jeden wspólny
 * encryptionData: nie uruchamiaj równolegle innego użytkownika tego demo. */
export async function openSession(call) {
  const { data } = await call('POST', '/open-session');
  return requireReference(data?.referenceNumber);
}

/** Waliduje referencję przed wstawieniem do URL i zapisem raportu.
 * Brak API; wadliwa odpowiedź nie może stać się ścieżką pliku ani URL. */
function requireReference(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9-]{1,100}$/.test(value))
    throw new RunnerError('Java API nie zwróciło poprawnego referenceNumber.');
  return value;
}

/** Java POST /send-invoice/{s}/{nip} -> POST /v2/sessions/online/{s}/invoices.
 * Java czyta XML, szyfruje AES-CBC i liczy oba SHA-256. Przyjęcie HTTP nie
 * oznacza nadania numeru KSeF. ŻADNEGO retry: nawet 500 może ukrywać wysyłkę. */
export async function sendInvoice(call, session, nip) {
  const { data } = await call('POST', `/send-invoice/${session}/${nip}`);
  return requireReference(data?.referenceNumber);
}

/** Java GET /invoice-status?referenceNumber=s&invoiceReferenceNumber=i ->
 * KSeF GET /v2/sessions/{s}/invoices/{i}. Polling sprawdza status.code,
 * kończy się przy przyjęciu, odrzuceniu, duplikacie lub limicie czasu. */
export async function checkInvoiceStatus(call, config, session, invoice, log = console.log) {
  return poll(config, async timeoutMs => {
    const { data } = await call('GET', `/invoice-status?${new URLSearchParams({
      referenceNumber: session, invoiceReferenceNumber: invoice,
    })}`, undefined, { timeoutMs });
    log(`      Business Status: ${Number.isInteger(data?.status?.code) ? data.status.code : 'MISSING'}`);
    return invoiceResult(data);
  }, { log });
}

/** Java POST /close-session/{s} -> POST /v2/sessions/online/{s}/close.
 * Puste HTTP 200 od kontrolera oznacza, że SDK zaakceptowało KSeF HTTP 204.
 * To potwierdzenie żądania zamknięcia, nie końcowy status przetwarzania sesji. */
export async function closeSession(call, session) {
  const response = await call('POST', `/close-session/${session}`);
  if (![200, 204].includes(response.status)) throw new RunnerError('Niepotwierdzone zamknięcie sesji.');
  return { status: 'CLOSE_ACCEPTED', httpStatus: response.status };
}

/** Java GET /session-status?referenceNumber=s -> KSeF GET /v2/sessions/{s}.
 * Po close 100/170 oznacza oczekiwanie, 200 zakończenie przetwarzania.
 * Osobno czekamy na upo.pages, ponieważ UPO jest generowane asynchronicznie.
 * Do raportu trafiają kody/liczniki, nigdy podpisane URL-e z upo.pages. */
export async function checkSessionStatus(call, config, session, log = console.log) {
  let last = { status: 'PENDING', upoAvailable: false };
  try {
    return await poll(config, async timeoutMs => {
      const { data } = await call('GET', `/session-status?${new URLSearchParams({ referenceNumber: session })}`,
        undefined, { timeoutMs });
      const code = data?.status?.code;
      if (!Number.isInteger(code)) throw new RunnerError('Brak status.code sesji.');
      const pages = Array.isArray(data.upo?.pages) ? data.upo.pages.length : 0;
      last = { status: 'PENDING', businessStatus: code, upoAvailable: pages > 0, upoPages: pages };
      log(`      Session Business Status: ${code}; UPO pages: ${pages}`);
      if ([100, 170].includes(code)) return null;
      if (code !== 200) throw new RunnerError(`Sesja zakończona błędem status.code=${code}.`, 'SESSION_REJECTED');
      if (!pages) return null;
      return { ...last, status: 'PROCESSED' };
    }, { log });
  } catch (error) {
    if (error.code !== 'PENDING') throw error;
    return last;
  }
}

/** Java GET /invoice-upo-by-invoice-number?s&i ->
 * KSeF GET /v2/sessions/{s}/invoices/{i}/upo. Pobieramy przez Javę, nigdy
 * bezpośrednio z upoDownloadUrl. Status sesji sprawdza checkSessionStatus.
 * 202/204/404 = brak dokumentu; 401/403 to błąd, nie PENDING.
 * Spring może zwrócić KSeF błąd jako 500: limit pollingu kończy się PENDING. */
export async function downloadUpo(call, config, session, invoice, log = console.log) {
  let bytes;
  try {
    bytes = await poll(config, async timeoutMs => {
      let response;
      try {
        response = await call('GET', `/invoice-upo-by-invoice-number?${new URLSearchParams({
          referenceNumber: session, invoiceReferenceNumber: invoice,
        })}`, undefined, { binary: true, timeoutMs });
      } catch (error) {
        if (error.httpStatus === 404) return null;
        throw error;
      }
      if ([202, 204].includes(response.status)) return null;
      const xml = response.data.toString('utf8').replace(/^\uFEFF/, '').trim();
      if (response.status !== 200 || !/<(?:[\w.-]+:)?Potwierdzenie(?:\s|>)/.test(xml)
          || !xml.includes('http://upo.schematy.mf.gov.pl/') || !xml.includes('</'))
        throw new RunnerError('Odpowiedź UPO nie jest rozpoznanym dokumentem XML.');
      return response.data;
    }, { log });
  } catch (error) {
    if (error.code !== 'PENDING') throw error;
    return { status: 'PENDING', reason: 'UPO niedostępne lub odczyty nieskuteczne w limicie pollingu.' };
  }
  const path = join(config.outputDir, 'upo.xml');
  await writeFile(path, bytes);
  return { status: 'DOWNLOADED', path, bytes: bytes.length };
}

/** Atomowy zapis tylko jawnie wybranych pól raportu (bez tokenów i URL UPO).
 * Brak HTTP; awaria dysku przerywa operacje, by nie zgubić referencji. */
async function saveReport(config, report) {
  const path = join(config.outputDir, 'result.json');
  await writeFile(`${path}.tmp`, `${JSON.stringify(report, null, 2)}\n`);
  await rename(`${path}.tmp`, path);
}

/** Trwała blokada wx powstaje PRZED wysyłką. Pozostaje także po awarii,
 * odrzuceniu i sukcesie. Demo losuje nowy numer przy każdym POST, więc hash
 * lokalnego XML nie chroniłby przed przypadkowym drugim uruchomieniem.
 * Do odczytu wyniku używaj --resume; nie kasujemy znacznika automatycznie. */
export async function reserveSend(outputDir, record) {
  const path = join(outputDir, 'send-attempt.json');
  let handle;
  try { handle = await open(path, 'wx'); }
  catch (error) {
    if (error.code === 'EEXIST') throw new RunnerError('Istnieje próba wysyłki. Użyj --resume; nie ponawiaj send.', 'SEND_GUARD');
    throw error;
  }
  try { await handle.writeFile(`${JSON.stringify(record, null, 2)}\n`); await handle.sync(); }
  finally { await handle.close(); }
}

/** Orkiestrator siedmiu kroków. Bez flag robi jedynie OPTIONS (dry-run).
 * --send autoryzuje pojedynczą próbę; --resume tylko uwierzytelnia i odczytuje
 * istniejącą fakturę / domyka znaną sesję. Referencje zapisujemy po każdym kroku.
 * Nawet gdy faktura jest odrzucona lub polling wygasa, próbujemy zamknąć sesję.
 * Braki kontraktu opisujemy jawnie, nigdy nie udajemy pełnego E2E. */
export async function main(args = process.argv.slice(2)) {
  if (args.includes('--help')) {
    console.log('node ksef-runner.js [--send | --resume]\nBez flag: bezpieczny preflight. Konfiguracja: .env obok skryptu.');
    return 0;
  }
  if (args.some(arg => !['--send', '--resume'].includes(arg)) || args.length > 1)
    throw new RunnerError('Użyj jednej flagi: --send albo --resume; lub żadnej.', 'CONFIG');
  await loadEnv();
  const config = readConfig();
  console.log('========================================\nKSeF 2.0 — END TO END TEST\n========================================');
  await preflight(config);
  await verifyRuntime(config);
  if (!args.length) {
    if (config.sourceDir) await verifyJavaSource(config);
    console.log('DRY-RUN: REST potwierdzony. Wysyłka wymaga --send. Patrz README: konfiguracja TEST w Javie.');
    return 0;
  }
  await verifyJavaSource(config);
  await mkdir(config.outputDir, { recursive: true });
  const lockPath = join(config.outputDir, 'runner.lock');
  let lock;
  try { lock = await open(lockPath, 'wx'); }
  catch (error) {
    if (error.code === 'EEXIST') throw new RunnerError('Runner już działa lub pozostała blokada po awarii; patrz README.', 'LOCK');
    throw error;
  }
  let report = { environment: 'TEST', nip: config.nip, startedAt: new Date().toISOString(),
    status: 'RUNNING', step: 'INITIALIZE', sessionStatus: 'NOT_CHECKED' };
  let token;
  const call = (method, path, body, options = {}) => callJavaClient(config, method, path, body, { token, ...options });
  const step = (name, number) => {
    report.step = name;
    console.log(`\n[${number}/7] ${name}`);
    const endpoints = {
      1: 'POST /v2/auth/challenge -> POST /v2/auth/xades-signature -> GET /v2/auth/{ref} -> POST /v2/auth/token/redeem (Java)',
      2: 'brak — lokalny podgląd szablonu',
      3: 'POST /v2/sessions/online',
      4: 'POST /v2/sessions/online/{session}/invoices',
      5: 'GET /v2/sessions/{session}/invoices/{invoice}',
      6: 'POST /v2/sessions/online/{session}/close',
      7: 'GET /v2/sessions/{session} -> GET /v2/sessions/{session}/invoices/{invoice}/upo',
    };
    console.log(`      KSeF API: ${endpoints[number]}`);
  };
  try {
    if (args[0] === '--send') {
      // Sprawdzamy przed auth/open; atomowy zapis przed send chroni przed wyścigiem.
      try { await readFile(join(config.outputDir, 'send-attempt.json')); throw new RunnerError('Istnieje próba wysyłki. Użyj --resume.', 'SEND_GUARD'); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
    } else {
      const previous = JSON.parse(await readFile(join(config.outputDir, 'result.json'), 'utf8'));
      report = { ...previous, resumedAt: new Date().toISOString(), status: 'RUNNING' };
      delete report.error;
      requireReference(report.sessionReferenceNumber);
      requireReference(report.invoiceReferenceNumber);
    }
    step('AUTHENTICATION', 1);
    await saveReport(config, report);
    token = await authenticate(call, config.nip, config.authTimeoutMs);
    report.auth = { jwtObtained: true, businessStatus: 200 };
    console.log('      Business Status: 200\n      JWT obtained: YES');
    if (args[0] === '--send') {
      step('PREPARE INVOICE', 2);
      report.invoice = await prepareInvoice();
      console.log('      FA(3): oficjalny wzór; XSD sprawdzono podczas przygotowania fixture (README).');
      step('OPEN ONLINE SESSION', 3);
      report.sessionReferenceNumber = await openSession(call);
      await saveReport(config, report);
      console.log(`      Session Reference: ${report.sessionReferenceNumber}`);
    } else console.log('      RESUME: pominięto przygotowanie, otwarcie i wysyłkę.');
    let invoiceError;
    let errorStep;
    try {
      if (args[0] === '--send') {
        step('SEND ENCRYPTED INVOICE', 4);
        await reserveSend(config.outputDir, { startedAt: new Date().toISOString(),
          sessionReferenceNumber: report.sessionReferenceNumber, outcome: 'ATTEMPT_RESERVED_DO_NOT_RETRY' });
        report.sendStatus = 'OUTCOME_UNKNOWN';
        await saveReport(config, report);
        report.invoiceReferenceNumber = await sendInvoice(call, report.sessionReferenceNumber, config.nip);
        report.sendStatus = 'REFERENCE_RECEIVED';
        await saveReport(config, report);
        console.log(`      Invoice Reference: ${report.invoiceReferenceNumber}`);
      }
      step('CHECK INVOICE STATUS', 5);
      Object.assign(report, await checkInvoiceStatus(call, config, report.sessionReferenceNumber, report.invoiceReferenceNumber));
      await saveReport(config, report);
      console.log(`      KSeF Number: ${report.ksefNumber}`);
    } catch (error) { invoiceError = error; errorStep = report.step; }
    step('CLOSE SESSION', 6);
    if (!report.close) {
      report.close = { status: 'OUTCOME_UNKNOWN' };
      await saveReport(config, report);
      try { report.close = await closeSession(call, report.sessionReferenceNumber); }
      catch (error) {
        report.close = { status: 'OUTCOME_UNKNOWN', error: error.code || 'LOCAL' };
        if (!invoiceError) { invoiceError = error; errorStep = report.step; }
      }
      await saveReport(config, report);
    }
    console.log(`      Status: ${report.close.status}`);
    if (invoiceError) { report.step = errorStep; throw invoiceError; }
    if (report.close.status !== 'CLOSE_ACCEPTED') throw new RunnerError('Wynik close jest nieznany; nie ponawiamy automatycznie.', 'CLOSE_UNKNOWN');
    step('DOWNLOAD UPO', 7);
    report.session = await checkSessionStatus(call, config, report.sessionReferenceNumber);
    report.sessionStatus = report.session.status;
    await saveReport(config, report);
    report.upo = report.session.status === 'PROCESSED'
      ? await downloadUpo(call, config, report.sessionReferenceNumber, report.invoiceReferenceNumber)
      : { status: 'PENDING', reason: 'Oczekiwanie na przetworzenie sesji i wygenerowanie UPO.' };
    console.log(`      Status: ${report.upo.status}`);
    report.status = report.upo.status === 'DOWNLOADED' ? 'SUCCESS' : 'UPO_PENDING';
    await saveReport(config, report);
    console.log(`\n========================================\nFINAL RESULT\n${report.status}\nKSeF Number: ${report.ksefNumber}\nRaport: ${join(config.outputDir, 'result.json')}\n========================================`);
    return report.status === 'SUCCESS' ? 0 : 2;
  } catch (error) {
    if (error.code === 'SEND_GUARD') {
      console.error(error.message); // Zachowujemy raport pierwotnej próby.
      return 1;
    }
    report.status = 'FAILED';
    report.error = { code: error instanceof RunnerError ? error.code : 'LOCAL',
      message: error instanceof RunnerError ? error.message : 'Lokalny błąd pliku lub konfiguracji.',
      ...(error.httpStatus ? { httpStatus: error.httpStatus } : {}) };
    await saveReport(config, report);
    console.error(`\nFINAL RESULT: FAILED\nEtap: ${report.step}\n${report.error.message}\nRaport: ${join(config.outputDir, 'result.json')}`);
    return 1;
  } finally { token = undefined; await lock.close(); await unlink(lockPath); }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().then(code => { process.exitCode = code; }).catch(error => {
    console.error(error instanceof RunnerError ? error.message : 'Błąd lokalnej konfiguracji/pliku. Sprawdź README.');
    process.exitCode = 1;
  });
}
