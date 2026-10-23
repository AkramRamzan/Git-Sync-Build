#!/usr/bin/env python3
"""
Anti-Gravity Termux Bridge Server (v2.1)
Allows Aro Code (Android App) to execute Anti-Gravity models:
- Gemini 3.8 Flash (High)
- Claude Sonnet 4.6 (Thinking)
- Claude Opus 4.6 (Thinking)
- GPT-OSS 120B (Medium)
- Gemini 4 Argon

Usage in Termux:
    pkg update && pkg install python -y
    pip install fastapi uvicorn pydantic sse-starlette
    termux-wake-lock
    python server.py
"""

import os
import sys
import json
import shutil
import asyncio
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

app = FastAPI(title="Anti-Gravity Termux Bridge", version="2.1")

# Enable CORS for local network and webview
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class ChatRequest(BaseModel):
    model: str = "gemini-3.8-flash"
    prompt: str
    system_prompt: str = ""

def find_agy_binary():
    """Locate the official agy or antigravity binary in Termux/Linux environment."""
    candidates = [
        shutil.which("agy"),
        shutil.which("antigravity"),
        os.path.expanduser("~/.local/bin/agy"),
        os.path.expanduser("~/.npm-global/bin/agy"),
        "/data/data/com.termux/files/usr/bin/agy",
        "/data/data/com.termux/files/home/.local/bin/agy",
        "/usr/local/bin/agy",
        "/usr/bin/agy"
    ]
    for c in candidates:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return shutil.which("agy") or "agy"

@app.get("/")
@app.get("/v1/health")
async def health_check():
    agy_bin = find_agy_binary()
    return {
        "status": "ok",
        "engine": "Anti-Gravity Termux Bridge v2.1",
        "agy_binary": agy_bin,
        "available_models": [
            "gemini-3.8-flash",
            "claude-sonnet-4.6",
            "claude-opus-4.6",
            "gpt-oss-120b",
            "gemini-4-argon"
        ]
    }

@app.get("/v1/models")
async def list_models():
    return {
        "models": [
            {"id": "gemini-3.8-flash", "name": "Gemini 3.8 Flash ⚡"},
            {"id": "claude-sonnet-4.6", "name": "Claude Sonnet 4.6 (Thinking) 🧠"},
            {"id": "claude-opus-4.6", "name": "Claude Opus 4.6 (Thinking) 🚀"},
            {"id": "gpt-oss-120b", "name": "GPT-OSS 120B (Medium) 🤖"},
            {"id": "gemini-4-argon", "name": "Gemini 4 Argon 🔬"}
        ]
    }

async def stream_agy_execution(model: str, full_prompt: str):
    """
    Spawns the local agy CLI process in Termux and streams output lines via SSE.
    Uses stdin piping to completely bypass Linux command-line ARG_MAX limits.
    """
    agy_bin = find_agy_binary()

    # Exact agy internal CLI model identifiers
    model_id_map = {
        "gemini-3.8-flash":  "gemini-3.8-flash-high",
        "claude-sonnet-4.6": "claude-sonnet-4-6",
        "claude-opus-4.6":   "claude-opus-4-6-thinking",
        "gpt-oss-120b":      "gpt-oss-120b-medium",
        "gemini-4-argon":    "gemini-3.8-flash-high",
    }
    agy_model_name = model_id_map.get(model, model)

    # Use stdin piping with --input-format text to bypass ARG_MAX
    cmd = [
        agy_bin,
        "--model", agy_model_name,
        "--dangerously-skip-permissions",
        "--input-format", "text"
    ]

    print(f"\n[Termux Bridge] Executing model: '{agy_model_name}' (stdin prompt length: {len(full_prompt)} chars)")

    try:
        process = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )

        # Feed stdin concurrently in background to prevent buffer deadlocks
        async def feed_stdin():
            try:
                process.stdin.write(full_prompt.encode("utf-8"))
                await process.stdin.drain()
                process.stdin.close()
            except Exception as e:
                print(f"[Termux Bridge Stdin Error] {e}")

        asyncio.create_task(feed_stdin())
    except Exception as e:
        err_msg = f"⚠️ Error launching '{agy_bin}': {str(e)}."
        print(f"[Termux Bridge Error] {err_msg}")
        yield f"data: {json.dumps({'token': err_msg})}\n\n"
        yield "data: [DONE]\n\n"
        return

    token_count = 0
    # Stream stdout tokens in real-time
    while True:
        line = await process.stdout.readline()
        if not line:
            break
        text = line.decode("utf-8", errors="replace")
        token_count += 1
        yield f"data: {json.dumps({'token': text})}\n\n"

    stderr_output = await process.stderr.read()
    if stderr_output and (token_count == 0 or process.returncode != 0):
        err_text = stderr_output.decode("utf-8", errors="replace").strip()
        if err_text:
            print(f"[Termux Bridge Stderr] {err_text}")
            yield f"data: {json.dumps({'token': f'\\n\\n⚠️ Notice: {err_text}\\n\\n'})}\n\n"

    await process.wait()
    yield "data: [DONE]\n\n"

@app.post("/v1/chat/stream")
async def chat_stream(req: ChatRequest):
    print(f"[Termux Bridge] Received chat stream request for model: {req.model}")
    combined = f"{req.system_prompt}\n\n{req.prompt}".strip() if req.system_prompt else req.prompt
    return StreamingResponse(
        stream_agy_execution(req.model, combined),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )

if __name__ == "__main__":
    import uvicorn
    print("\n" + "="*55)
    print("⚡ Anti-Gravity Termux Bridge Server Started (v2.1)!")
    print("📍 Host: http://0.0.0.0:8000 (accessible via 127.0.0.1:8000)")
    print("🚀 Stdin stream mode enabled: No file or prompt size limits!")
    print("📱 Aro Code app will automatically connect to this URL.")
    print("="*55 + "\n")
    uvicorn.run(app, host="0.0.0.0", port=8000)
