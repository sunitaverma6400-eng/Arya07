"""
android_start.py
-----------------
Android app ka Python entrypoint. Agar server import/start fail ho,
to poora traceback hi 127.0.0.1:5000 par dikha dete hain (debug ke liye).
"""

import threading
import traceback

_started = False
_thread = None


def start(context, port: int = 5000):
    global _started, _thread
    if _started:
        return "already_running"

    import native_android
    native_android.set_context(context)

    try:
        import server as _server_module
        app = _server_module.app
    except Exception:
        tb = traceback.format_exc()
        from flask import Flask
        app = Flask(__name__)

        @app.route("/", defaults={"path": ""})
        @app.route("/<path:path>")
        def _err(path):
            return "<pre style='white-space:pre-wrap;font-size:14px'>" + tb + "</pre>", 500

    def _run():
        app.run(host="127.0.0.1", port=port, threaded=True,
                 use_reloader=False, debug=False)

    _thread = threading.Thread(target=_run, name="jarvis-flask", daemon=True)
    _thread.start()
    _started = True
    return "started"


def is_running():
    return _started
