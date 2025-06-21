const express = require('express');
const fs = require('fs');
const path = require('path');
const cors = require('cors');

const app = express();
const PORT = 4567;

app.use(cors());
app.use(express.json());

// Раздача static-файлов из папки public
app.use(express.static(path.join(__dirname, 'public')));

app.post('/save-coords', (req, res) => {
  const data = req.body;
  const filePath = path.join(__dirname, 'coords.json');
  fs.writeFile(filePath, JSON.stringify(data, null, 2), err => {
    if (err) return res.status(500).send('Ошибка');
    console.log('Координаты сохранены:', data);
    res.send('OK');
  });
});

app.listen(PORT, () => console.log(`Сервер запущен: http://localhost:${PORT}`));
