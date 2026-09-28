from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UP = ROOT / "upstream"
ANDROID = UP / "android" / "app" / "src" / "main"

src = ROOT / "overlay" / "android" / "app" / "src" / "main" / "java" / "com" / "metallic" / "chiaki" / "leo" / "LeoMainActivity.kt"
dst = ANDROID / "java" / "com" / "metallic" / "chiaki" / "leo" / "LeoMainActivity.kt"
dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(src, dst)

manifest_path = ANDROID / "AndroidManifest.xml"
ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
android = "{http://schemas.android.com/apk/res/android}"
tree = ET.parse(manifest_path)
manifest = tree.getroot()
app = manifest.find("application")
if app is None:
    raise RuntimeError("application node missing")
app.set(android + "label", "LEO Control")
app.set(android + "usesCleartextTraffic", "true")

for activity in list(app.findall("activity")):
    if activity.get(android + "name") == ".main.MainActivity":
        for f in list(activity.findall("intent-filter")):
            activity.remove(f)

leo = ET.Element("activity")
leo.set(android + "name", ".leo.LeoMainActivity")
leo.set(android + "exported", "true")
leo.set(android + "label", "LEO Control")
intent = ET.SubElement(leo, "intent-filter")
action = ET.SubElement(intent, "action")
action.set(android + "name", "android.intent.action.MAIN")
category = ET.SubElement(intent, "category")
category.set(android + "name", "android.intent.category.LAUNCHER")
app.insert(0, leo)
tree.write(manifest_path, encoding="utf-8", xml_declaration=True)

stream_path = ANDROID / "java" / "com" / "metallic" / "chiaki" / "stream" / "StreamActivity.kt"
s = stream_path.read_text(encoding="utf-8")
s = s.replace("import android.app.AlertDialog\n", "import android.app.AlertDialog\nimport android.content.pm.ActivityInfo\nimport android.graphics.Color\n")
s = s.replace("import android.widget.EditText\n", "import android.widget.EditText\nimport android.widget.FrameLayout\n")
s = s.replace('const val EXTRA_CONNECT_INFO = "connect_info"\n', 'const val EXTRA_CONNECT_INFO = "connect_info"\n\t\tconst val EXTRA_CONTROLLER_ONLY = "leo_controller_only"\n')
s = s.replace("private lateinit var insetsController: WindowInsetsControllerCompat\n", "private lateinit var insetsController: WindowInsetsControllerCompat\n\tprivate var controllerOnly = false\n")
needle = "\t\tbinding = ActivityStreamBinding.inflate(layoutInflater)\n\t\tsetContentView(binding.root)\n"
replacement = needle + "\n\t\tcontrollerOnly = intent.getBooleanExtra(EXTRA_CONTROLLER_ONLY, false)\n\t\tif(controllerOnly)\n\t\t{\n\t\t\trequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE\n\t\t\tviewModel.setOnScreenControlsEnabled(true)\n\t\t\tviewModel.setTouchpadOnlyEnabled(false)\n\t\t\tval cover = android.view.View(this).apply { setBackgroundColor(Color.rgb(11, 13, 16)) }\n\t\t\tbinding.mainStreamLayout.addView(cover, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))\n\t\t\tbinding.overlay.visibility = android.view.View.GONE\n\t\t}\n"
if needle not in s:
    raise RuntimeError("StreamActivity insertion point not found")
s = s.replace(needle, replacement, 1)
needle2 = "\tprivate fun showOverlay()\n\t{\n"
replacement2 = "\tprivate fun showOverlay()\n\t{\n\t\tif(controllerOnly)\n\t\t{\n\t\t\tbinding.overlay.isGone = true\n\t\t\treturn\n\t\t}\n"
if needle2 not in s:
    raise RuntimeError("showOverlay insertion point not found")
s = s.replace(needle2, replacement2, 1)
stream_path.write_text(s, encoding="utf-8")

print("LEO overlay applied")
