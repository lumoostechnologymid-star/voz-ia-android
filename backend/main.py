import os
from typing import List

import httpx
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel

app = FastAPI(title="Voz IA Backend", version="0.2.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-5.6-luna")
ELEVENLABS_API_KEY = os.getenv("ELEVENLABS_API_KEY", "")
SYSTEM_INSTRUCTIONS = os.getenv(
    "SYSTEM_INSTRUCTIONS",
    "Eres un asistente conversacional útil, natural y claro. Responde en español de México salvo que el usuario pida otro idioma. Mantén respuestas breves para conversación por voz."
)

class HistoryItem(BaseModel):
    role: str
    text: str

class ChatRequest(BaseModel):
    message: str
    history: List[HistoryItem] = []

class SpeakRequest(BaseModel):
    text: str
    voice_id: str

@app.get("/health")
def health():
    return {
        "ok": True,
        "chat_configured": bool(OPENAI_API_KEY),
        "voice_configured": bool(ELEVENLABS_API_KEY),
        "model": OPENAI_MODEL,
    }

@app.post("/voice-profile")
async def create_voice_profile(
    name: str = Form(...),
    consent: bool = Form(...),
    file: UploadFile = File(...),
):
    if not consent:
        raise HTTPException(status_code=400, detail="Se requiere autorización explícita para crear el perfil de voz.")
    if not ELEVENLABS_API_KEY:
        raise HTTPException(status_code=503, detail="Falta configurar ELEVENLABS_API_KEY.")

    audio = await file.read()
    if not audio:
        raise HTTPException(status_code=400, detail="La muestra de audio está vacía.")
    if len(audio) > 25 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="La muestra es demasiado grande.")

    files = {
        "files": (file.filename or "sample.m4a", audio, file.content_type or "audio/mp4")
    }
    data = {
        "name": name,
        "description": "Perfil creado desde Voz IA con autorización confirmada por el usuario.",
        "remove_background_noise": "false",
    }
    headers = {"xi-api-key": ELEVENLABS_API_KEY}
    async with httpx.AsyncClient(timeout=120.0) as client:
        r = await client.post("https://api.elevenlabs.io/v1/voices/add", headers=headers, data=data, files=files)
    if r.status_code >= 300:
        raise HTTPException(status_code=502, detail=f"Proveedor de voz: {r.text}")
    payload = r.json()
    return {"voice_id": payload.get("voice_id"), "requires_verification": payload.get("requires_verification", False)}

def extract_output_text(payload: dict) -> str:
    parts = []
    for item in payload.get("output", []):
        if not isinstance(item, dict):
            continue
        for content in item.get("content", []):
            if isinstance(content, dict) and isinstance(content.get("text"), str):
                parts.append(content["text"])
    return "\n".join(parts).strip()

@app.post("/chat")
async def chat(req: ChatRequest):
    if not OPENAI_API_KEY:
        return {"reply": "El modo de demostración funciona, pero falta configurar la clave de OpenAI en el servidor."}

    transcript = []
    for item in req.history[-12:]:
        who = "Usuario" if item.role == "user" else "Asistente"
        transcript.append(f"{who}: {item.text}")
    transcript.append(f"Usuario: {req.message}")

    headers = {
        "Authorization": f"Bearer {OPENAI_API_KEY}",
        "Content-Type": "application/json",
    }
    body = {
        "model": OPENAI_MODEL,
        "instructions": SYSTEM_INSTRUCTIONS,
        "input": "\n".join(transcript),
    }
    async with httpx.AsyncClient(timeout=90.0) as client:
        r = await client.post("https://api.openai.com/v1/responses", headers=headers, json=body)
    if r.status_code >= 300:
        raise HTTPException(status_code=502, detail=f"Servicio de IA: {r.text}")

    reply = extract_output_text(r.json())
    if not reply:
        raise HTTPException(status_code=502, detail="La IA respondió sin texto utilizable.")
    return {"reply": reply}

@app.post("/speak")
async def speak(req: SpeakRequest):
    if not ELEVENLABS_API_KEY:
        raise HTTPException(status_code=503, detail="Falta configurar ELEVENLABS_API_KEY.")
    headers = {"xi-api-key": ELEVENLABS_API_KEY, "Content-Type": "application/json"}
    body = {"text": req.text, "model_id": "eleven_multilingual_v2"}
    url = f"https://api.elevenlabs.io/v1/text-to-speech/{req.voice_id}?output_format=mp3_44100_128"
    async with httpx.AsyncClient(timeout=120.0) as client:
        r = await client.post(url, headers=headers, json=body)
    if r.status_code >= 300:
        raise HTTPException(status_code=502, detail=f"Síntesis de voz: {r.text}")
    return Response(content=r.content, media_type="audio/mpeg")
