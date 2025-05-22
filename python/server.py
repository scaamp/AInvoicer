import os
from dotenv import load_dotenv
from flask import Flask, request, jsonify
from flask_cors import CORS
from cfenv import AppEnv
from hdbcli import dbapi
import os
from openai import OpenAI
import json
import time
from langfuse_service import LangfuseService

# Initialize LangfuseService
langfuse_service = LangfuseService()

app = Flask(__name__)
CORS(app)
env = AppEnv()
load_dotenv()  # Wczytaj dane z pliku .env

hana_service = 'hana'
hana = env.get_service(label=hana_service)

port = int(os.environ.get('PORT', 4000))
client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

@app.route('/')
def hello():
    # Dodaj obsługę błędów dla połączenia HANA
    try:
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
    except Exception as e:
        return f"Error connecting to HANA database: {str(e)}", 500
        


@app.route('/speech', methods=['POST'])
def generate_speech():
    try:
        data = request.get_json()
        text = data.get('text')
        instructions = data.get('instructions', '')

        if not text:
            return jsonify({"error": "Missing 'text' parameter"}), 400

        response = client.audio.speech.create(
            model="gpt-4o-mini-tts",
            voice="shimmer",
            input=text,
            speed=1.3,
            instructions=instructions
        )

        audio_bytes = response.read()  # audio file as binary
        return audio_bytes, 200, {'Content-Type': 'audio/mpeg'}

    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route('/filter', methods=['POST'])
def filter_data():
    try:
        data = request.get_json()
        if not data:
            return jsonify({"error": "No JSON data received"}), 400
            
        query = data.get("queryText")
        if not query:
            return jsonify({"error": "Missing 'queryText' parameter"}), 400

        # Create trace and span
        trace = langfuse_service.create_trace(
            id=str(time.time()),  # Using timestamp as ID
            name="InvoiceFilterPrompt",
            session_id="user123"
        )
        
        # Create span for the LLM call
        span = langfuse_service.create_span(
            trace=trace,
            name="LLM call",
            input=query
        )

        # Get system prompt from Langfuse
        system_prompt = langfuse_service.get_system_prompt("filter_system_prompt")
        if not system_prompt:
            return jsonify({"error": "Failed to fetch system prompt from Langfuse"}), 500

        # Prepare messages for the API call
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": f"Użytkownik wpisał: \"{query}\""}
        ]

        # Make the API call
        response = client.chat.completions.create(
            model="gpt-4o",
            messages=messages,
            temperature=0.3
        )

        # Finalize span with all the details
        langfuse_service.finalize_span(
            span=span,
            name="LLM call",
            input=messages,
            output=response
        )

        result_text = response.choices[0].message.content.strip()

        # Remove JSON markers
        if result_text.startswith("```json"):
            result_text = result_text[7:]
        if result_text.endswith("```"):
            result_text = result_text[:-3]

        try:
            result_json = json.loads(result_text)
            
            if "sortBy" in result_json:
                print(f"✅ Wykryto żądanie sortowania: {result_json['sortBy']}")
            
            # Return only the business logic response without metrics
            return jsonify(result_json)
            
        except json.JSONDecodeError as e:
            print("❌ Błąd dekodowania JSON:", e)
            print("📦 Treść:", result_text)
            return jsonify({"error": "Nieprawidłowy format JSON od OpenAI"}), 500

    except Exception as e:
        print(f"❌ Błąd w endpoincie /filter: {str(e)}")
        return jsonify({"error": str(e)}), 500


# Rozszerzony prompt systemowy obsługujący zaawansowane zapytania
# SYSTEM_PROMPT = """
# <rules>
# Jesteś asystentem AI, który tłumaczy zapytania użytkownika w języku naturalnym na filtry techniczne używane w aplikacjach SAP Fiori (SAPUI5).
# Twoim zadaniem jest zrozumieć intencję użytkownika i przekształcić ją w strukturę filtrów OData v4.

# Zwróć odpowiedź w formacie JSON z następującymi kluczami:
# 1. "filters" – lista filtrów, z których każdy zawiera:
# - "path" – nazwa właściwości w PascalCase zgodna z CDS (np. "CompanyCode", "PostingDate")
# - "operator" – operator filtra, np.: EQ, GT, GE, LT, LE, BT, NE, Contains, StartsWith, EndsWith
# - "value1" – wartość filtra
# - "value2" – (opcjonalnie) dla operatorów BT (between)

# 2. "description" – jedno zdanie po polsku wyjaśniające, co zostanie wyświetlone po zastosowaniu filtrów.

