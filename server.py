#!/usr/bin/env python3
"""
Anti-Gravity Termux Bridge Server (v2.2) - Autonomous Workspace Edition
Allows Aro Code (Android App) to execute Anti-Gravity models:
- Gemini 3.8 Flash (High)
- Claude Sonnet 4.6 (Thinking)
- Claude Opus 4.6 (Thinking)
- GPT-OSS 120B (Medium)
- Gemini 4 Argon

Now featuring:
- Autonomous Physical Workspace Mounting (--add-dir + cwd)
- Zero Permission Prompts (--dangerously-skip-permissions)
- Stdin Piping (No ARG_MAX Linux limits)
- Clean ANSI Terminal Stripping for Native UI Markdown
- Immediate SSE Connection Heartbeat

Usage in Termux:
    pkg update && pkg install python -y
    pip install fastapi uvicorn pydantic sse-starlette
    termux-wake-lock
    python server.py
"""

import os
import sys
import re
import json
import shutil
import asyncio
from typing import Optional
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

app = FastAPI(title="Anti-Gravity Termux Bridge", version="2.2")

# Regex to strip ANSI terminal escape color codes for clean Android markdown
ANSI_ESCAPE = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')

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
    project_path: Optional[str] = None

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
        "engine": "Anti-Gravity Termux Bridge v2.2 (Autonomous Workspace Edition)",
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

async def stream_agy_execution(model: str, full_prompt: str, project_path: Optional[str] = None):
    """
    Spawns the local agy CLI process in Termux and streams output lines via SSE.
    Mounts physical project directory if provided to enable full autonomous file editing.
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

    # Determine execution working directory & add-dir if project_path is valid
    exec_cwd = None
    cmd = [
        agy_bin,
        "--model", agy_model_name,
        "--dangerously-skip-permissions",
        "--input-format", "text"
    ]

    if project_path:
        candidates = [
            project_path,
            os.path.join("/storage/emulated/0", project_path.lstrip("/")),
            os.path.join(os.path.expanduser("~"), project_path.lstrip("/")),
            f"/sdcard/{project_path.lstrip('/')}"
        ]
        for c in candidates:
            if os.path.exists(c) and os.path.isdir(c):
                exec_cwd = os.path.abspath(c)
                cmd.extend(["--add-dir", exec_cwd])
                break

    print(f"\n[Termux Bridge] Executing model: '{agy_model_name}' (cwd: {exec_cwd or 'default'}, stdin: {len(full_prompt)} chars)")

    # Immediate heartbeat token to establish SSE stream without client timeout
    yield f"data: {json.dumps({'token': ''})}\n\n"

    try:
        process = await asyncio.create_subprocess_exec(
            *cmd,
            cwd=exec_cwd,
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
        clean_text = ANSI_ESCAPE.sub('', text)
        token_count += 1
        yield f"data: {json.dumps({'token': clean_text})}\n\n"

    stderr_output = await process.stderr.read()
    if stderr_output and (token_count == 0 or process.returncode != 0):
        err_text = stderr_output.decode("utf-8", errors="replace").strip()
        clean_err = ANSI_ESCAPE.sub('', err_text)
        if clean_err:
            print(f"[Termux Bridge Stderr] {clean_err}")
            yield f"data: {json.dumps({'token': f'\\n\\n⚠️ Notice: {clean_err}\\n\\n'})}\n\n"

    await process.wait()
    yield "data: [DONE]\n\n"

@app.post("/v1/chat/stream")
async def chat_stream(req: ChatRequest):
    print(f"[Termux Bridge] Received chat stream request for model: {req.model} (project_path: {req.project_path})")
    combined = f"{req.system_prompt}\n\n{req.prompt}".strip() if req.system_prompt else req.prompt
    return StreamingResponse(
        stream_agy_execution(req.model, combined, req.project_path),
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
    print("⚡ Anti-Gravity Termux Bridge Server Started (v2.2)!")
    print("📍 Host: http://0.0.0.0:8000 (accessible via 127.0.0.1:8000)")
    print("📁 Autonomous Workspace Mode Enabled!")
    print("🚀 Stdin stream mode enabled: No file or prompt size limits!")
    print("📱 Aro Code app will automatically connect to this URL.")
    print("="*55 + "\n")
    uvicorn.run(app, host="0.0.0.0", port=8000)
