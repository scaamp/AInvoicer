
# 🧠 AInvoicer

**AInvoicer** is a fullstack SAP-based invoice processing application that merges traditional finance logic with modern AI capabilities. This demo project showcases the integration of ABAP RAP, SAPUI5, OData v4, and AI microservices deployed in SAP BTP Cloud Foundry.

---

## 💡 Overview

The application enables:

- Connecting SAPUI5 frontend with ABAP RAP and OData v4 backend
- Processing financial data from the `ZFI_ACDOCA` model
- Scanning and analyzing invoices with AI (OCR + NLP)
- Smart filtering using natural language queries
- Tracking AI queries and prompts with Langfuse

---

## 🚀 Key Features

- 🔍 **Natural Language Filtering (Langfuse-tracked)**  
  Using OpenAI and Langfuse, user queries in natural language are translated into OData filters. Example: “Show documents over 5000 EUR from 2024”.

- 📄 **OCR Invoice Scanner**  
  Node.js microservice + OpenAI analyzes uploaded invoices (PDF/JPG/PNG) and extracts structured data matching the `ZFI_ACDOCA` table.

- 📊 **Dynamic Table (sap.ui.table)**  
  With features such as:  
  → OData v4 binding  
  → Sorting, filtering, pagination  
  → `limit` support using temporary JSON models  
  → Human-readable filter descriptions

- 🤖 **Field Value Prediction**  
  Python microservice + OpenAI predicts invoice fields based on historical data.

- 🧠 **Langfuse Observability**  
  Each AI interaction (prompt, response, model, tokens) is tracked with Langfuse:  
  → `trace` for the user query  
  → `span` for each prompt run  
  → full communication log with metadata (model, tokens, temperature)

---

## 🛠️ Technologies Used

| Layer         | Technology                                                    |
|---------------|---------------------------------------------------------------|
| Backend       | ABAP RAP (`ZFI_ACDOCA` table, OData v4 service)               |
| Frontend      | SAPUI5 (Freestyle, sap.ui.table, mdc.FilterBar)               |
| AI Services   | Python (Flask), Node.js (Express), OpenAI SDK                 |
| Integration   | OData v4 (SAPUI5 ↔ ABAP), REST (SAPUI5 ↔ AI)                  |
| AI Monitoring | Langfuse (tracking prompts, results, tokens, models)          |
| Hosting       | SAP BTP (ABAP Env + Cloud Foundry for AI microservices)       |
| Database      | SAP HANA Cloud (optional – cache or AI training layer)        |

---

## 📚 Additional Topics Covered

- 🧾 Custom ACDOCA-based model (`ZFI_ACDOCA`) for invoice simulation
- 🧠 Prompt engineering with predefined rules and examples
- 🧮 `$top` simulation in UI5 via model slicing
- 🧪 Postman endpoint testing
- 🌐 Cross-tech orchestration of SAP ↔ AI workflows
- 📜 Conversational AI interaction history in the UI

---

## 📦 Deployment

- AI microservices (Python & Node.js) deployed on SAP BTP Cloud Foundry
- ABAP backend logic (RAP + OData v4) runs on SAP BTP ABAP Environment
- SAPUI5 frontend as a BSP app or standalone deployment

---

## 📈 Future Enhancements

- 🔐 Authorization and role-based access (e.g., finance analyst, manager)
- 📊 Charts and visualizations (VizFrame integration)
- 🧠 Custom AI model training using historical invoice data
- 📉 Prompt quality metrics and dashboards in Langfuse


---


# 🧠 AInvoicer

**AInvoicer** to pełnostackowa aplikacja SAP do przetwarzania faktur, łącząca klasyczną logikę finansową z nowoczesnymi możliwościami sztucznej inteligencji. Projekt demonstracyjny pokazuje integrację technologii takich jak ABAP RAP, SAPUI5, OData v4 oraz mikrousług AI na platformie SAP BTP Cloud Foundry.

---

## 💡 Overview

Aplikacja umożliwia:

- Łączenie SAPUI5 z ABAP RAP i OData v4
- Przetwarzanie danych finansowych z modelu `ZFI_ACDOCA`
- Skanowanie i analizę faktur z użyciem AI (OCR + NLP)
- Inteligentne filtrowanie danych z użyciem języka naturalnego
- Monitorowanie zapytań i promptów z wykorzystaniem Langfuse

---

## 🚀 Key Features

- 🔍 **Natural Language Filtering (Langfuse-tracked)**  
  Dzięki OpenAI i Langfuse, zapytania użytkownika w języku naturalnym są przekształcane w filtry OData. Przykład: „Pokaż dokumenty powyżej 5000 EUR z 2024”.

- 📄 **OCR Invoice Scanner**  
  Mikrousługa Node.js + OpenAI analizuje przesłane faktury (PDF/JPG/PNG) i wyodrębnia dane zgodne ze strukturą `ZFI_ACDOCA`.

- 📊 **Dynamiczna Tabela (sap.ui.table)**  
  Z funkcjami:  
  → OData v4 binding  
  → Sortowanie, filtrowanie, paginacja  
  → Obsługa `limit` z wykorzystaniem modelu tymczasowego  
  → Opis aktywnych filtrów w języku naturalnym

- 🤖 **Predykcja wartości pól faktury**  
  Mikrousługa Python + OpenAI przewiduje pola faktury na podstawie danych historycznych.

- 🧠 **Langfuse Observability**  
  Każda interakcja z AI (zapytanie, odpowiedź, model, tokeny) jest śledzona w Langfuse:  
  → `trace` dla zapytania użytkownika  
  → `span` dla konkretnego promptu  
  → pełny zapis komunikacji + metadanych (model, tokeny, temperatura)

---

## 🛠️ Technologies Used

| Warstwa       | Technologia                                                  |
|---------------|--------------------------------------------------------------|
| Backend       | ABAP RAP (tabela `ZFI_ACDOCA`, serwis OData v4)              |
| Frontend      | SAPUI5 (Freestyle, sap.ui.table, mdc.FilterBar)              |
| AI Services   | Python (Flask), Node.js (Express), OpenAI SDK                |
| Integracja    | OData v4 (SAPUI5 ↔ ABAP), REST (SAPUI5 ↔ AI)                 |
| Monitoring AI | Langfuse (śledzenie promptów, wyników, tokenów, modeli)      |
| Hosting       | SAP BTP (ABAP Env + Cloud Foundry dla AI)                    |
| Baza danych   | SAP HANA Cloud (opcjonalnie – cache lub baza uczenia AI)     |

---

## 📚 Additional Topics Covered

- 🧾 Własny model danych ACDOCA (`ZFI_ACDOCA`) dla symulacji dokumentów
- 🧠 Prompt engineering z predefiniowanymi regułami i przykładami
- 🧮 Symulacja `$top` w UI5 przez model slicing
- 🧪 Testowanie endpointów z Postmanem
- 🌐 Orkiestracja SAP ↔ AI w wielu językach
- 📜 Historia interakcji z AI dostępna z poziomu aplikacji

---

## 📦 Deployment

- Mikrousługi AI (Python i Node.js) hostowane w SAP BTP Cloud Foundry
- Backend ABAP (RAP + OData v4) w SAP BTP ABAP Environment
- Frontend SAPUI5 jako aplikacja BSP lub standalone

---

## 📈 Future Enhancements

- 🔐 Autoryzacja i role użytkowników (np. analityk finansowy, manager)
- 📊 Integracja wykresów z VizFrame (trend, porównania)
- 🧠 Trening własnego modelu AI na danych historycznych
- 📉 Statystyki i metryki jakości promptów w Langfuse Dashboard
