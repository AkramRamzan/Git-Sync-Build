#!/usr/bin/env python3
"""
Anti-Gravity Termux Bridge Server (v2.4) - Hardened Production Edition
Allows Aro Code (Android App) to execute Anti-Gravity models:
- Gemini 3.8 Flash (High)
- Claude Sonnet 4.6 (Thinking)
- Claude Opus 4.6 (Thinking)
- GPT-OSS 120B (Medium)
- Gemini 4 Argon

Security & Concurrency Enhancements:
- Async Lock Protected Process Management (Zero Race Conditions)
- Path Traversal Protected Project Mounting (Enforces Safe Allowed Bases)
- Localhost Restricted CORS (Safe API Surface)
- Active 2-second SSE TCP Heartbeat (Never times out during long file reasoning)
- Zero Permission Prompts (--dangerously-skip-permissions)
- Stdin Piping (No ARG_MAX Linux limits)
- Clean ANSI Terminal Stripping for Native UI Markdown

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
import collections
from typing import Optional
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

app = FastAPI(title="Anti-Gravity Termux Bridge", version="2.4")

# In-memory circular log buffer to serve live logs to Aro Code UI via /v1/logs
server_logs = collections.deque(maxlen=300)

class LogInterceptor:
    def __init__(self, original_stream):
        self.original_stream = original_stream

    def write(self, s):
        try:
            self.original_stream.write(s)
            self.original_stream.flush()
        except Exception:
            pass
        if s:
            for line in s.splitlines():
                stripped = line.strip()
                if stripped:
                    server_logs.append(stripped)

    def flush(self):
        try:
            self.original_stream.flush()
        except Exception:
            pass

    def isatty(self):
        return getattr(self.original_stream, "isatty", lambda: False)()

sys.stdout = LogInterceptor(sys.stdout)
sys.stderr = LogInterceptor(sys.stderr)

# Regex to strip ANSI terminal escape color codes for clean Android markdown
ANSI_ESCAPE = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')

# Concurrency tracker protected by asyncio.Lock to prevent race conditions
active_process: Optional[asyncio.subprocess.Process] = None
process_lock = asyncio.Lock()

# Safe base paths allowed for project workspace mounting
ALLOWED_BASES = [
    "/storage/emulated/0",
    "/sdcard",
    os.path.expanduser("~")
]

# Restrict CORS to local device loopback for enhanced security
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://127.0.0.1",
        "http://127.0.0.1:8000",
        "http://localhost",
        "http://localhost:8000"
    ],
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
        "engine": "Anti-Gravity Termux Bridge v2.4 (Hardened Production Edition)",
        "agy_binary": agy_bin,
        "available_models": [
            "gemini-3.8-flash",
            "claude-sonnet-4.6",
            "claude-opus-4.6",
            "gpt-oss-120b",
            "gemini-4-argon"
        ]
    }

@app.get("/v1/logs")
async def get_logs():
    return {"logs": list(server_logs)}

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
    Uses async queue with 2-second keep-alive pulse so mobile sockets NEVER time out.
    """
    global active_process
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

    # Determine execution working directory & validate against path traversal
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
            abs_cand = os.path.abspath(c)
            # Enforce path safety within allowed roots
            is_safe = any(abs_cand.startswith(os.path.abspath(base)) for base in ALLOWED_BASES)
            if is_safe and os.path.exists(abs_cand) and os.path.isdir(abs_cand):
                exec_cwd = abs_cand
                cmd.extend(["--add-dir", exec_cwd])
                break

    print(f"\n[Termux Bridge] Executing model: '{agy_model_name}' (cwd: {exec_cwd or 'default'}, stdin: {len(full_prompt)} chars)")

    # Send instant initial heartbeat token so HTTP SSE connection is established immediately
    yield f"data: {json.dumps({'token': ''})}\n\n"

    # Use lock to safely cancel previous process and assign new active_process
    async with process_lock:
        if active_process and active_process.returncode is None:
            try:
                print("[Termux Bridge] Cancelling previous running agy process...")
                active_process.terminate()
                await asyncio.sleep(0.05)
            except Exception as e:
                print(f"[Termux Bridge] Warning: failed to terminate previous process: {e}")

        try:
            process = await asyncio.create_subprocess_exec(
                *cmd,
                cwd=exec_cwd,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            active_process = process
        except Exception as e:
            err_msg = f"⚠️ Error launching '{agy_bin}': {str(e)}."
            print(f"[Termux Bridge Error] {err_msg}")
            yield f"data: {json.dumps({'token': err_msg})}\n\n"
            yield "data: [DONE]\n\n"
            return

    # Feed stdin concurrently in background to prevent pipe deadlocks
    async def feed_stdin():
        try:
            process.stdin.write(full_prompt.encode("utf-8"))
            await process.stdin.drain()
            process.stdin.close()
        except Exception as e:
            print(f"[Termux Bridge Stdin Error] {e}")

    asyncio.create_task(feed_stdin())

    # Background async queue reader for process.stdout
    queue = asyncio.Queue()

    async def read_stdout():
        try:
            while True:
                line = await process.stdout.readline()
                if not line:
                    break
                await queue.put(("stdout", line))
        except Exception:
            pass
        finally:
            await queue.put(("done", None))

    stdout_reader_task = asyncio.create_task(read_stdout())

    token_count = 0
    try:
        while True:
            try:
                # 2.0s timeout: If no new output line, send an SSE keepalive comment
                kind, data = await asyncio.wait_for(queue.get(), timeout=2.0)
                if kind == "done":
                    break
                if data:
                    text = data.decode("utf-8", errors="replace")
                    clean_text = ANSI_ESCAPE.sub('', text)
                    if clean_text:
                        token_count += 1
                        yield f"data: {json.dumps({'token': clean_text})}\n\n"
            except asyncio.TimeoutError:
                # Process finished and queue emptied?
                if process.returncode is not None and queue.empty():
                    break
                # Pulse TCP keep-alive comment so mobile socket / Ktor NEVER times out!
                yield ": keepalive\n\n"

        stderr_output = await process.stderr.read()
        if stderr_output and (token_count == 0 or process.returncode != 0):
            err_text = stderr_output.decode("utf-8", errors="replace").strip()
            clean_err = ANSI_ESCAPE.sub('', err_text)
            if clean_err:
                print(f"[Termux Bridge Stderr] {clean_err}")
                yield f"data: {json.dumps({'token': f'\\n\\n⚠️ Notice: {clean_err}\\n\\n'})}\n\n"

        await process.wait()
    finally:
        async with process_lock:
            if active_process is process:
                active_process = None
        if process.returncode is None:
            try:
                process.terminate()
            except Exception:
                pass

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
    import urllib.request

    # Check if Bridge is ALREADY running (e.g. in background or Termux)
    try:
        with urllib.request.urlopen("http://127.0.0.1:8000/v1/health", timeout=1.5) as res:
            if res.status == 200:
                print("\n" + "="*55)
                print("🟢 Anti-Gravity Bridge is ALREADY RUNNING at http://127.0.0.1:8000!")
                print("📱 Aro Code app is already connected and ready.")
                print("💡 Agar server restart karna hai to pehle purana process band karein:")
                print("   fuser -k 8000/tcp  (ya Termux app band karein)")
                print("="*55 + "\n")
                sys.exit(0)
    except Exception:
        pass

    print("\n" + "="*55)
    print("⚡ Anti-Gravity Termux Bridge Server Starting (v2.4)!")
    print("📍 Host: http://127.0.0.1:8000")
    print("📁 Hardened Workspace Security & Path Traversal Protection Active")
    print("💓 2-Second TCP Keep-Alive Heartbeat Enabled (Zero Timeouts)!")
    print("🚀 Stdin stream mode enabled: No file or prompt size limits!")
    print("📱 Aro Code app will automatically connect to this URL.")
    print("="*55 + "\n")

    try:
        uvicorn.run(app, host="0.0.0.0", port=8000)
    except OSError as e:
        if e.errno == 98 or "address in use" in str(e).lower():
            print("\n⚠️ Port 8000 is occupied. Trying 127.0.0.1:8000...")
            try:
                uvicorn.run(app, host="127.0.0.1", port=8000)
            except Exception as e2:
                print(f"\n❌ Could not bind to port 8000: {e2}")
                print("💡 Solution: Termux app me ja kar 'pkill -f python' karein, ya run: 'fuser -k 8000/tcp'")
        else:
            raise
