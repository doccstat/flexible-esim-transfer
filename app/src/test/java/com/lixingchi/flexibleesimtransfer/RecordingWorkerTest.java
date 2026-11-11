package com.lixingchi.flexibleesimtransfer;

import org.junit.Test;
import java.io.File;
import java.util.List;
import static org.junit.Assert.*;

public class RecordingWorkerTest {
    @Test public void rootEscapePrecedesSessionDetachmentAndOnlyTheRecorderStarts() {
        var worker = RecordingWorker.builder(300);
        assertEquals(List.of("su", "-c",
                "export CLASSPATH=/data/adb/modules/flexible_esim_transfer/payload.jar; "
                        + "exec /system/bin/toybox setsid /system/bin/app_process /system/bin "
                        + "com.lixingchi.flexibleesimtransfer.ModuleControl record 300"), worker.command());
    }

    @Test public void workerDoesNotInheritManagerInputOrOutputPipes() {
        var worker = RecordingWorker.builder(900);
        assertEquals(ProcessBuilder.Redirect.Type.READ, worker.redirectInput().type());
        assertEquals(ProcessBuilder.Redirect.Type.WRITE, worker.redirectOutput().type());
        assertEquals(ProcessBuilder.Redirect.Type.WRITE, worker.redirectError().type());
        for (var redirect : List.of(worker.redirectInput(), worker.redirectOutput(), worker.redirectError()))
            assertEquals(new File("/dev/null"), redirect.file());
    }

    @Test public void durationIsBoundedAndPreservedWithoutLaunchingAnything() {
        for (int seconds : new int[]{1, 300, 900, 1800, 3600})
            assertTrue(RecordingWorker.builder(seconds).command().get(2).endsWith(" record " + seconds));
        for (int seconds : new int[]{Integer.MIN_VALUE, -1, 0, 3601, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> RecordingWorker.builder(seconds));
    }
}
