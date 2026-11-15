namespace customer.cap_java;

// KSEF-STOR-04 — prywatny magazyn dokładnych bajtów XML FA(3)/UPO workerów KSeF (HANA BLOB, lokalnie H2).
//
// Zasady kontraktu (przewodnik docs/guides/KSEF_STOR_04_PRIVATE_HANA_STORAGE.md):
// - encja NIE MA projekcji w żadnym serwisie CAP: brak OData, brak $metadata, brak widoku HDI;
//   jedyna ścieżka zapisu/odczytu to PersistenceService w HanaBlobObjectStorageClient;
// - złożony klucz główny = logiczny adres dokumentu (środowisko × kanał × właściciel × SHA-256);
//   PK jest fizycznym UNIQUE, więc równoległe INSERT tego samego dokumentu dają dokładnie jeden wiersz;
// - wartości kluczy ustala zaufany backend (DocumentScope + SHA-256 liczony z bajtów), nigdy klient;
// - brak `cuid`/`managed`: żadnego losowego ID i żadnej zależności od createdBy/modifiedBy;
// - wiersze są tylko wstawiane — adapter nie wykonuje UPDATE ani DELETE.
// Tabela jest niezależna od laboratoryjnego KsefDocuments/BlobService (BLOB-LAB-01).
@cds.autoexpose: false
entity KsefStoredDocuments {
  // TEST | DEMO | PROD — środowisko KSeF (KsefEnvironment.name()).
  key environment  : String(4)  not null;
  // INBOUND | OUTBOUND — kanał (DocumentScope.Channel.name()); zapas długości na przyszłe kanały.
  key channel      : String(16) not null;
  // OUTBOUND: NIP podatnika (10 cyfr); INBOUND: '-' (staging współdzielony, dostęp przez granty CAP).
  key ownerSegment : String(32) not null;
  // SHA-256 (hex, małe litery) dokładnych bajtów, liczony przez backend.
  key sha256       : String(64) not null;
  // Obecnie wyłącznie 'application/xml'; inna wartość przy odczycie = naruszenie integralności.
  mimeType         : String(100) not null;
  // Długość bajtów zapisana przez backend; sprawdzana przed odczytem BLOB (limit pamięci) i po nim.
  sizeBytes        : Integer64 not null;
  // Czas zapisu (zegar backendu) — wyłącznie do okresu karencji audytu orphan.
  storedAt         : Timestamp not null;
  // Dokładne bajty dokumentu, bez ponownej serializacji (HANA: BLOB).
  content          : LargeBinary not null;
}
