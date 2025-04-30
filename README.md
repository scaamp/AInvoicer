# 🧠 AInvoicer

AInvoicer is a fullstack SAP-based invoice processing application that combines classic financial processing with modern AI capabilities.

## 💡 Overview

This project demonstrates the integration of:

- ✅ **ABAP RAP** – core business logic, data model and OData v4 service
- ✅ **SAPUI5 Freestyle App** – custom frontend with PDF upload and table view
- ✅ **OData v4** – RESTful connection between frontend and backend
- ✅ **Python (Flask)** – for AI features: OCR (from PDF) and cost prediction
- ✅ **SAP BTP Cloud Foundry** – deployment of Python microservice
- ✅ **SAP HANA Cloud** – optional cache layer for analytics/AI input
- ✅ **GitHub** – full code versioned and open

---

## 🚀 Features

- Upload PDF invoices and extract data (OCR)
- Store and display invoices from `ZFI_ACDOCA` (custom ACDOCA-like table)
- Predict cost fields using OpenAI models (e.g. GPT-4)
- Analyze entries in SAPUI5 `Table` (with OData v4)
- Modular architecture for learning SAP BTP fullstack

---

## 🛠️ Technologies

| Layer        | Stack                                                     |
|--------------|-----------------------------------------------------------|
| Backend      | ABAP RAP (ZFI_ACDOCA table, OData v4 service)             |
| Frontend     | SAPUI5 (Freestyle)                                        |
| AI Service   | Python Flask + OpenAI SDK                                 |
| Integration  | REST (between ABAP ↔ Python), OData v4 (UI5 ↔ ABAP)       |
| Hosting      | SAP BTP (ABAP Environment + Cloud Foundry)                |
| Database     | SAP HANA Cloud (for optional caching or ML training data) |
