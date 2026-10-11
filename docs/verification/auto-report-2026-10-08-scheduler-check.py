#!/usr/bin/env python3
"""Portable regression check for the sanitized app fault scheduler (2026-10-08).

LIMITED API-BOUNDARY EVIDENCE ONLY. This is NOT Android runtime execution.

The check compiles the actual repository sources for the scheduler, scheduling
policy, HTTP protocol, delivery, outbox and sanitized fault model together with
thin, hand-written stubs of the Android / AndroidX WorkManager API surface. It
then runs SchedulerCheck on a plain JVM. The stubs model only:

  * WorkManager unique-work bookkeeping as a single counter of queued jobs
    (REPLACE -> 1, APPEND_OR_REPLACE -> +1, cancelUniqueWork -> 0), and
  * a one-shot hook that runs inside WorkManager.getInstance so that a
    re-entrant schedule can be interleaved deterministically.

Nothing here proves real WorkManager, ACRA, broadcast, process or device
behavior. Stub sources live only in an auto-cleaned temporary directory; no
dependencies are downloaded and no repository file is modified.

Assertions (both fail against the current scheduler, by design; the check is
expected to turn GREEN once the product is fixed):
  1. A startup request with a blank endpoint cancels a previously queued job.
  2. A stale empty-snapshot cancel racing a re-entrant schedule for a newly
     recorded fault preserves the queued job.

Output is limited to numeric/boolean results and exit codes. The process exit
code is the exit code of the Java check (or non-zero on setup failure).

Usage: python3 docs/verification/auto-report-2026-10-08-scheduler-check.py
Uses JAVA_HOME/bin/{javac,java} when JAVA_HOME is set, otherwise PATH.
"""

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = (REPO_ROOT / "app" / "src" / "main" / "java" / "org" / "schabi"
              / "newpipe" / "error" / "autoreport")

CORE_SOURCES = [
    "AppFaultScheduler.java",
    "AppFaultScheduling.java",
    "AppFaultHttpProtocol.java",
    "AppFaultOutbox.java",
    "SanitizedAppFault.java",
]
STUBBED_CLASSES = {"AppFaultUploadWorker.java", "AppFaultEventReceiver.java"}

