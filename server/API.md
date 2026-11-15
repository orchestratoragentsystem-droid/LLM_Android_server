# LLM-сервер: API

Формат OpenAI (подмножество). Адрес: `http://127.0.0.1:8760/v1` (порт задаётся на экране сервера).
Ключ клиента: `Authorization: Bearer <ключ>` (или `X-Auth-Token`). Ключи создаются на экране сервера.

## Маршруты
- `GET /health` — без ключа: готова ли модель, окно.
- `GET /v1/models`
- `POST /v1/chat/completions` — чат, `stream: true|false`.
- `GET /queue` — кто выполняется и ждёт, статистика по клиентам.

## Что принимает `messages[].content`
Строка или массив частей:
- `{"type":"text","text":"..."}`
- `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}` — большие фото уменьшаются до 1024 px.
- `{"type":"input_audio","input_audio":{"data":"<base64>","format":"wav"}}` — wav, mp3, flac; для сырого
  PCM: `"format":"pcm16","sample_rate":16000`.
- `{"type":"video_url","video_url":{"url":"data:video/mp4;base64,...","frames":6,"max_side":448}}` —
  сервер берёт из видео кадры (по умолчанию 6) и отдаёт их модели по порядку. Звук видео не используется.

Роли: `system`, `user`, `assistant` (в т.ч. с `tool_calls`), `tool`.

## Инструменты
Передаются как в OpenAI (`tools: [{"type":"function","function":{...}}]`). Сервер ничего не
выполняет: он возвращает `tool_calls` и `finish_reason: "tool_calls"`, клиент сам вызывает инструмент
и присылает следующий запрос с сообщением `role: "tool"`. При этом сервер не пересчитывает историю, если
запрос продолжает предыдущий.

## Очередь
Модель одна, запросы выполняются по одному. Порядок: приоритет, затем справедливость между клиентами,
затем время прихода. Заголовки запроса:
- `X-Priority: high|normal|low` — можно только понизить приоритет клиента.
- `X-Queue-Timeout: <секунды>` — сколько ждать в очереди (по умолчанию 120, максимум 900).
- `X-Supersede-Key: <ключ>` — новый запрос с тем же ключом от того же клиента заменяет ждущий старый
  (удобно для периодических задач: «берём только свежий кадр»).

Лимиты: 50 запросов всего, 4 на клиента, 96 МБ данных в очереди (иначе 429). Если клиент оборвал
соединение, ожидающий запрос отменяется. Пока запрос ждёт, потоковый ответ присылает комментарии
`: queued position=N`.

## Примеры
```bash
KEY=ключ_с_экрана
curl -s http://127.0.0.1:8760/v1/chat/completions -H "Authorization: Bearer $KEY" \
  -d '{"messages":[{"role":"user","content":"Привет!"}]}'

# фото
B64=$(base64 -w0 photo.jpg)
curl -s http://127.0.0.1:8760/v1/chat/completions -H "Authorization: Bearer $KEY" \
  -d "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Что на фото?\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,$B64\"}}]}]}"

# очередь
curl -s http://127.0.0.1:8760/queue -H "Authorization: Bearer $KEY"
```
