# 🧠 AInvoicer

**AInvoicer** is a fullstack SAP-based invoice processing application that combines traditional finance logic with advanced AI capabilities. It serves as a comprehensive demo project for integrating ABAP RAP, SAPUI5, OData v4, and AI microservices within SAP BTP Cloud Foundry.

---

## 💡 Overview

This project showcases how to build and deploy a modular invoice processing system that:

- Connects frontend (SAPUI5) with backend (ABAP RAP + OData v4)
- Processes and analyzes financial documents from `ZFI_ACDOCA`
- Supports AI-based PDF invoice scanning (OCR) and prediction
- Enables smart filtering using natural language queries (NLP)
- Deploys AI services (Python + Node.js) to SAP BTP Cloud Foundry

---

## 🚀 Key Features

- 🔍 **Natural Language Table Filtering**  
  Use AI (Python + OpenAI API SDK) to translate user input into technical OData filters. Example: "Show documents over 5000 EUR from 2024".

- 📄 **AI-Powered Invoice Scanning**  
  Upload invoice PDFs and use OpenAI API (Node.js) to extract structured data like amounts, dates, and accounts.

- 📊 **Dynamic Table UI (sap.ui.table)**  
  SAPUI5 Table with:  
  → OData v4 binding  
  → Sort, filter, paginate  
  → Dynamic row limiting via model slicing  
  → Highlight active filters in human-readable format

- 🤖 **Value fields predictions with OpenAI**  
  Run inference logic to estimate invoice fields based on past data.

- 📦 **Fullstack Integration**  
  ABAP RAP → OData v4 → SAPUI5 ↔ Python/Node microservices over HTTP.

---

## 🛠️ Technologies Used

| Layer        | Stack                                                     |
|--------------|-----------------------------------------------------------|
| Backend      | ABAP RAP (ZFI_ACDOCA table, OData v4 service)             |
| Frontend     | SAPUI5 (Freestyle)                                        |
| AI Services  | Python (Flask), Node.js (Express), OpenAI SDK             |
| Integration  | OData v4 (SAPUI5 ↔ ABAP), REST (SAPUI5 ↔ AI services)         |
| Hosting      | SAP BTP (ABAP Env + Cloud Foundry for AI)                 |
| Database     | SAP HANA Cloud (optional layer for cache/AI training)     |

---

## 📚 Additional Topics Covered

- 🧾 **Custom ACDOCA-based Model** (`ZFI_ACDOCA`) for invoice simulations
- 🧠 **Prompt engineering** for accurate NLP-to-filter conversion
- 🧮 **Model slicing** in UI5 to simulate `$top` behavior
- 🗃️ **Temporary JSON models** for row limiting in UI5
- 🧪 **Postman testing** for endpoint validation
- 🌐 **Cross-technology orchestration** on SAP BTP
- 📜 **Audit trail of queries and AI interactions** in conversational UI

---

## 📦 Deployment

- Python and Node.js AI microservices deployed to SAP BTP Cloud Foundry
- ABAP logic runs on SAP BTP ABAP Environment
- Frontend (SAPUI5) deployed via BSP application or standalone UI5 app

---

## 🧠 Future Enhancements

- 🔐 Authorization and business roles (e.g. finance analyst, manager)
- 📈 Trend analysis with charts (VizFrame integration)
- 🔄 Training custom AI model based on historical invoice data
