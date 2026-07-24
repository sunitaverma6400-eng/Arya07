"""
android_start.py
-----------------
Yeh module Android app ka Python entrypoint hai. JarvisService.kt is module
ko Chaquopy se import karke `start(context)` call karta hai.

`server.py` ko normal import karne se uske top-level setup (Flask routes
register, keepalive/memory_guard/scheduler start) already chal jaata hai —
uska `if __name__ == "__main__":` block yahan nahi chalega (kyunki import
ke waqt naam "server" hota hai, "__main__" nahi), isliye humein khud
`app.run()` ek background thread mein explicitly call karna padta hai.
"""

import threading

_started = False
_thread = None


def start(context, port: int = 5000):
    global _started, _thread
    if _started:
        return "already_running"

    import native_android
    native_android.set_context(context)

    import server  # module-level setup (routes, scheduler, keepalive) yahin chal jaata hai

    def _run():
        server.app.run(host="127.0.0.1", port=port, threaded=True,
                        use_reloader=False, debug=False)

    _thread = threading.Thread(target=_run, name="jarvis-flask", daemon=True)
    _thread.start()
    _started = True
    return "started"


def is_running():
    return _started
