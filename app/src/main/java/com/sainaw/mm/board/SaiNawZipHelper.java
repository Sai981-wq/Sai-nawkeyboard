package com.sainaw.mm.board;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class SaiNawZipHelper {

    public static String getFileName(Context context, Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        if (result != null && result.toLowerCase().endsWith(".zip")) {
            result = result.substring(0, result.length() - 4);
        }
        return (result != null && !result.isEmpty()) ? result : "CustomPack_" + System.currentTimeMillis();
    }

    public static String extractZip(Context context, Uri zipUri) {
        String folderName = getFileName(context, zipUri);
        File baseDir = new File(context.getFilesDir(), "custom_sound_packs");
        if (!baseDir.exists()) baseDir.mkdirs();

        File targetDir = new File(baseDir, folderName);
        int count = 1;
        while (targetDir.exists()) {
            targetDir = new File(baseDir, folderName + "_" + count);
            count++;
        }
        targetDir.mkdirs();

        try (InputStream is = context.getContentResolver().openInputStream(zipUri);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            byte[] buffer = new byte[2048];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String fileName = new File(entry.getName()).getName();
                if (!fileName.endsWith(".mp3") && !fileName.endsWith(".wav") && !fileName.endsWith(".ogg")) continue;
                File outFile = new File(targetDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
                zis.closeEntry();
            }
            return targetDir.getName();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean zipFolder(File sourceFolder, OutputStream os) {
        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            File[] files = sourceFolder.listFiles();
            if (files == null) return false;
            byte[] buffer = new byte[2048];
            for (File file : files) {
                if (file.isDirectory()) continue;
                try (FileInputStream fis = new FileInputStream(file)) {
                    zos.putNextEntry(new ZipEntry(file.getName()));
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, length);
                    }
                    zos.closeEntry();
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

