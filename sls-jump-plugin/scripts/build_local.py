#!/usr/bin/env python3
"""Build against an installed IDEA 2026.2 using its JBR 25, without class rewriting."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("idea", nargs="?", default="/Applications/IntelliJ IDEA.app",
                    help="IDEA installation directory (.app on macOS)")
args = parser.parse_args()
idea = Path(args.idea).resolve()
if (idea / "Contents").is_dir():
    idea = idea / "Contents"
build_file = next((p for p in [idea / "Resources/build.txt", idea / "build.txt"] if p.exists()), None)
if not build_file:
    raise SystemExit("Cannot locate IDEA build.txt; pass the IDEA installation directory.")
build_id = build_file.read_text().strip().split("-", 1)[-1]
parts = tuple(int(p) for p in build_id.split("."))
if parts[0] != 262 or parts < (262, 9437, 185):
    raise SystemExit("This draft requires IDEA build 262.9437.185 or later within 262.x.")
jbr = next((p for p in [idea / "jbr/Contents/Home", idea / "jbr"] if (p / "bin/javac").exists()), None)
if jbr is None:
    raise SystemExit("IDEA's JBR with javac is required.")
java, javac = jbr / "bin/java", jbr / "bin/javac"
version = subprocess.run([str(javac), "-version"], capture_output=True, text=True, check=True)
if not re.search(r"javac 25(?:\.|\s|$)", version.stdout + version.stderr):
    raise SystemExit("JBR 25 is required; bytecode version rewriting is not used.")

def run(command, capture=False):
    return subprocess.run([str(x) for x in command], cwd=ROOT, check=True,
                          capture_output=capture, text=True)

deps = BUILD / "deps"
deps.mkdir(parents=True, exist_ok=True)
if not (deps / "aliyun-log-0.6.166.jar").exists() or not (deps / "jackson-databind-2.17.2.jar").exists():
    run(["mvn", "-q", "-f", "dependencies.pom.xml", "dependency:copy-dependencies",
         "-DincludeScope=runtime", "-DoutputDirectory=" + str(deps)])
junit_dir = BUILD / "test-deps"
junit_dir.mkdir(parents=True, exist_ok=True)
junit = junit_dir / "junit-platform-console-standalone-1.10.2.jar"
if not junit.exists():
    run(["mvn", "-q", "-f", "dependencies.pom.xml", "dependency:copy",
         "-Dartifact=org.junit.platform:junit-platform-console-standalone:1.10.2",
         "-DoutputDirectory=" + str(junit_dir)])

classes, tests = BUILD / "classes", BUILD / "test-classes"
for directory in [classes, tests]:
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
platform = sorted((idea / "lib").glob("*.jar")) + sorted((idea / "plugins/java/lib").glob("*.jar"))
runtime = sorted(deps.glob("*.jar"))
classpath = os.pathsep.join(str(p) for p in platform + runtime)
run([javac, "--release", "25", "-encoding", "UTF-8", "-cp", classpath, "-d", classes,
     *sorted((ROOT / "src/main/java").rglob("*.java"))])
run([javac, "--release", "25", "-encoding", "UTF-8", "-cp",
     os.pathsep.join([str(classes), classpath, str(junit)]), "-d", tests,
     *sorted((ROOT / "src/test/java").rglob("*.java"))])
result = run([java, "-cp", os.pathsep.join([str(junit), str(classes), str(tests), classpath]),
              "org.junit.platform.console.ConsoleLauncher", "execute", "--select-package", "dev.slslogjump",
              "--details=summary", "--disable-ansi-colors", "--fail-if-no-tests"], capture=True)
print(result.stdout, end="")
(BUILD / "test-results.txt").write_text(result.stdout + result.stderr)
shutil.copytree(ROOT / "src/main/resources", classes, dirs_exist_ok=True)
libs = BUILD / "libs"
libs.mkdir(exist_ok=True)
jar = libs / "aliyun-sls-log-jump-0.1.0.jar"
with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
    for p in sorted(classes.rglob("*")):
        if p.is_file():
            archive.write(p, p.relative_to(classes).as_posix())
dist = BUILD / "distributions"
dist.mkdir(exist_ok=True)
output = dist / "aliyun-sls-log-jump-0.1.0.zip"
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
    for p in [jar, *runtime]:
        archive.write(p, "aliyun-sls-log-jump/lib/" + p.name)
print("Plugin ZIP:", output)
