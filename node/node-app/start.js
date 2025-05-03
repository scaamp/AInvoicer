const express = require('express');
const bodyParser = require('body-parser');
const cors = require('cors'); // ✅ Dodano CORS
const { OpenAI } = require('openai');
require('dotenv').config();

const app = express();

// ✅ Użycie CORS – wersja domyślna (zezwala na wszystko podczas developmentu)
app.use(cors());

app.use(bodyParser.json({ limit: '10mb' }));

// Inicjalizacja klienta OpenAI
const openai = new OpenAI({
    apiKey: process.env.OPENAI_API_KEY,
});

// Endpoint POST /analyze
app.post('/analyze', async (req, res) => {
    try {
        const image_url = "data:image/jpeg;base64," + req.body.image_url;

        if (!image_url) {
            return res.status(400).json({ error: 'Missing image_url in request body.' });
        }

        const prompt = `
            Przeanalizuj załączoną fakturę (plik PDF/JPG/PNG) i zwróć dane w formacie JSON zgodnym ze strukturą tabeli SAP \`zfi_acdoca\`.

            Zwróć **wyłącznie JSON**, zawierający poniższe pola jako klucze. Jeśli pole nie występuje w dokumencie lub nie da się go rozpoznać, przypisz pusty string \`""\`.

            Oto lista wymaganych pól:
            - client
            - company_code
            - fiscal_year
            - document_no
            - line_item
            - posting_date
            - document_date
            - entry_date
            - document_type
            - reference
            - debit_credit
            - account
            - vendor
            - customer
            - cost_center
            - profit_center
            - segment
            - currency_code
            - amount_document
            - amount_local
            - payment_terms
            - payment_method
            - due_date
            - clearing_doc
            - clearing_date
            - created_by
            - created_at
            - changed_by
            - changed_at

            Przykład odpowiedzi:
            {
            "client": "100",
            "company_code": "2000",
            "fiscal_year": "2024",
            "document_no": "5100001234",
            ...
            "created_by": "",
            "created_at": "",
            "changed_by": "",
            "changed_at": ""
            }

            Nie dodawaj żadnego opisu, komentarza, ani dodatkowych tekstów. Zwróć **tylko JSON**.
        `;
        
        const response = await openai.chat.completions.create({
            model: 'gpt-4o',
            messages: [
                {
                    role: 'user',
                    content: [
                        { type: 'text', text: prompt },
                        {
                            type: 'image_url',
                            image_url: {
                                url: image_url,
                            },
                        },
                    ],
                },
            ],
            max_tokens: 1000,
        });

        const jsonText = response.choices[0].message.content;
        res.json({ result: jsonText });
    } catch (error) {
        console.error('Error:', error.message);
        res.status(500).json({ error: 'Internal Server Error', details: error.message });
    }
});

// Testowy endpoint
app.get('/', function (req, res) {
    res.send('Hello from Node!');
});

const port = process.env.PORT || 3000;
app.listen(port, function () {
    console.log('Server running on port ' + port);
});
