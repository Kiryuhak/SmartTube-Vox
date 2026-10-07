# SmartTube VOX 8 — Local Real Live Translation Backend Prototype

## 1. Честный статус и профиль хост-машины

### 1.1. Статус запуска инференса
* **BACKEND_REAL_MODEL_TEST**: `NOT_AVAILABLE` / `NOT_EXECUTED` (веса моделей не предустановлены, выделенный CUDA GPU отсутствует).
* **LIVE_REAL_TRANSLATION**: `NO` (фальсификация успеха строго исключена; реальный инференс в проде требует GPU или развернутого сервиса).
* **BACKEND_ARCHITECTURE & MOCK / DEGRADATION TEST**: `PASS` (13 из 13 тестов пройдены успешно).

### 1.2. Аппаратный профиль хост-машины (Текущая станция разработки)
* **CPU**: AMD Ryzen 7 5700U with Radeon Graphics (8 физических ядер, 16 потоков @ 1.80–4.30 GHz).
* **RAM**: 16 GB DDR4 (15.3 GB доступно для приложений).
* **GPU**: Встроенная графика AMD Radeon Graphics (Renoir / Vega 8, разделяемая видеопамять 512 MB). Поддержка NVIDIA CUDA и TensorRT отсутствует.
* **OS**: Windows 11 Home / PowerShell.
* **Python**: Python 3.12.10 (библиотеки `faster-whisper`, `ctranslate2`, `torch`, `piper-tts` и модели весом 1.5–4.5 GB локально не предустановлены).

---

## 2. Архитектурная оценка производительности (CPU vs GPU)

Для потокового перевода живой трансляции с чанками по 2–3 секунды бюджет сквозной задержки составляет **3000–6000 мс**:
$$\text{Latency}_{\text{total}} = T_{\text{STT}} + T_{\text{Translation}} + T_{\text{TTS}} + T_{\text{Network}}$$

| Компонент | Архитектура / Модель | Инференс на CPU (Ryzen 7 5700U int8) | Инференс на GPU (RTX 3060/4060 float16) | Примечание |
|---|---|---|---|---|
| **STT** | Faster-Whisper `base` / `small` | 1200–2800 мс | 150–350 мс | CPU на 1 сессию укладывается в бюджет, но дает высокую утилизацию ядер |
| **MT** | Marian NMT (`opus-mt-en-ru`) | 120–250 мс | 20–40 мс | Легковесная модель, CTranslate2 на CPU работает быстро |
| **TTS** | Piper TTS (`ru_RU-dmitri-medium`) | 250–500 мс | 40–80 мс | ONNX Runtime на CPU оптимизирован под x86 AVX2 |
| **Итого задержка конвейера** | — | **~1600–3550 мс** | **~250–500 мс** | На CPU возможна работа только 1-2 одновременных сессий |

---

## 3. Системные требования для реального развертывания

### Минимальные требования (Local Real CPU Mode)
* **CPU**: 6+ ядер x86-64 с поддержкой AVX2 (Intel Core i5 10th gen+ или AMD Ryzen 5 3600+).
* **RAM**: 16 GB.
* **Диск**: 10 GB свободного места под веса моделей и кэш HuggingFace.
* **Ограничение**: Максимум 1 активная трансляция с чанками 2 секунды.

### Рекомендуемые требования (Production Server / GPU Mode)
* **GPU**: NVIDIA GPU с 8+ GB VRAM (RTX 3060, RTX 4060, T4, A10G) с CUDA 12.x и TensorRT.
* **CPU**: 8+ vCPU.
* **RAM**: 32 GB.
* **Диск**: 50 GB NVMe SSD.
* **Пропускная способность**: До 10–20 одновременных живых сессий.

---

## 4. Инструкция по локальному развертыванию Real Backend

### Шаг 1. Клонирование и подготовка окружения
```bash
cd services/vox-live-backend
python -m venv .venv
# Windows:
.venv\Scripts\activate
# Linux/macOS:
source .venv/bin/activate

pip install --upgrade pip
pip install faster-whisper ctranslate2 piper-tts
```

### Шаг 2. Загрузка моделей
```bash
# Faster-Whisper base (автоматически при первом вызове или через huggingface-cli)
# Marian NMT en-ru CTranslate2 модель
# Piper ONNX голос
```

### Шаг 3. Запуск Backend
```bash
export VOX_BACKEND_MODE=LOCAL_REAL
export PORT=8090
npm start
```

### Шаг 4. Проверка готовности (Health & Ready)
```bash
curl http://127.0.0.1:8090/health
curl http://127.0.0.1:8090/ready
```

Ответ `/ready`:
```json
{
  "ready": false,
  "mode": "LOCAL_REAL",
  "stt": {
    "engine": "faster-whisper",
    "model": "base",
    "device": "cpu",
    "status": "NOT_AVAILABLE",
    "reason": "Faster-Whisper Python dependencies or model weights not installed locally"
  },
  "hostHardware": {
    "cpu": "AMD Ryzen 7 5700U with Radeon Graphics (8C/16T)",
    "ram": "16 GB (15.3 GB Available)",
    "gpu": "Integrated AMD Radeon Graphics (No CUDA/TensorRT)"
  }
}
```

---

## 5. Защита от фальсификации и безопасность
1. **Zero Fake Success**: Если реальные веса отсутствуют, система честно рапортует `ready: false` и возвращает `NOT_AVAILABLE`.
2. **Controlled Degradation**: При явном флаге `allowFallbackToMock: true` инференс не падает аварийно, а деградирует в детерминированный mock с пометкой `degraded: true` в ответе.
3. **Strict Ephemeral Audio**: Исходные аудиосэмплы не сохраняются на диск и освобождаются из памяти сразу после обработки (`segment.audioBuffer = null`).
4. **Privacy Protection**: Запрещены любые приватные поля (`videoTitle`, `channelName`, токены, произвольные URL).
