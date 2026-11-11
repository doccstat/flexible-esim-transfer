package com.lixingchi.flexibleesimtransfer;

import java.io.File;

final class RecordingWorker {
    static ProcessBuilder builder(int seconds) {
        if (!DiagnosticRecorder.validDuration(seconds)) throw new IllegalArgumentException("Invalid duration");
        // Ordinary su escapes the Manager's app cgroup; setsid detaches the shell session.
        return new ProcessBuilder("su", "-c",
                "export CLASSPATH=/data/adb/modules/flexible_esim_transfer/payload.jar; "
                        + "exec /system/bin/toybox setsid /system/bin/app_process /system/bin "
                        + "com.lixingchi.flexibleesimtransfer.ModuleControl record " + seconds)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.to(new File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.to(new File("/dev/null")));
    }
}
