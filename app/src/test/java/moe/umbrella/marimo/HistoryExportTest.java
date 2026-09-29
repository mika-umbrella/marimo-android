package moe.umbrella.marimo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/** JVM tests for the diary export's Android-free half: which files get picked
 *  up, the byte-for-byte stream copy (and its count, which the toast reports),
 *  and the size formatting. The MediaStore side needs a real device.
 *
 *  These write real files — the enumeration IS the logic under test — so they
 *  use a JUnit TemporaryFolder that is removed afterwards rather than a
 *  hand-rolled temp dir. */
public class HistoryExportTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void touch(File f, String body) throws Exception {
        try (FileWriter w = new FileWriter(f, StandardCharsets.UTF_8)) {
            w.write(body);
        }
    }

    @Test public void diaryFilesTakesTheLiveLogAndEveryArchive() throws Exception {
        File dir = tmp.newFolder();
        touch(new File(dir, "history.jsonl"), "{\"ts\":1}\n");
        touch(new File(dir, "history.2025.jsonl.gz"), "old");
        touch(new File(dir, "history.2024.jsonl.gz"), "older");
        // things that must NOT be exported
        touch(new File(dir, "history.jsonl.bak"), "backup");
        touch(new File(dir, "history.24.jsonl.gz"), "bad year");
        touch(new File(dir, "notes.txt"), "hello");
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "history.2019.jsonl.gz").mkdirs();   // a directory, not a file

        List<File> got = HistoryExport.diaryFiles(dir);
        assertEquals(3, got.size());
        assertEquals("history.2024.jsonl.gz", got.get(0).getName());  // archives first,
        assertEquals("history.2025.jsonl.gz", got.get(1).getName());  // oldest..newest,
        assertEquals("history.jsonl", got.get(2).getName());          // live log last
    }

    @Test public void diaryFilesEmptyWhenThereIsNoDiary() throws Exception {
        assertTrue(HistoryExport.diaryFiles(tmp.newFolder()).isEmpty());
        assertTrue(HistoryExport.diaryFiles(new File("/nope/nope")).isEmpty());
        assertTrue(HistoryExport.diaryFiles(null).isEmpty());
    }

    @Test public void copyStreamIsByteExactAndCounts() throws Exception {
        String body = "{\"ts\":1,\"artist\":\"Utsu-P\"}\nærtist\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = HistoryExport.copyStream(new ByteArrayInputStream(bytes), out);
        assertEquals(bytes.length, n);
        assertEquals(body, out.toString("UTF-8"));
    }

    @Test public void humanSizeReadsLikeAPersonWroteIt() {
        assertEquals("0 bytes", HistoryExport.humanSize(0));
        assertEquals("1 byte", HistoryExport.humanSize(1));
        assertEquals("812 bytes", HistoryExport.humanSize(812));
        assertEquals("1.0 KB", HistoryExport.humanSize(1024));
        assertEquals("18.4 KB", HistoryExport.humanSize(18_840));
        assertEquals("1024.0 KB", HistoryExport.humanSize(1024 * 1024 - 1));
        assertEquals("2.1 MB", HistoryExport.humanSize(2_200_000));
    }
}
