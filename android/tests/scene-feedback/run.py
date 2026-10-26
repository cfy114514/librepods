#!/usr/bin/env python3
"""Compile real scene/AACP sources using Java and a cached Kotlin compiler; no downloads."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


PRODUCTION_SOURCES = (
    "bluetooth/AACPManager.kt",
    "bluetooth/BoundPacketWriter.kt",
    "bluetooth/AacpPacketValidation.kt",
    "bluetooth/HeadTrackingMotion.kt",
    "bluetooth/RtBuddyHeadTracking.kt",
    "utils/OwnedCallbackSlot.kt",
    "utils/InteractiveCommandQueue.kt",
    "utils/KeyedWorkSession.kt",
    "data/CustomEq.kt",
    "data/AirPods.kt",
    "data/AirPodsName.kt",
    "data/BatteryHistory.kt",
    "data/Packets.kt",
    "data/CachedAirPodsInformation.kt",
    "data/ListeningModePolicy.kt",
    "data/ScenePresets.kt",
    "data/ScenePresetController.kt",
)
SCENE_VIEWMODEL_SOURCE = "presentation/viewmodel/AirPodsViewModel.kt"


def extracted_manual_scene_adapter(snapshot):
    """Compile exact production method excerpts; fail closed when their boundaries change."""
    text = snapshot[SCENE_VIEWMODEL_SOURCE].decode()

    def between(start, end):
        if text.count(start) != 1 or text.count(end) != 1:
            raise ValueError(f"ViewModel scene extraction boundary changed between {start.strip()!r} and {end.strip()!r}; review the harness adapter")
        begin = text.index(start)
        finish = text.index(end)
        if finish <= begin:
            raise ValueError("ViewModel scene extraction boundaries are out of order")
        return text[begin:finish]

    # Preserve the actual manual-edit ordering, mapper and context publication. The remainder
    # of setControlCommandValue sends a separate manual command and is outside this test.
    manual_method = between("    fun setControlCommandValue(\n", "    private fun enqueueSetting(key: Int, packet: ByteArray) {\n")
    prefix_end = "        val ownedValue = value.copyOf()\n"
    if manual_method.count(prefix_end) != 1:
        raise ValueError("ViewModel manual prefix boundary changed; review the harness adapter")
    prefix = manual_method[:manual_method.index(prefix_end)]
    mapper = between("    private fun sceneSetting(identifier: ControlCommandIdentifiers): SceneSetting? = when (identifier) {\n",
                     "    private fun sceneIdentifier(setting: SceneSetting): ControlCommandIdentifiers = when (setting) {\n")
    synchronize = between("    private fun synchronizeSceneContext() {\n", "    private fun observeSceneReports() {\n")
    excerpts = prefix + "    }\n\n" + mapper + synchronize
    wrapper = """package me.kavishdevar.librepods.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.ControlCommandIdentifiers
import me.kavishdevar.librepods.data.SceneContext
import me.kavishdevar.librepods.data.ScenePresetController
import me.kavishdevar.librepods.data.SceneSetting

