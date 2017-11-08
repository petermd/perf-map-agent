/*
 *   libperfmap: a JVM agent to create perf-<pid>.map files for consumption
 *               with linux perf-tools
 *   Copyright (C) 2013-2015 Johannes Rudolph<johannes.rudolph@gmail.com>
 *
 *   This program is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation; either version 2 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License along
 *   with this program; if not, write to the Free Software Foundation, Inc.,
 *   51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package net.virtualvoid.perf;

import java.io.*;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exports the symbols and maps required by a `perf` dump to a single compressed archive */
public class ExportCache {

    // Definitions

    public final static String DEFAULT_ATTACH_OPTIONS = "";

    private final static Pattern BUILD_ID = Pattern.compile("[a-fA-F0-9]+");
    private final static Pattern PATH = Pattern.compile("[^\\s]+");
    private final static Pattern PROCESS_MAP = Pattern.compile("(perf-)([\\d+]+).map$");

    // Processing

    /** Process list of files and generate a zipped archive */
    public void process(List<File> input, OutputStream out) throws Exception {

        ZipOutputStream zipOut = new ZipOutputStream(out);
        for (File f : input) {
            Matcher m = PROCESS_MAP.matcher(f.getName());
            // perf-NNN.map requires a generated PID map
            if (m.matches()) {
                String pid = m.group(2);
                mapProcess(f, pid, zipOut);
            }
            // Missing symbols
            else if (!f.exists()) {
                System.err.println(String.format("WARNING! Unable to resolve '%s'", f.getAbsolutePath()));
            }
            // Symbol file
            else {
                copy(f, zipOut);
            }
        }
        zipOut.close();
    }

    /** Parse input */
    public List<File> parse(InputStream in) throws IOException {

        List<File> res = new ArrayList<>();
        Scanner textIn = new Scanner(in);

        // Format is [NNNNNNNNNN] [PATH]
        while (textIn.hasNext(BUILD_ID)) {
            textIn.next(BUILD_ID);
            File path = new File(textIn.next(PATH));
            res.add(path);
        }

        return res;
    }

    // Implementation

    /** Map process */
    protected void mapProcess(File src, String pid, ZipOutputStream out) {
        try {
            // Generate Attach map and output file
            AttachOnce.loadAgent(pid, DEFAULT_ATTACH_OPTIONS);
            if (!src.exists()) {
                throw new UnsupportedOperationException(String.format("Expected map was not generated at %s", src.getAbsolutePath()));
            }
            // Copy the generated map
            copy(src, out);
        }
        catch (Throwable t) {
            System.err.println(String.format("WARNING! Mapping of process '%s' failed (e=%s)", pid, t));
        }
    }

    /** Copy file to archive */
    protected void copy(File src, ZipOutputStream out) throws IOException {

        // Use absolute path
        // PMCD: Question here re: ownership + permissions settings
        ZipEntry entry = new ZipEntry(src.getAbsolutePath());
        entry.setLastModifiedTime(FileTime.fromMillis(src.lastModified()));
        out.putNextEntry(entry);

        try (InputStream in = new FileInputStream(src)) {
            transferFromTo(in, out);
        }

        out.closeEntry();
    }

    /**
     * Simple stream-to-stream transfer
     *
     * @// Java9 use InputStream::transferTo
     */
    protected long transferFromTo(InputStream src, OutputStream dest) throws IOException {
        byte[] buf = new byte[2 << 12];
        long bt = 0;
        int br;
        while ((br = src.read(buf)) >= 0) {
            dest.write(buf, 0, br);
            bt += br;
        }
        return bt;
    }

    // Entry point

    /** Main */
    public static void main(String[] args) {
        int rc = 0;
        try (InputStream in=args.length > 0 ? new FileInputStream(args[0]) : System.in;
             OutputStream out=args.length > 1 ? new FileOutputStream(args[1]) : System.out) {
            ExportCache ec = new ExportCache();
            ec.process(ec.parse(in), out);
        }
        catch (Throwable t) {
            System.err.println("Export failed: " + t);
            t.printStackTrace(System.err);
            rc = -1;
        }
        // Exit once resources released
        System.exit(rc);
    }
}