# 3. "sortBy" (opcjonalnie) – jeśli użytkownik prosi o dokumenty z najmniejszą/największą wartością lub podobne zapytania wymagające sortowania, dodaj ten klucz zawierający obiekt:
# - "path" – nazwa właściwości, po której należy sortować
# - "descending" – true dla sortowania malejącego (największe pierwsze), false dla rosnącego (najmniejsze pierwsze)

# 4. "limit" (opcjonalnie) – liczba dokumentów do wyświetlenia, jeśli użytkownik prosi o N najwyższych/najniższych wartości

# Dostępne właściwości (PascalCase) i ich typy danych (masz prawo korzystać TYLKO!!! z tych):
# - CompanyCode: string (4 cyfry)
# - FiscalYear: string (4 cyfry, np. "2024")
# - DocumentNo: string
# - LineItem: string (3 cyfry)
# - PostingDate: data (format YYYY-MM-DD)
# - DocumentDate: data
# - EntryDate: data
# - DocumentType: string
# - Reference: string
# - DebitCredit: string (jeden znak: "S" albo "H")
# - Account: string
# - Vendor: string
# - Customer: string
# - CostCenter: string
# - ProfitCenter: string
# - Segment: string
# - CurrencyCode: string (np. "PLN", "EUR")
# - AmountDocument: liczba zmiennoprzecinkowa
# - AmountLocal: liczba zmiennoprzecinkowa
# - PaymentTerms: string
# - PaymentMethod: string
# - DueDate: data
# - ClearingDoc: string
# - ClearingDate: data

# Odpowiedź ma być WYŁĄCZNIE poprawnym obiektem JSON.

# WAŻNE WSKAZÓWKI:
# 1. Jeśli użytkownik pyta o "największą kwotę", "najwyższą wartość", "najdroższy dokument" itp., użyj klucza "sortBy" z odpowiednią ścieżką (zwykle "AmountDocument" lub "AmountLocal") i ustaw "descending" na true.
# 2. Jeśli użytkownik pyta o "najmniejszą kwotę", "najniższą wartość" itp., użyj klucza "sortBy" z odpowiednią ścieżką i ustaw "descending" na false.
# 3. Jeśli użytkownik pyta o "N najdroższych" lub "N najtańszych", dodaj klucz "limit" z odpowiednią liczbą.
# 4. Jeśli użytkownik używa określeń typu "tylko", "wyłącznie", "jedynie", "pokaż mi tylko" - upewnij się, że filtry są odpowiednio ograniczające.
# 5. W przypadku wątpliwości, staraj się odgadnąć intencję użytkownika na podstawie kontekstu jego zapytania.
# </rules>

# <examples>
# Przykład 1 - standardowe filtry:
# Zapytanie: "Dokumenty z firmy 1000 o kwocie powyżej 5000 zł z pierwszego kwartału 2024"
# {
#   "filters": [
#     { "path": "CompanyCode", "operator": "EQ", "value1": "1000" },
#     { "path": "AmountDocument", "operator": "GT", "value1": "5000" },
#     { "path": "PostingDate", "operator": "BT", "value1": "2024-01-01", "value2": "2024-03-31" }
#   ],
#   "description": "Wyświetlam dokumenty z firmy 1000 o kwocie powyżej 5000 zł zarejestrowane w pierwszym kwartale 2024 roku."
# }

# Przykład 2 - z sortowaniem:
# Zapytanie: "Dokument z największą kwotą z kwietnia 2024"
# {
#   "filters": [
#     { "path": "PostingDate", "operator": "BT", "value1": "2024-04-01", "value2": "2024-04-30" }
#   ],
#   "sortBy": {
#     "path": "AmountDocument",
#     "descending": true
#   },
#   "limit": 1,
#   "description": "Wyświetlam dokument o największej kwocie zarejestrowany w kwietniu 2024 roku."
# }

# Przykład 3 - z sortowaniem i limitem:
# Zapytanie: "5 najmniejszych płatności dla dostawcy 1234 w 2023 roku"
# {
#   "filters": [
#     { "path": "Vendor", "operator": "EQ", "value1": "1234" },
#     { "path": "FiscalYear", "operator": "EQ", "value1": "2023" }
#   ],
#   "sortBy": {
#     "path": "AmountDocument",
#     "descending": false
#   },
#   "limit": 5,
#   "description": "Wyświetlam 5 dokumentów o najniższej kwocie dla dostawcy 1234 z roku fiskalnego 2023."
# }
# </examples>
# '''
# """

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=port)