/** Only the current-context provider and minimal UI state are supplied by the fixture. */
internal class ExtractedManualSceneAdapter(
    private val sceneController: ScenePresetController,
    private val currentSceneContext: () -> SceneContext?
) {
    private data class UiState(val canApplyScenePresets: Boolean = false)
    private val _uiState = MutableStateFlow(UiState())
    val canApplyScenePresets: Boolean get() = _uiState.value.canApplyScenePresets

"""
    return (wrapper + excerpts + "}\n").encode(), excerpts.encode()


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[1])
    parser.add_argument("--compiler-lib", type=Path, help="Cached Gradle distribution lib directory")
    parser.add_argument("--case", help="Run only the named integration case")
    parser.add_argument("--negative-controls", action="store_true",
                        help="Also prove four isolated deliberately broken source snapshots fail targeted cases")
    options = parser.parse_args()
    compiler_lib = options.compiler_lib
    if compiler_lib is None:
        roots = [Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")), Path("/workspace/gradle-cache")]
        compilers = sorted({jar for root in roots for jar in root.glob("wrapper/dists/gradle-*-bin/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar")})
        if not compilers:
            parser.error("No cached Kotlin compiler found; set GRADLE_USER_HOME or --compiler-lib. This script downloads nothing.")
        compiler_lib = compilers[-1].parent
    if not list(compiler_lib.glob("kotlin-compiler-embeddable-*.jar")):
        parser.error(f"No Kotlin compiler jar in {compiler_lib}")
    java = shutil.which("java")
    if not java:
        parser.error("java is required")
    android_root = options.android_root.resolve()
    source = android_root / "app/src/main/java/me/kavishdevar/librepods"
    source_bytes = {}
    for relative in (*PRODUCTION_SOURCES, SCENE_VIEWMODEL_SOURCE):
        path = source / relative
        if not path.is_file():
            parser.error(f"Missing source: {path}")
        source_bytes[relative] = path.read_bytes()
    harness_bytes = {path.relative_to(here): path.read_bytes()
                     for path in [*sorted((here / "stubs").glob("*.kt")), *sorted(here.glob("*Integration.kt"))]}
    revision = subprocess.run(["git", "-C", str(android_root), "rev-parse", "HEAD"],
                              text=True, capture_output=True)
    dirty = subprocess.run(["git", "-C", str(android_root), "status", "--porcelain", "--", "."],
                           text=True, capture_output=True)
    print(f"Production Android root: {android_root}", flush=True)
    print(f"Production HEAD: {revision.stdout.strip() or 'unknown'} ({'working changes present' if dirty.stdout else 'clean'})", flush=True)
    for relative, data in source_bytes.items():
        print(f"Source SHA256 {hashlib.sha256(data).hexdigest()} {relative}", flush=True)
    try:
        _, excerpts = extracted_manual_scene_adapter(source_bytes)
    except ValueError as error:
        parser.error(str(error))
    print(f"Extracted SHA256 {hashlib.sha256(excerpts).hexdigest()} ViewModel manual prefix, mapper and synchronizer", flush=True)
    coroutines = list(compiler_lib.glob("kotlinx-coroutines-core-jvm-*.jar"))
    if not coroutines:
        parser.error(f"No cached kotlinx-coroutines-core-jvm jar in {compiler_lib}")
    library_jars = [*compiler_lib.glob("kotlin-stdlib-*.jar"), *compiler_lib.glob("annotations-*.jar"), *coroutines]
    classpath = os.pathsep.join(map(str, library_jars))
    def compile_and_run(snapshot, build, case=None, capture=False):
        # Compile immutable copies, including when another worktree is still being edited.
        sources = []
        for relative, data in snapshot.items():
            target = build / "production" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            if relative != SCENE_VIEWMODEL_SOURCE:
                sources.append(target)
        adapter, _ = extracted_manual_scene_adapter(snapshot)
        adapter_source = build / "ExtractedManualSceneAdapter.kt"
        adapter_source.write_bytes(adapter)
        sources.append(adapter_source)
        # Resource IDs are the only Android dependency of the real model/capability table.
        resources = sorted(set(re.findall(r"R\.drawable\.(\w+)", snapshot["data/AirPods.kt"].decode())))
        resource_stub = build / "R.kt"
        resource_stub.write_text("package me.kavishdevar.librepods\nobject R { object drawable {\n" +
                                 "\n".join(f"const val {name} = {index}" for index, name in enumerate(resources, 1)) +
                                 "\n} }\n")
        sources.append(resource_stub)
        for relative, data in harness_bytes.items():
            target = build / "harness" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            sources.append(target)
        classes = build / "classes"
        subprocess.run([java, "-cp", str(compiler_lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", classpath,
                        "-d", str(classes), *map(str, sources)], check=True)
        command = [java, "-cp", os.pathsep.join([str(classes), classpath]),
                   "me.kavishdevar.librepods.bluetooth.AacpFeedbackIntegrationKt"]
        if case:
            command.append(case)
        return subprocess.run(command, text=True, stdout=subprocess.PIPE if capture else None,
                              stderr=subprocess.STDOUT if capture else None)

    with tempfile.TemporaryDirectory(prefix="librepods-scene-feedback-jvm-") as temporary:
        build = Path(temporary)
        result = compile_and_run(source_bytes, build / "baseline", options.case)
        if result.returncode or not options.negative_controls:
            raise SystemExit(result.returncode)
        mutations = (
            ("optimistic_write_published_as_receive", "bluetooth/AACPManager.kt",
             "setControlCommandStatusValue(ControlCommandIdentifiers.fromByte(controlCommand.identifier)!!, controlCommand.value, current)",
             "setControlCommandStatusValue(ControlCommandIdentifiers.fromByte(controlCommand.identifier)!!, controlCommand.value, current)\n"
             "                parseAndStoreControlCommand(packet, current)",
             "outgoing_cache_and_listeners_never_become_reports", None),
            ("confirmation_before_write_success", "data/ScenePresetController.kt",
             "if (entry.written != true || entry.reported != entry.command.target.value) return",
             "if (entry.reported != entry.command.target.value) return",
             "matching_reply_during_failed_flush_cannot_apply", None),
            ("manual_edit_rewrites_previous_context", "data/ScenePresetController.kt",
             "if (entry.command.context != context) return",
             "// Removed context ownership guard for the isolated negative control.",
             "manual_changes_on_replacement_preserve_earlier_results", None),
            ("manual_edit_skips_current_context_refresh", SCENE_VIEWMODEL_SOURCE,
             "sceneSetting(identifier)?.let {\n            synchronizeSceneContext()\n            sceneController.supersede(it)\n        }",
             "sceneSetting(identifier)?.let { sceneController.supersede(it) }",
             "manual_prefix_refreshes_context_before_history_supersession",
             "Actual manual prefix must refresh same-MAC session context before superseding historical observed result"),
        )
        for name, relative, original, replacement, case, required_assertion in mutations:
            text = source_bytes[relative].decode()
            if text.count(original) != 1:
                parser.error(f"Negative-control anchor changed in {relative}; review mutation {name}. No production files were edited.")
            snapshot = dict(source_bytes)
            snapshot[relative] = text.replace(original, replacement, 1).encode()
            print(f"Checking isolated negative control: {name}", flush=True)
            result = compile_and_run(snapshot, build / name, case, capture=True)
            failures = [line for line in result.stdout.splitlines() if line.startswith(f"FAIL {case}:")
                        and (required_assertion is None or required_assertion in line)]
            if result.returncode == 0 or not failures:
                print(result.stdout, flush=True)
                raise SystemExit(f"Negative control did not fail its intended assertion: {name}")
            print(f"PASS negative_control_{name}: {failures[0]}", flush=True)


if __name__ == "__main__":
    main()
