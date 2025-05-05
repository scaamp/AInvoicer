import os
from dotenv import load_dotenv
from flask import Flask, request, jsonify
from flask_cors import CORS
from cfenv import AppEnv
from hdbcli import dbapi
import os
from openai import OpenAI
import json


app = Flask(__name__)
CORS(app)
env = AppEnv()
load_dotenv()  # Wczytaj dane z pliku .env

hana_service = 'hana'
hana = env.get_service(label=hana_service)

port = int(os.environ.get('PORT', 4000))
@app.route('/')
def hello():
    if hana is None:
        return "Can't connect to HANA service '{}' – check service name?".format(hana_service)
    else:
        conn = dbapi.connect(address=hana.credentials['host'],
                             port=int(hana.credentials['port']),
                             user=hana.credentials['user'],
                             password=hana.credentials['password'],
                             encrypt='true',
                             sslTrustStore=hana.credentials['certificate'])

        cursor = conn.cursor()
        cursor.execute("select CURRENT_UTCTIMESTAMP from DUMMY")
        ro = cursor.fetchone()
        cursor.close()
        conn.close()

        return "Current time is: " + str(ro["CURRENT_UTCTIMESTAMP"])
        

@app.route('/filter', methods=['POST'])
def filter():
    SYSTEM_PROMPT = """
    <rules>
    Jesteś asystentem AI, który tłumaczy zapytania użytkownika w języku naturalnym na filtry techniczne używane w aplikacjach SAP Fiori (SAPUI5).
    Twoim zadaniem jest zrozumieć intencję użytkownika i przekształcić ją w strukturę filtrów OData v4.

    Zwróć odpowiedź w formacie JSON z dwoma kluczami:
    1. "filters" – lista filtrów, z których każdy zawiera:
    - "path" – nazwa właściwości w PascalCase zgodna z CDS (np. "CompanyCode", "PostingDate")
    - "operator" – operator filtra, np.: EQ, GT, GE, LT, LE, BT, NE, Contains, StartsWith, EndsWith
    - "value1" – wartość filtra
    - "value2" – (opcjonalnie) dla operatorów BT (between)

    2. "description" – jedno zdanie po polsku wyjaśniające, co zostanie wyświetlone po zastosowaniu filtrów.

    Dostępne właściwości (PascalCase) i ich typy danych (masz prawo korzystać TYLKO!!! z tych):
    - CompanyCode: string (4 cyfry)
    - FiscalYear: string (4 cyfry, np. "2024")
    - DocumentNo: string
    - LineItem: string (3 cyfry)
    - PostingDate: data (format YYYY-MM-DD)
    - DocumentDate: data
    - EntryDate: data
    - DocumentType: string
    - Reference: string
    - DebitCredit: string (jeden znak: "S" albo "H")
    - Account: string
    - Vendor: string
    - Customer: string
    - CostCenter: string
    - ProfitCenter: string
    - Segment: string
    - CurrencyCode: string (np. "PLN", "EUR")
    - AmountDocument: liczba zmiennoprzecinkowa
    - AmountLocal: liczba zmiennoprzecinkowa
    - PaymentTerms: string
    - PaymentMethod: string
    - DueDate: data
    - ClearingDoc: string
    - ClearingDate: data

    Odpowiedź ma być WYŁĄCZNIE poprawnym obiektem JSON.
    </rules>

    <example>
    Przykład odpowiedzi:
    {
    "filters": [
        { "path": "CompanyCode", "operator": "EQ", "value1": "1000" },
        { "path": "AmountDocument", "operator": "GT", "value1": "5000" },
        { "path": "PostingDate", "operator": "BT", "value1": "2024-01-01", "value2": "2024-03-31" }
    ],
    "description": "Wyświetlam dokumenty z firmy 1000 o kwocie powyżej 5000 zł zarejestrowane w pierwszym kwartale 2024 roku."
    }
    </example>
    """
    data = request.get_json()
    query = data.get("queryText")

    client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

    try:
        response = client.chat.completions.create(
            model="gpt-4o",
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"Użytkownik wpisał: \"{query}\""}
            ],
            temperature=0.3
        )

        result_text = response.choices[0].message.content.strip()

        # Usuń znaczniki ```json i ```
        if result_text.startswith("```json"):
            result_text = result_text[7:]  # usuwa ```json\n
        if result_text.endswith("```"):
            result_text = result_text[:-3]  # usuwa \n```

        # Przekształć tekst do słownika
        try:
            result_json = json.loads(result_text)
        except json.JSONDecodeError as e:
            print("❌ Błąd dekodowania JSON:", e)
            print("📦 Treść:", result_text)
            return jsonify({"error": "Nieprawidłowy format JSON od OpenAI"}), 500

        return jsonify(result_json)

    except Exception as e:
        return jsonify({"error": str(e)}), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=port)
