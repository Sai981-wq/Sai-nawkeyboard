package com.sainaw.mm.board;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SaiNawZipHelper {
    public static boolean extractZip(Context context, Uri zipUri, String targetDirName) {
        File targetDir = new File(context.getFilesDir(), targetDirName);
        if (targetDir.exists()) {
            File[] files = targetDir.listFiles();
            if (files != null) {
                for (File child : files) child.delete();
            }
        } else {
            targetDir.mkdirs();
        }
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
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
