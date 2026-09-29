from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UP = ROOT / "upstream"
ANDROID = UP / "android" / "app" / "src" / "main"

src_dir = ROOT / "overlay" / "android" / "app" / "src" / "main" / "java" / "com" / "metallic" / "chiaki" / "leo"
dst_dir = ANDROID / "java" / "com" / "metallic" / "chiaki" / "leo"
dst_dir.mkdir(parents=True, exist_ok=True)
shutil.copytree(src_dir, dst_dir, dirs_exist_ok=True)

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

queries = manifest.find("queries")
if queries is None:
    queries = ET.Element("queries")
    app_index = list(manifest).index(app)
    manifest.insert(app_index, queries)
if not any(p.get(android + "name") == "com.universal.remote.multi" for p in queries.findall("package")):
    pkg = ET.SubElement(queries, "package")
    pkg.set(android + "name", "com.universal.remote.multi")

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

leo_register = ET.Element("activity")
leo_register.set(android + "name", ".leo.LeoRegisterActivity")
leo_register.set(android + "exported", "false")
leo_register.set(android + "label", "Collega PS5")
app.insert(1, leo_register)

leo_controller = ET.Element("activity")
leo_controller.set(android + "name", ".leo.LeoPs5ControllerActivity")
leo_controller.set(android + "exported", "false")
leo_controller.set(android + "screenOrientation", "sensorLandscape")
leo_controller.set(android + "configChanges", "keyboard|keyboardHidden|orientation|screenSize")
leo_controller.set(android + "theme", "@style/StreamTheme")
leo_controller.set(android + "label", "LEO PS5")
app.insert(2, leo_controller)

leo_remote = ET.Element("activity")
leo_remote.set(android + "name", ".leo.LeoRemoteActivity")
leo_remote.set(android + "exported", "false")
leo_remote.set(android + "label", "LEO Remote")
app.insert(3, leo_remote)

tree.write(manifest_path, encoding="utf-8", xml_declaration=True)

gradle_path = UP / "android" / "app" / "build.gradle"
gradle = gradle_path.read_text(encoding="utf-8")
gradle = gradle.replace("versionCode 12", "versionCode 122", 1)
gradle = gradle.replace("versionName chiakiVersion", 'versionName "0.12.2"', 1)
deps = [
    'implementation "com.squareup.okhttp3:okhttp:4.12.0"',
    'implementation "dev.mobile:dadb:2.0.0"',
    'implementation "org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5"',
]
for dep in reversed(deps):
    if dep not in gradle:
        gradle = gradle.replace('dependencies {', 'dependencies {\n    ' + dep, 1)
gradle_path.write_text(gradle, encoding="utf-8")

stream_path = ANDROID / "java" / "com" / "metallic" / "chiaki" / "stream" / "StreamActivity.kt"
s = stream_path.read_text(encoding="utf-8")
s = s.replace("import android.app.AlertDialog\n", "import android.app.AlertDialog\nimport android.content.pm.ActivityInfo\nimport android.content.res.ColorStateList\nimport android.graphics.Color\n")
s = s.replace("import android.widget.EditText\n", "import android.widget.EditText\nimport android.widget.FrameLayout\nimport android.widget.ArrayAdapter\nimport android.widget.Button\nimport android.widget.LinearLayout\nimport android.widget.Spinner\nimport android.widget.TextView\n")
s = s.replace('const val EXTRA_CONNECT_INFO = "connect_info"\n', 'const val EXTRA_CONNECT_INFO = "connect_info"\n\t\tconst val EXTRA_CONTROLLER_ONLY = "leo_controller_only"\n')
s = s.replace("private lateinit var insetsController: WindowInsetsControllerCompat\n", "private lateinit var insetsController: WindowInsetsControllerCompat\n\tprivate var controllerOnly = false\n\tprivate var controllerCover: android.view.View? = null\n\tprivate var leoInputIndicator: TextView? = null\n")
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
controls_old = """\tprivate var controlsJob: Job? = null

\toverride fun onAttachFragment(fragment: Fragment)
\t{
\t\tsuper.onAttachFragment(fragment)
\t\tif(fragment is TouchControlsFragment)
\t\t{
\t\t\tcontrolsJob?.cancel()
\t\t\tcontrolsJob = fragment.controllerState
\t\t\t\t.onEach { viewModel.input.touchControllerState = it }
\t\t\t\t.launchIn(lifecycleScope)
\t\t\tfragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
\t\t\tif(fragment is TouchpadOnlyFragment)
\t\t\t\tfragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
\t\t}
\t}
"""

controls_new = """\tprivate val controlsJobs = mutableMapOf<String, Job>()

\toverride fun onAttachFragment(fragment: Fragment)
\t{
\t\tsuper.onAttachFragment(fragment)
\t\tif(fragment is TouchControlsFragment)
\t\t{
\t\t\tval key = fragment.javaClass.name
\t\t\tcontrolsJobs.remove(key)?.cancel()
\t\t\tcontrolsJobs[key] = fragment.controllerState
\t\t\t\t.onEach { viewModel.input.touchControllerState = it }
\t\t\t\t.launchIn(lifecycleScope)
\t\t\tfragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
\t\t\tif(fragment is TouchpadOnlyFragment)
\t\t\t\tfragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
\t\t}
\t}
"""

if controls_old not in s:
    raise RuntimeError("touch controls listener block not found")
s = s.replace(controls_old, controls_new, 1)
s = s.replace(
    """\t\tcontrolsJob?.cancel()
\t}
""",
    """\t\tcontrolsJobs.values.forEach { it.cancel() }
\t\tcontrolsJobs.clear()
\t}
""",
    1
)

stream_path.write_text(s, encoding="utf-8")

print("LEO overlay applied")
