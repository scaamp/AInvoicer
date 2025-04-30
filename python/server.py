import os
from flask import Flask, request, jsonify
from flask_cors import CORS
from cfenv import AppEnv
from hdbcli import dbapi

app = Flask(__name__)
CORS(app)
env = AppEnv()

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
        

@app.route('/predict', methods=['POST'])
def predict():
    data = request.get_json()

    # Przykład: odbierz kwotę i walutę z żądania
    invoice_amount = data.get("amount", 0)
    currency = data.get("currency", "EUR")

    # Przykładowa logika predykcji – tutaj po prostu mnożymy
    predicted_cost = float(invoice_amount) * 1.23

    return jsonify({
        "predicted_cost": predicted_cost,
        "currency": currency,
        "message": "Prediction done successfully"
    })


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=port)
