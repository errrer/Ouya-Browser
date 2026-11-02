#!/usr/bin/env python3
"""Remote Chromium renderer for OUYA Browser.

The OUYA client receives one JPEG frame at a time and sends simple input events.
Keep this service on a trusted LAN or place it behind an authenticated HTTPS tunnel.
"""

import asyncio
import hmac
import os
import re
from urllib.parse import urlparse

from aiohttp import web
from playwright.async_api import async_playwright

TOKEN = os.environ.get("OUYA_TOKEN", "").strip()
HOST = os.environ.get("OUYA_HOST", "0.0.0.0")
PORT = int(os.environ.get("OUYA_PORT", "8765"))
WIDTH = int(os.environ.get("BROWSER_WIDTH", "1280"))
HEIGHT = int(os.environ.get("BROWSER_HEIGHT", "720"))
JPEG_QUALITY = max(30, min(85, int(os.environ.get("JPEG_QUALITY", "55"))))

if len(TOKEN) < 8:
    raise RuntimeError("Set OUYA_TOKEN to a unique password of at least 8 characters before starting.")


@web.middleware
async def require_token(request, handler):
    supplied = request.headers.get("Authorization", "")
    expected = "Bearer " + TOKEN
    if not hmac.compare_digest(supplied, expected):
        return web.json_response({"error": "unauthorized"}, status=401)
    return await handler(request)


async def browser_start(app):
    app["playwright"] = await async_playwright().start()
    app["context"] = await app["playwright"].chromium.launch_persistent_context(
        user_data_dir=os.environ.get("BROWSER_PROFILE", "/app/profile"),
        headless=True,
        viewport={"width": WIDTH, "height": HEIGHT},
        device_scale_factor=1,
        accept_downloads=False,
        ignore_https_errors=False,
        args=[
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-background-networking",
            "--disable-extensions",
            "--disable-sync",
            "--no-first-run",
            "--no-default-browser-check",
        ],
    )
    app["page"] = app["context"].pages[0] if app["context"].pages else await app["context"].new_page()
    app["last_navigation_error"] = None
    try:
        await app["page"].goto("https://github.com", wait_until="domcontentloaded", timeout=45000)
    except Exception as exc:  # Keep the service alive even if GitHub is slow/unavailable.
        app["last_navigation_error"] = str(exc)[:240]
    print("OUYA Browser remote engine ready on %s:%s (%sx%s)" % (HOST, PORT, WIDTH, HEIGHT), flush=True)


async def browser_stop(app):
    context = app.get("context")
    if context:
        await context.close()
    playwright = app.get("playwright")
    if playwright:
        await playwright.stop()


def valid_web_url(value):
    if not isinstance(value, str) or len(value) > 4096:
        return False
    parsed = urlparse(value.strip())
    return parsed.scheme in ("http", "https") and bool(parsed.netloc)


async def health(request):
    page = request.app.get("page")
    return web.json_response({
        "ok": page is not None,
        "engine": "Chromium via Playwright",
        "viewport": {"width": WIDTH, "height": HEIGHT},
        "url": page.url if page else "",
    })


async def frame(request):
    page = request.app["page"]
    try:
        jpeg = await page.screenshot(type="jpeg", quality=JPEG_QUALITY, full_page=False, animations="disabled")
    except Exception as exc:
        return web.json_response({"error": "frame unavailable", "detail": str(exc)[:180]}, status=503)
    return web.Response(
        body=jpeg,
        content_type="image/jpeg",
        headers={
            "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma": "no-cache",
            "X-Content-Type-Options": "nosniff",
        },
    )


async def navigate(request):
    try:
        data = await request.json()
    except Exception:
        return web.json_response({"error": "JSON body required"}, status=400)
    target = (data.get("url") or "").strip()
    if not valid_web_url(target):
        return web.json_response({"error": "Only http:// and https:// URLs are allowed"}, status=400)
    try:
        response = await request.app["page"].goto(target, wait_until="commit", timeout=20000)
        request.app["last_navigation_error"] = None
        return web.json_response({"ok": True, "url": request.app["page"].url,
                                  "status": response.status if response else None})
    except Exception as exc:
        request.app["last_navigation_error"] = str(exc)[:240]
        # Many sites continue rendering after a timeout; report the resulting page URL.
        return web.json_response({"ok": False, "url": request.app["page"].url,
                                  "error": str(exc)[:240]}, status=200)


async def history(request):
    try:
        data = await request.json()
    except Exception:
        return web.json_response({"error": "JSON body required"}, status=400)
    page = request.app["page"]
    action = data.get("action")
    try:
        if action == "back":
            await page.go_back(wait_until="commit", timeout=15000)
        elif action == "forward":
            await page.go_forward(wait_until="commit", timeout=15000)
        elif action == "reload":
            await page.reload(wait_until="commit", timeout=20000)
        else:
            return web.json_response({"error": "Unknown history action"}, status=400)
        return web.json_response({"ok": True, "url": page.url})
    except Exception as exc:
        return web.json_response({"ok": False, "url": page.url, "error": str(exc)[:180]}, status=200)


async def input_event(request):
    try:
        data = await request.json()
    except Exception:
        return web.json_response({"error": "JSON body required"}, status=400)
    page = request.app["page"]
    kind = data.get("type")
    try:
        if kind == "move":
            x = max(0, min(WIDTH, float(data.get("x", WIDTH / 2))))
            y = max(0, min(HEIGHT, float(data.get("y", HEIGHT / 2))))
            await page.mouse.move(x, y)
        elif kind == "click":
            x = max(0, min(WIDTH, float(data.get("x", WIDTH / 2))))
            y = max(0, min(HEIGHT, float(data.get("y", HEIGHT / 2))))
            await page.mouse.click(x, y)
        elif kind == "wheel":
            delta = max(-1600, min(1600, float(data.get("deltaY", 0))))
            await page.mouse.wheel(0, delta)
        elif kind == "key":
            key = data.get("key", "")
            allowed = {"Enter", "Escape", "Tab", "Backspace", "Delete", "ArrowUp", "ArrowDown",
                       "ArrowLeft", "ArrowRight", "Home", "End", "PageUp", "PageDown", " ",
                       "F5", "Control+A", "Control+C", "Control+V"}
            if key not in allowed:
                return web.json_response({"error": "Key not allowed"}, status=400)
            await page.keyboard.press(key)
        elif kind == "text":
            text = data.get("text", "")
            if not isinstance(text, str) or len(text) > 200:
                return web.json_response({"error": "Text too long"}, status=400)
            await page.keyboard.insert_text(text)
        else:
            return web.json_response({"error": "Unknown input type"}, status=400)
        return web.json_response({"ok": True})
    except Exception as exc:
        return web.json_response({"error": str(exc)[:180]}, status=500)


def create_app():
    app = web.Application(middlewares=[require_token], client_max_size=16 * 1024)
    app.on_startup.append(browser_start)
    app.on_cleanup.append(browser_stop)
    app.router.add_get("/health", health)
    app.router.add_get("/frame.jpg", frame)
    app.router.add_post("/navigate", navigate)
    app.router.add_post("/history", history)
    app.router.add_post("/input", input_event)
    return app


if __name__ == "__main__":
    web.run_app(create_app(), host=HOST, port=PORT, access_log=None)
