"""
native_android.py
------------------
Chaquopy ke through seedha Android system APIs call karta hai — SMS, calls,
battery, vibrate, torch, notification, location. Yeh Termux:API ya
phone_bridge.py polling ki jagah leta hai, kyunki ab Jarvis khud native
Android app ke andar (isi process mein) chal raha hai.

MainActivity/JarvisService start hote hi `set_context()` call karke
Android Context yahan pass kar dete hain. Uske baad tools.py ke
phone-control functions seedha isi module se kaam karte hain.

Agar yeh module Android ke bahar (normal desktop/CI Python) import ho
jaaye, to `available()` False rahega aur caller apna purana Termux
fallback ya error message use karega — kuch crash nahi hota.
"""

try:
    from java import jclass
    _JAVA_OK = True
except Exception:
    _JAVA_OK = False

_context = None


def set_context(ctx):
    global _context
    _context = ctx


def available():
    return _JAVA_OK and _context is not None


def _svc(name_const):
    Context = jclass("android.content.Context")
    return _context.getSystemService(getattr(Context, name_const))


def send_sms(phone_number: str, message: str):
    if not available():
        return None
    try:
        SmsManager = jclass("android.telephony.SmsManager")
        sms = SmsManager.getDefault()
        parts = sms.divideMessage(message)
        sms.sendMultipartTextMessage(phone_number, None, parts, None, None)
        return f"{phone_number} ko SMS bhej diya."
    except Exception as e:
        return f"SMS error: {e}"


def make_call(phone_number: str):
    if not available():
        return None
    try:
        Intent = jclass("android.content.Intent")
        Uri = jclass("android.net.Uri")
        intent = Intent("android.intent.action.CALL", Uri.parse(f"tel:{phone_number}"))
        intent.addFlags(0x10000000)  # FLAG_ACTIVITY_NEW_TASK
        _context.startActivity(intent)
        return f"{phone_number} par call laga raha hoon."
    except Exception as e:
        return f"Call error: {e}"


def get_battery_status():
    if not available():
        return None
    try:
        IntentFilter = jclass("android.content.IntentFilter")
        Intent = jclass("android.content.Intent")
        filt = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        battery_intent = _context.registerReceiver(None, filt)
        level = battery_intent.getIntExtra("level", -1)
        scale = battery_intent.getIntExtra("scale", -1)
        status = battery_intent.getIntExtra("status", -1)
        pct = int(level * 100 / scale) if scale > 0 else -1
        charging = status in (2, 5)  # CHARGING, FULL
        return f"Battery {pct}% — {'Charging' if charging else 'Not charging'}."
    except Exception as e:
        return f"Battery error: {e}"


def send_notification(title: str, content: str):
    if not available():
        return None
    try:
        NotifBuilder = jclass("androidx.core.app.NotificationCompat$Builder")
        nm = _svc("NOTIFICATION_SERVICE")
        builder = NotifBuilder(_context, "jarvis_alerts")
        builder.setContentTitle(title)
        builder.setContentText(content)
        builder.setSmallIcon(_context.getApplicationInfo().icon)
        builder.setAutoCancel(True)
        import time
        nm.notify(int(time.time()) % 100000, builder.build())
        return f"Notification bhej di: '{title}'."
    except Exception as e:
        return f"Notification error: {e}"


def vibrate(duration_ms: int = 500):
    if not available():
        return None
    try:
        VibrationEffect = jclass("android.os.VibrationEffect")
        v = _svc("VIBRATOR_SERVICE")
        effect = VibrationEffect.createOneShot(duration_ms, VibrationEffect.DEFAULT_AMPLITUDE)
        v.vibrate(effect)
        return f"Phone vibrate kiya {duration_ms}ms."
    except Exception as e:
        return f"Vibrate error: {e}"


def toggle_torch(on: bool = True):
    if not available():
        return None
    try:
        cm = _svc("CAMERA_SERVICE")
        cam_id = cm.getCameraIdList()[0]
        cm.setTorchMode(cam_id, bool(on))
        return f"Torch {'on' if on else 'off'} kar di."
    except Exception as e:
        return f"Torch error: {e}"


def get_location():
    if not available():
        return None
    try:
        lm = _svc("LOCATION_SERVICE")
        loc = None
        for provider in ("gps", "network", "passive"):
            try:
                loc = lm.getLastKnownLocation(provider)
                if loc:
                    break
            except Exception:
                continue
        if not loc:
            return "Location abhi available nahi hai (GPS lock nahi mila, dobara try karo)."
        lat, lon = loc.getLatitude(), loc.getLongitude()
        return (f"📍 Coordinates: {lat:.5f}, {lon:.5f}\n"
                f"🗺️ Maps: https://maps.google.com/?q={lat},{lon}")
    except Exception as e:
        return f"Location error: {e}"