STUBS = {
    "android/content/Context.java": r'''package android.content;
public class Context {
    public Context getApplicationContext() { return this; }
    public java.io.File getNoBackupFilesDir() {
        return new java.io.File(System.getProperty("scheduler.check.dir"), "no-backup");
    }
    public void sendBroadcast(Intent i) { }
}
''',
    "android/content/Intent.java": r'''package android.content;
public class Intent { public Intent(Context c, Class<?> t) { } }
''',
    "androidx/annotation/NonNull.java": r'''package androidx.annotation;
public @interface NonNull { }
''',
    "androidx/annotation/Nullable.java": r'''package androidx.annotation;
public @interface Nullable { }
''',
    "androidx/work/ExistingWorkPolicy.java": r'''package androidx.work;
public enum ExistingWorkPolicy { REPLACE, APPEND_OR_REPLACE }
''',
    "androidx/work/NetworkType.java": r'''package androidx.work;
public enum NetworkType { CONNECTED }
''',
    "androidx/work/Constraints.java": r'''package androidx.work;
public class Constraints {
    public static class Builder {
        public Builder setRequiredNetworkType(NetworkType n) { return this; }
        public Constraints build() { return new Constraints(); }
    }
}
''',
    "androidx/work/OneTimeWorkRequest.java": r'''package androidx.work;
public class OneTimeWorkRequest {
    public static class Builder {
        public Builder(Class<?> c) { }
        public Builder setConstraints(Constraints c) { return this; }
        public Builder setInitialDelay(long t, java.util.concurrent.TimeUnit u) { return this; }
        public OneTimeWorkRequest build() { return new OneTimeWorkRequest(); }
    }
}
''',
    "androidx/work/Operation.java": r'''package androidx.work;
public class Operation {
    public java.util.concurrent.CompletableFuture<Object> getResult() {
        return java.util.concurrent.CompletableFuture.completedFuture(new Object());
    }
}
''',
    "androidx/work/WorkManager.java": r'''package androidx.work;
public class WorkManager {
    public static final WorkManager INSTANCE = new WorkManager();
    public static Runnable hook;
    public int queued;
    public static WorkManager getInstance(android.content.Context c) {
        Runnable h = hook;
        hook = null;
        if (h != null) {
            h.run();
        }
        return INSTANCE;
    }
    public Operation cancelUniqueWork(String n) {
        queued = 0;
        return new Operation();
    }
    public Operation enqueueUniqueWork(String n, ExistingWorkPolicy p, OneTimeWorkRequest r) {
        queued = p == ExistingWorkPolicy.REPLACE ? 1 : queued + 1;
        return new Operation();
    }
}
''',
    "org/schabi/newpipe/BuildConfig.java": r'''package org.schabi.newpipe;
public class BuildConfig { public static String APP_FAULT_ENDPOINT = ""; }
''',
    "org/schabi/newpipe/error/autoreport/AppFaultUploadWorker.java": r'''package org.schabi.newpipe.error.autoreport;
public class AppFaultUploadWorker { }
''',
    "org/schabi/newpipe/error/autoreport/AppFaultEventReceiver.java": r'''package org.schabi.newpipe.error.autoreport;
public class AppFaultEventReceiver { }
''',
    "org/schabi/newpipe/error/autoreport/SchedulerCheck.java": r'''package org.schabi.newpipe.error.autoreport;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import androidx.work.WorkManager;

public class SchedulerCheck {
    public static void main(String[] args) throws Exception {
        android.content.Context ctx = new android.content.Context();
        WorkManager wm = WorkManager.INSTANCE;

        // 1. Blank-endpoint startup request must cancel an old queued job.
        wm.queued = 1;
        CountDownLatch done = new CountDownLatch(1);
        AppFaultScheduler.request(ctx, done::countDown);
        boolean completed = done.await(5, TimeUnit.SECONDS);
        System.out.println("blank_endpoint_request_completed=" + completed);
        boolean blankBad = !completed || wm.queued != 0;
        System.out.println("blank_endpoint_jobs_after=" + wm.queued);
        System.out.println("blank_endpoint_cancel_ok=" + !blankBad);

        // 2. Stale empty snapshot cancel racing a re-entrant new-fault schedule.
        java.io.File dir = Files.createTempDirectory(
                new java.io.File(System.getProperty("scheduler.check.dir")).toPath(),
                "race-").toFile();
        AppFaultOutbox q = new AppFaultOutbox(dir);
        long now = 1700000000000L;
        SanitizedAppFault f = SanitizedAppFault.fromAcra(
                "java.lang.NullPointerException\n\tat org.schabi.newpipe.player.Player.run(Player.java:1)",
                1, 23).get();
        String endpoint = "https://relay.invalid/v1/fault";
        WorkManager.hook = () -> {
            try {
                q.enqueue(f, now);
                AppFaultScheduler.schedule(ctx, endpoint, q, now);
            } catch (Exception e) {
                throw new AssertionError("re-entrant schedule failed");
            }
        };
        AppFaultScheduler.schedule(ctx, endpoint, q, now);
        int pending = q.pending(now).size();
        boolean raceBad = pending == 1 && wm.queued == 0;
        System.out.println("race_pending=" + pending);
        System.out.println("race_jobs_after=" + wm.queued);
        System.out.println("race_queued_job_preserved=" + !raceBad);

        boolean ok = !blankBad && !raceBad;
        System.out.println("scheduler_check_passed=" + ok);
        System.exit(ok ? 0 : 1);
    }
}
''',
}


def find_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / name
        if candidate.is_file():
            return str(candidate)
    return shutil.which(name)


def actual_sources():
    sources = [SOURCE_DIR / name for name in CORE_SOURCES]
    delivery = sorted(p for p in SOURCE_DIR.glob("*Delivery*.java")
                      if p.name not in STUBBED_CLASSES)
    if len(delivery) != 1:
        return None
    sources.append(delivery[0])
    if not all(p.is_file() for p in sources):
        return None
    return sources


def main():
    javac = find_tool("javac")
    java = find_tool("java")
    print("jdk_found=" + str(bool(javac and java)).lower())
    if not (javac and java):
        return 2
    sources = actual_sources()
    print("actual_sources_found=" + str(len(sources) if sources else 0))
    if not sources:
        return 2

    with tempfile.TemporaryDirectory(prefix="scheduler-check-") as tmp:
        tmp_path = Path(tmp)
        stub_root = tmp_path / "stubs"
        classes = tmp_path / "classes"
        classes.mkdir()
        stub_files = []
        for rel, data in STUBS.items():
            p = stub_root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(data, encoding="utf-8")
            stub_files.append(str(p))

        compile_cmd = [javac, "-encoding", "UTF-8", "-nowarn", "-d", str(classes)]
        compile_cmd += [str(p) for p in sources] + stub_files
        compiled = subprocess.run(compile_cmd, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL, check=False)
        print("javac_exit=" + str(compiled.returncode))
        if compiled.returncode != 0:
            return 2

        run_cmd = [java, "-Dscheduler.check.dir=" + str(tmp_path), "-cp", str(classes),
                   "org.schabi.newpipe.error.autoreport.SchedulerCheck"]
        result = subprocess.run(run_cmd, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True, check=False)
        for line in result.stdout.splitlines():
            key, sep, value = line.partition("=")
            if sep and (value in ("true", "false") or value.lstrip("-").isdigit()):
                print(key + "=" + value)
        print("java_exit=" + str(result.returncode))
        return result.returncode


if __name__ == "__main__":
    sys.exit(main())
